name := "lesserwrong"

version := "0.0.1"

val projects = crossProject.settings(
  unmanagedSourceDirectories in Compile += baseDirectory.value / "shared" / "main" / "src" / "scala",
  scalaVersion := "2.11.8",
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-async" % "0.9.6",
    "com.lihaoyi" %%% "upickle" % "0.4.3",
    "com.google.code.findbugs" % "jsr305" % "3.0.1" % "compile"
  )
).jsSettings(
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "0.9.1"
  )
).jvmSettings(
  libraryDependencies ++= Seq(
    "org.postgresql" % "postgresql" % "9.4.1212",
    "com.typesafe.slick" %% "slick" % "3.1.1",
    "com.typesafe.slick" %% "slick-hikaricp" % "3.1.1",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "com.typesafe" % "config" % "1.3.1",
    "com.github.blemale" %% "scaffeine" % "1.3.0",
    "com.typesafe.akka" %% "akka-http" % "10.0.0",
    "de.heikoseeberger" % "akka-http-upickle_2.11" % "1.11.0"
  )
)

lazy val js = projects.js

lazy val jvm = projects.jvm.settings(
  (resources in Compile) += (fastOptJS in (js, Compile)).value.data
)

