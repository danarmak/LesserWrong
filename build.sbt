name := "lesserwrong"

version := "0.0.1"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.postgresql" % "postgresql" % "9.4.1212",
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.1.1",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "com.typesafe" % "config" % "1.3.1",
  "org.scala-lang.modules" %% "scala-async" % "0.9.6",
  "com.github.blemale" %% "scaffeine" % "1.3.0",
  "com.typesafe.akka" %% "akka-http" % "10.0.0",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.0",
//  "de.heikoseeberger" % "akka-http-upickle_2.11" % "1.11.0",
//  "com.lihaoyi" %% "upickle" % "0.4.3"
//  "de.heikoseeberger" % "akka-http-argonaut_2.11" % "1.11.0",
  "com.google.code.findbugs" % "jsr305" % "3.0.1" % "compile"
)
