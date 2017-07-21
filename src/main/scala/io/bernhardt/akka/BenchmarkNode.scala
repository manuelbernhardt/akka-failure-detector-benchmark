package io.bernhardt.akka

import akka.actor.{Actor, ActorLogging, ActorRef, Address, Props, RootActorPath}
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.{Cluster, ClusterEvent, Member, UniqueAddress}
import io.bernhardt.akka.BenchmarkCoordinator.{ExpectUnreachableAck, MemberUnreachabilityDetected, ReconfigurationAck}
import io.bernhardt.akka.BenchmarkNode.{AwaitShutdown, BecomeUnreachable, ExpectUnreachable, Reconfigure}

class BenchmarkNode(coordinator: ActorRef) extends Actor with ActorLogging {

  var systemManager: Option[ActorRef] = None

  var expectedUnreachable: Option[Member] = None

  var start: Option[Long] = None

  val cluster = Cluster(context.system)

  override def preStart() = {
    super.preStart()
    log.info("Started node at {}", cluster.selfUniqueAddress)
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
      log.info(s"Reconfiguring node to use $implementationClass with threshold $threshold")
      sender() ! ReconfigurationAck
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
  }

  def shutdown(properties: Map[String, String] = Map.empty): Unit = {
    systemManager.map { r =>
      r ! Shutdown(properties)
    } getOrElse {
      log.error("No reference to system manager")
    }
  }
}

object BenchmarkNode {
  def props(coordinator: ActorRef) = Props(classOf[BenchmarkNode], coordinator)
  val name = "benchmark-node"

  def path(address: Address) = RootActorPath(address) / "user" / name

  case class BecomeUnreachable(address: UniqueAddress)

  case class ExpectUnreachable(member: Member)

  case object AwaitShutdown

  case class Reconfigure(implementationClass: String, threshold: Double, step: Int)

}
