package io.bernhardt.akka

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, FSM, Props}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, ClusterEvent, Member, UniqueAddress}
import io.bernhardt.akka.BenchmarkCoordinator._
import io.bernhardt.akka.BenchmarkNode.{BecomeUnreachable, ExpectUnreachable, Reconfigure}
import org.HdrHistogram.Histogram

import scala.concurrent.duration._
import scala.util.Random

class BenchmarkCoordinator extends Actor with FSM[State, Data] with ActorLogging {

  val cluster = Cluster(context.system)

  val expectedMembers = context.system.settings.config.getInt("benchmark.expected-members")

  val warmupTime = Duration.create(context.system.settings.config.getDuration("benchmark.warmup-time").getSeconds, TimeUnit.SECONDS)

  val rounds = context.system.settings.config.getInt("benchmark.rounds")

  val plan: List[RoundConfiguration] = {
    import scala.collection.JavaConverters._
    context.system.settings.config.getConfigList("benchmark.plan").asScala.map { c =>
      RoundConfiguration(c.getString("fd"), c.getDouble("threshold"))
    }
  }.toList

  val step = Option(System.getProperty("benchmark.step")).getOrElse("0").toInt

  val detectionTiming = new Histogram(10.seconds.toMicros, 3)

  var warmedUp: Boolean = false

  override def preStart() = {
    super.preStart()
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[MemberUp], classOf[MemberRemoved], classOf[UnreachableMember], classOf[ReachableMember])
  }

  override def postStop() = {
    super.postStop()
    cluster.unsubscribe(self)
  }

  startWith(Waiting, WaitingData(Set.empty, 1))

  when(Waiting, warmupTime) {
    case Event(StateTimeout, data: WaitingData) =>
      warmedUp = true
      startRound(data.members, data.round)
    case Event(MemberUp(member), data: WaitingData) =>
      val members = data.members + member
      if (members.size == expectedMembers && warmedUp) {
        startRound(members, data.round)
      } else {
        stay() using data.copy(members = members)
      }
    case Event(MemberRemoved(member, _), data: WaitingData) =>
      stay() using data.copy(members = data.members - member)
  }

  when(Benchmarking) {
    case Event(MemberUnreachabilityDetected(member, duration), data: BenchmarkData) =>
      val durations = data.detectionDurations + (member -> duration)
      if (durations.size == expectedMembers - 1) {
        self ! RoundFinished
      }
      stay() using data.copy(detectionDurations = durations)
    case Event(RoundFinished, data: BenchmarkData) =>
      onRoundFinished(data)
    case Event(MemberUp(member), data: BenchmarkData) =>
      stay() using data.copy(members = data.members + member)
    case Event(UnreachableMember(member), data: BenchmarkData) if member.uniqueAddress == data.target =>
      cluster.down(member.address)
      stay() using data.copy(members = data.members - member)
    case Event(MemberRemoved(member, _), data: BenchmarkData) =>
      stay() using data.copy(members = data.members - member)
  }

  when(Done) {
    case Event(any, _) =>
      log.info(any.toString)
      stay()
  }

  private def startRound(members: Set[Member], round: Int) = {
    val candidates = members.filterNot(_.address == cluster.selfAddress)
    val target = candidates.toList(Random.nextInt(candidates.size))

    log.info(
      """
        |*********************
        |Starting benchmarking round {} / {} with {} member nodes, making {} unreachable
        |*********************""".stripMargin, round, rounds, expectedMembers, target.address)
    sendMessageToAll(members, ExpectUnreachable(target))
    shutdownMember(target)
    goto(Benchmarking) using BenchmarkData(round = round, target = target.uniqueAddress, members = members)
  }

  private def onRoundFinished(data: BenchmarkData) = {
    log.info("Round {} done".stripMargin, data.round)
    data.detectionDurations.values.foreach { durationNanos =>
      detectionTiming.recordValue(durationNanos.nanos.toMicros)
    }

    if (data.round == rounds) {
      reportRoundResults()
      configureStep(data.members)
    } else {
      if (data.members.size < expectedMembers) {
        log.info("Waiting for enough members to join")
        goto(Waiting) using WaitingData(data.members, data.round + 1)
      } else {
        startRound(data.members, data.round + 1)
      }
    }
  }

  private def sendMessageToAll(members: Set[Member], message: Any): Unit = {
    members.foreach { m =>
      context.actorSelection(BenchmarkNode.path(m.address)) ! message
    }
  }

  private def shutdownMember(member: Member): Unit = {
    context.actorSelection(BenchmarkNode.path(member.address)) ! BecomeUnreachable
  }

  private def reportRoundResults(): Unit = {
    val out = new ByteArrayOutputStream()
    detectionTiming.outputPercentileDistribution({
      new PrintStream(out)
    }, 1.0)
    val histogram = new String(out.toByteArray, StandardCharsets.UTF_8)
    val report =
      s"""
         |*********
         |Benchmark report for ${cluster.settings.FailureDetectorImplementationClass}
         |$expectedMembers nodes
         |$rounds rounds
         |
         |Threshold: ${cluster.settings.FailureDetectorConfig.getDouble("threshold")}
         |
         |50% percentile: ${detectionTiming.getValueAtPercentile(50)} µs
         |90% percentile: ${detectionTiming.getValueAtPercentile(90)} µs
         |99% percentile: ${detectionTiming.getValueAtPercentile(99)} µs
         |
         |Detection latencies (µs):
         |
         |$histogram
         |*********
        """.stripMargin
    log.info(report)
    Reporting.email(report, context.system)
  }

  private def configureStep(members: Set[Member]) = {
    val nextStep = step + 1
    if (nextStep == plan.size) {
      log.info("Benchmark done!")
      goto(Done)
    } else {
      System.setProperty("benchmark.step", nextStep.toString)
      sendMessageToAll(members, Reconfigure(plan(nextStep).implementationClass, plan(nextStep).threshold))
      goto(Waiting)
    }

  }

}

object BenchmarkCoordinator {
  def props = Props(classOf[BenchmarkCoordinator])

  val name = "benchmark-coordinator"

  sealed trait State

  case object Waiting extends State

  case object Benchmarking extends State

  case object Done extends State

  sealed trait Data

  case class WaitingData(members: Set[Member], round: Int) extends Data

  case class BenchmarkData(round: Int, target: UniqueAddress, detectionDurations: Map[UniqueAddress, Long] = Map.empty, members: Set[Member], start: Long = System.nanoTime()) extends Data

  case class RoundConfiguration(implementationClass: String, threshold: Double)

  // events
  case object RoundFinished

  case class MemberUnreachabilityDetected(detectedBy: UniqueAddress, duration: Long)


}
