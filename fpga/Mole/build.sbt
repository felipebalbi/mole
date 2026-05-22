ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "io.mole"

val spinalVersion = "1.14.1"

val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin(
  "com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion
)

// Mole has no cross-project sbt deps. The bit engine, UART, and SPRAM
// controller are all owned in-tree under src/hw/. See AGENTS.md.
lazy val mole = (project in file("."))
  .settings(
    name := "mole",
    Compile / scalaSource := baseDirectory.value / "src",
    libraryDependencies ++= Seq(
      spinalCore,
      spinalLib,
      spinalIdslPlugin,
      "com.github.spinalhdl" %% "spinalhdl-sim" % spinalVersion
    )
  )

fork := true
