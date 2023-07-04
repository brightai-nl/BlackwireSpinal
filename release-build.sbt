ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.11.12"
ThisBuild / organization := "online.blackwire"

val spinalVersion = "1.8.1"
//val spinalVersion = "dev"

val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalTester = "com.github.spinalhdl" %% "spinalhdl-tester" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

val sourceCode = "com.lihaoyi" %% "sourcecode" % "0.2.7"
val scalaTest = "org.scalatest" % "scalatest_2.11" % "2.2.1"
      
lazy val blackwireSpinal = (project in file("."))
  .settings(
    name := "BlackwireSpinal",
    libraryDependencies ++= Seq(sourceCode, scalaTest),
    libraryDependencies ++= Seq(spinalCore, spinalLib, spinalTester, spinalIdslPlugin)
  )
  .dependsOn(spinalCorundum)

lazy val spinalCorundum = ProjectRef(file("../SpinalCorundum"), "spinalCorundum")

fork := true
