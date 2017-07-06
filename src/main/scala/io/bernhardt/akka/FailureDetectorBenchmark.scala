package io.bernhardt.akka

import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.bernhardt.akka.BenchmarkNode.AwaitShutdown

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object FailureDetectorBenchmark {

  val Manager = "benchmark-coordinator-singleton-manager"

  def main(args: Array[String]) = {
    val props: Map[String, String] = args.flatMap { arg =>
      if (arg.startsWith("-D") && arg.contains("=")) {
        val Array(k, v) = arg.substring(2).split("=")
        Some(k -> v)
      } else {
        None
      }
    }.toMap

    startSystem(props)
  }

  def startSystem(properties: Map[String, String]): Unit = {
    import scala.collection.JavaConverters._
    val config = ConfigFactory.parseMap(properties.asJava).withFallback(ConfigFactory.load())
    val system: ActorSystem = ActorSystem("akka-fd-benchmark", config)
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

    val f = (node ? AwaitShutdown).mapTo[Shutdown]
    val termination = for {
      shutdown <- f
      _ <- system.terminate()
    } yield shutdown.properties

    val props = Await.result(termination, Duration.Inf)
    Await.result(system.whenTerminated, Duration.Inf)
    startSystem(props)
  }
}

case class Shutdown(properties: Map[String, String] = Map.empty)
