name := "tldr-bot"

version := "1.0"

scalaVersion := "2.11.8"

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "2.0.0",
  "org.apache.spark" %% "spark-graphx" % "2.0.0",
  "org.clulab" %% "processors" % "5.9.0",
  "org.clulab" %% "processors" % "5.9.0" classifier "models",
  "org.typelevel" %% "cats" % "0.7.0",
  "com.github.pathikrit" %% "better-files" % "2.16.0",
  "com.aylien.textapi" % "client" % "0.6.0",
  "com.typesafe.akka" %% "akka-stream" % "2.4.9",
  "net.dean.jraw" % "JRAW" % "0.9.0",
  "com.github.cb372" %% "scalacache-redis" % "0.9.1",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0"
)