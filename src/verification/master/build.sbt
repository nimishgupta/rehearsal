name := "master"

version := "0.1"

scalaVersion := "2.10.3"

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xfatal-warnings"
)

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.akka.io/snapshots/"
)

libraryDependencies ++= {
  val akkaV = "2.3.4"
  val graphV = "1.9.0"
  Seq(
    "org.scalatest" % "scalatest_2.10" % "2.1.3" % "test",
    "org.scalacheck" %% "scalacheck" % "1.10.1" % "test",
    "com.assembla.scala-incubator" %% "graph-core" % graphV,
    "com.assembla.scala-incubator" %% "graph-dot"  % graphV,
    "com.typesafe.akka" %% "akka-actor"  % akkaV,
    "com.typesafe.akka" %% "akka-kernel" % akkaV,
    "com.typesafe.akka" %% "akka-remote" % akkaV
  )
}