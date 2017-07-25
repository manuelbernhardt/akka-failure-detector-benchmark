package io.bernhardt.akka

import akka.actor.{ActorSystem, PoisonPill}
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.bernhardt.akka.BenchmarkNode.AwaitShutdown

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

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
    val systemName = Option(System.getenv("SYSTEM_NAME")).getOrElse("akka-fd-benchmark")
    val config = ConfigFactory.parseMap(properties.asJava).withFallback(ConfigFactory.load())
    val step = Option(config.getInt("benchmark.step")).getOrElse("0")
    val system: ActorSystem = ActorSystem(s"$systemName-$step", config)
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

    val cluster = Cluster(system)
    ClusterHttpManagement(cluster).start()

    val node = system.actorOf(BenchmarkNode.props(coordinatorSingletonProxy), "benchmark-node")

    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val timeout = Timeout(1.hour)

    // wait until the system is asked to shutdown, retrieve new properties and start anew
    val f = (node ? AwaitShutdown).mapTo[Shutdown]

    val termination = (for {
      shutdown <- f
      _ <- system.terminate()
      _ <- system.whenTerminated
    } yield shutdown.properties).recover { case NonFatal(_) => properties }

    // if the system terminates unexpectedly we still want to restart using the previous properties
    system.whenTerminated.foreach { _ =>
      if (!f.isCompleted) {
        startSystem(properties)
      }
    }

    val props = Await.result(termination, Duration.Inf)
    startSystem(props)
  }
}

case class Shutdown(properties: Map[String, String] = Map.empty)
