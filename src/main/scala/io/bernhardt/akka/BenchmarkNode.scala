package io.bernhardt.akka

import akka.actor.{Actor, ActorLogging, ActorRef, Address, Cancellable, Props, RootActorPath}
import akka.cluster.ClusterEvent.{MemberEvent, UnreachableMember}
import akka.cluster.{Cluster, ClusterEvent, Member, UniqueAddress}
import io.bernhardt.akka.BenchmarkCoordinator.{ExpectUnreachableAck, MemberUnreachabilityDetected, ReconfigurationAck}
import io.bernhardt.akka.BenchmarkNode._

import scala.concurrent.duration._

class BenchmarkNode(coordinator: ActorRef) extends Actor with ActorLogging {

  var systemManager: Option[ActorRef] = None

  var expectedUnreachable: Option[Member] = None

  var start: Option[Long] = None

  val cluster = Cluster(context.system)

  var resolutionScheduler: Option[Cancellable] = None

  override def preStart() = {
    super.preStart()
    log.info("Started node at {} in system {}", cluster.selfUniqueAddress, context.system.name)
    cluster.subscribe(self, ClusterEvent.initialStateAsEvents, classOf[UnreachableMember])
  }

  override def postStop() = {
    cluster.unsubscribe(self)
    super.postStop()
  }

  def receive = {
    case AwaitShutdown =>
      systemManager = Some(sender())
    case BecomeUnreachable(uniqueAddress) if uniqueAddress == cluster.selfUniqueAddress =>
      log.info("Becoming unreachable by shutting down actor system")
      shutdown()
    case BecomeUnreachable(_) =>
      start = Some(System.nanoTime())
    case Reconfigure(implementationClass, threshold, step) =>
      log.info(s"Reconfiguring node to use $implementationClass with threshold $threshold at step $step")
      sender() ! ReconfigurationAck
      cluster.leave(cluster.selfAddress)
      shutdown(Map(
        "akka.cluster.failure-detector.threshold" -> threshold.toString,
        "akka.cluster.failure-detector.implementation-class" -> implementationClass,
        "benchmark.step" -> step.toString
      ))
    case ExpectUnreachable(member) =>
      // set the unreachable member the first time and start counting in case we don't get the second message in time
      expectedUnreachable = Some(member)
      start = Some(System.nanoTime())
      sender() ! ExpectUnreachableAck(cluster.selfUniqueAddress)
      log.info("Expecting {} to become unreachable", member.address)
    case UnreachableMember(member) if Some(member) == expectedUnreachable =>
      log.info("Reporting unreachability of {} to coordinator", member.address)
      val duration = start.map(s => System.nanoTime() - s).getOrElse(0l)
      coordinator ! MemberUnreachabilityDetected(cluster.selfUniqueAddress, duration)
      start = None
      expectedUnreachable = None
    case UnreachableMember(member) =>
      log.info("OTHER UNREACHABLE {}, {}", member, expectedUnreachable)
      scheduleResolution()
    case _: MemberEvent if cluster.state.unreachable.isEmpty =>
      resolutionScheduler.foreach(_.cancel())
      resolutionScheduler = None
    case _: MemberEvent =>
      scheduleResolution()
    case SimpleMajorityResolution =>
      simpleMajorityResolution()
  }

  def shutdown(properties: Map[String, String] = Map.empty): Unit = {
    systemManager.map { r =>
      r ! Shutdown(properties)
    } getOrElse {
      log.error("No reference to system manager")
    }
  }

  def scheduleResolution() = {
    resolutionScheduler.foreach(_.cancel)
    context.system.scheduler.scheduleOnce(10.seconds, self, SimpleMajorityResolution)(context.dispatcher)
  }

  def simpleMajorityResolution() = {
    resolutionScheduler = None
    val unreachable = cluster.state.unreachable
    val reachable = cluster.state.members.diff(unreachable)
    if (reachable.size > unreachable.size) {
      unreachable.map(_.address).foreach(cluster.down)
    } else if (reachable.size < unreachable.size) {
      log.info("We're in minority - downing and shutting down")
      reachable.map(_.address).foreach(cluster.down)
      systemManager.foreach(_ ! Shutdown)
    } else {
      log.warning("Can't perform SBR based on simple majority, both partitions are of equal size")
    }
  }

}

object BenchmarkNode {

  def props(coordinator: ActorRef) = Props(classOf[BenchmarkNode], coordinator)

  val name = "benchmark-node"

  def path(address: Address) = RootActorPath(address) / "user" / name

  case object SimpleMajorityResolution

  case class BecomeUnreachable(address: UniqueAddress)

  case class ExpectUnreachable(member: Member)

  case object AwaitShutdown

  case class Reconfigure(implementationClass: String, threshold: Double, step: Int)

}
