package io.bernhardt.akka

import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.ask
import akka.util.Timeout
import io.bernhardt.akka.BenchmarkNode.AwaitShutdown

import scala.concurrent.Await
import scala.concurrent.duration._

object FailureDetectorBenchmark {

  val Manager = "benchmark-coordinator-singleton-manager"

  def main(args: Array[String]) = {
    args.foreach { arg =>
      if (arg.startsWith("-D") && arg.contains("=")) {
        val Array(k, v) = arg.substring(2).split("=")
        System.setProperty(k, v)
      }
    }
    startSystem()
  }

  def startSystem(): Unit = {
    val system: ActorSystem = ActorSystem("akka-fd-benchmark")
    val coordinatorSingletonManager = system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = BenchmarkCoordinator.props,
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system)
      ), Manager)

    val coordinatorSingletonProxy = system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = coordinatorSingletonManager.path.toStringWithoutAddress,
        settings = ClusterSingletonProxySettings(system)
      )
    )

    val node = system.actorOf(BenchmarkNode.props(coordinatorSingletonProxy), "benchmark-node")

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = Timeout(1.hour)
    val f = node ? AwaitShutdown
    for {
      _ <- f
      _ <- system.terminate()
    } yield ()

    Await.result(system.whenTerminated, Duration.Inf)
    startSystem()
  }
}

case object Shutdown
