package io.bernhardt.akka

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import akka.actor.{Actor, ActorLogging, FSM, Props}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, ClusterEvent, Member, UniqueAddress}
import io.bernhardt.akka.BenchmarkCoordinator._
import io.bernhardt.akka.BenchmarkNode.{BecomeUnreachable, ExpectUnreachable}
import org.HdrHistogram.Histogram

import scala.concurrent.duration._
import scala.util.Random

class BenchmarkCoordinator extends Actor with FSM[State, Data] with ActorLogging {

  val cluster = Cluster(context.system)

  val expectedMembers = context.system.settings.config.getInt("benchmark.expected-members")

  val rounds = context.system.settings.config.getInt("benchmark.rounds")

  val detectionTiming = new Histogram(10.seconds.toMicros, 3)

  override def preStart() = {
    super.preStart()
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[MemberUp], classOf[MemberRemoved], classOf[UnreachableMember], classOf[ReachableMember])
  }

  override def postStop() = {
    super.postStop()
    cluster.unsubscribe(self)
  }

  startWith(Waiting, WaitingData(Set.empty, 1))

  when(Idle) {
    case Event(any, _) =>
      log.info(any.toString)
      stay()
  }

  when(Waiting) {
    case Event(MemberUp(member), data: WaitingData) =>
      val members = data.members + member
      if (members.size == expectedMembers) {
        startRound(members, data.round)
      } else {
        stay() using data.copy(members = members)
      }
    case Event(MemberRemoved(member, _), data: WaitingData) =>
      stay() using data.copy(members = data.members - member)
  }

  when(Benchmarking) {
    case Event(MemberUnreachabilityDetected(member, timestamp), data: BenchmarkData) =>
      val timestamps = data.unreachabilityTimestamps + (member -> timestamp)
      if (timestamps.size == expectedMembers - 1) {
        self ! RoundFinished
      }
      stay() using data.copy(unreachabilityTimestamps = timestamps)
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

  private def startRound(members: Set[Member], round: Int) = {
    val candidates = members.filterNot(_.address == cluster.selfAddress)
    val member = candidates.toList(Random.nextInt(candidates.size))

    log.info(
      """
        |*********************
        |Starting benchmarking round {} with {} member nodes, making {} unreachable
        |*********************""".stripMargin, round, expectedMembers, member.address)
    informMembers(member, members)
    shutdownMember(member)
    goto(Benchmarking) using BenchmarkData(round = round, target = member.uniqueAddress, members = members)
  }

  private def onRoundFinished(data: BenchmarkData) = {
    log.info("Round {} done".stripMargin, data.round)
    data.unreachabilityTimestamps.values.foreach { timestamp =>
      val duration = (timestamp - data.start).nanos.toMicros
      detectionTiming.recordValue(duration)
    }

    if (data.round == 10) {
      val out = new ByteArrayOutputStream()
      detectionTiming.outputPercentileDistribution({
        new PrintStream(out)
      }, 1.0)
      val histogram = new String(out.toByteArray, StandardCharsets.UTF_8)
      val report = s"""
          |*********
          |Benchmark report for ${cluster.settings.FailureDetectorImplementationClass}
          |$expectedMembers nodes
          |$rounds rounds
          |
          |50% percentile: ${detectionTiming.getValueAtPercentile(50)} µs
          |90% percentile: ${detectionTiming.getValueAtPercentile(90)} µs
          |59% percentile: ${detectionTiming.getValueAtPercentile(59)} µs
          |
          |Detection latencies (µs):
          |
          |$histogram
          |*********
        """.stripMargin
      log.info(report)
      Reporting.email(report, context.system)
      goto(Idle)
    } else {
      if (data.members.size < expectedMembers) {
        log.info("Waiting for enough members to join")
        goto(Waiting) using WaitingData(data.members, data.round + 1)
      } else {
        startRound(data.members, data.round + 1)
      }
    }
  }

  private def informMembers(target: Member, members: Set[Member]): Unit = {
    members.foreach { m =>
      context.actorSelection(BenchmarkNode.path(m.address)) ! ExpectUnreachable(target)
    }
  }

  private def shutdownMember(member: Member): Unit = {
    context.actorSelection(BenchmarkNode.path(member.address)) ! BecomeUnreachable
  }

}

object BenchmarkCoordinator {
  def props = Props(classOf[BenchmarkCoordinator])

  val name = "benchmark-coordinator"

  sealed trait State

  case object Waiting extends State

  case object Idle extends State

  case object Benchmarking extends State

  sealed trait Data

  case class WaitingData(members: Set[Member], round: Int) extends Data

  case class BenchmarkData(round: Int, target: UniqueAddress, unreachabilityTimestamps: Map[UniqueAddress, Long] = Map.empty, members: Set[Member], start: Long = System.nanoTime()) extends Data

  // events
  case object RoundFinished

  case class MemberUnreachabilityDetected(detectedBy: UniqueAddress, timestamp: Long)


}
