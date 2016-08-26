name := "tldr-bot"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "2.0.0",
  "org.apache.spark" %% "spark-graphx" % "2.0.0",
  "org.clulab" %% "processors" % "5.9.0",
  "org.clulab" %% "processors" % "5.9.0" classifier "models",
  "org.typelevel" %% "cats" % "0.7.0"
)