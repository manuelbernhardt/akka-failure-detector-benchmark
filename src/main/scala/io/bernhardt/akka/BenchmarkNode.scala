package io.bernhardt.akka

import akka.actor.{Actor, ActorLogging, ActorRef, Address, Props, RootActorPath}
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.{Cluster, ClusterEvent, Member}
import io.bernhardt.akka.BenchmarkCoordinator.MemberUnreachabilityDetected
import io.bernhardt.akka.BenchmarkNode.{BecomeUnreachable, ExpectUnreachable, ShouldShutdown}

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
    case BecomeUnreachable =>
      log.info("Becoming unreachable by shutting down actor system")
      systemManager.map { r =>
        r ! Shutdown
      } getOrElse {
        log.error("No reference to system manager")
      }
    case ShouldShutdown =>
      systemManager = Some(sender())
    case ExpectUnreachable(member) =>
      start = Some(System.nanoTime())
      expectedUnreachable = Some(member)
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
}

object BenchmarkNode {
  def props(coordinator: ActorRef) = Props(classOf[BenchmarkNode], coordinator)
  val name = "benchmark-node"

  def path(address: Address) = RootActorPath(address) / "user" / name

  case object BecomeUnreachable

  case class ExpectUnreachable(member: Member)

  case object ShouldShutdown

}
