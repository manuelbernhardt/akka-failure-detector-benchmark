
name := "akka-failure-detector-benchmark"

version := "1.0"

scalaVersion := "2.12.2"

lazy val akkaVer = "2.5.3"
lazy val logbackVer = "1.1.3"

resolvers += Resolver.bintrayRepo("hseeberger", "maven")

resolvers += Resolver.bintrayRepo("tecsisa", "maven-bintray-repo")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVer,
  "com.typesafe.akka" %% "akka-actor" % akkaVer,
  "com.typesafe.akka" %% "akka-cluster" % akkaVer,
  "com.typesafe.akka" %% "akka-cluster-tools" % akkaVer,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVer,
  "ch.qos.logback"     % "logback-classic" % logbackVer,
  "com.lightbend.akka" %% "akka-management-cluster-http" % "0.3",
  "de.heikoseeberger" %% "constructr" % "0.17.0",
  "com.tecsisa"       %% "constructr-coordination-consul" % "0.7.0",
  "org.hdrhistogram"   % "HdrHistogram" % "2.1.9",
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.0.0",

  "com.typesafe.akka" %% "akka-testkit" % akkaVer,
  "org.scalatest"     %% "scalatest" % "3.0.1" % "test"
)

assemblyJarName in assembly := "akka-fd-benchmark.jar"

assemblyMergeStrategy in assembly := {
  case PathList("application-dev.conf") => MergeStrategy.discard
  case x => MergeStrategy.defaultMergeStrategy(x)
}

enablePlugins(JavaAppPackaging)