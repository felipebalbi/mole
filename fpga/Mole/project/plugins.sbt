// sbt-scalafmt: enables `sbt scalafmtCheckAll` and `sbt scalafmtAll`
// against the project-level `.scalafmt.conf`. Used by the
// fpga-sim CI workflow to fail on style drift; locally,
// `sbt scalafmtAll` rewrites in place.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
