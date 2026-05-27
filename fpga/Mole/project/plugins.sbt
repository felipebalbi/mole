// sbt-scalafmt plugin --- enables `sbt scalafmtCheckAll` and `sbt
// scalafmtAll` against the project-level `.scalafmt.conf`. Used by
// the fpga-sim CI workflow to fail on style drift; locally,
// `sbt scalafmtAll` rewrites in place.
//
// Avoid writing the directive token (the word scalafmt immediately
// followed by a colon) anywhere in this file. scalafmt's StyleMap
// uses an unanchored regex to find file-local style overrides in
// comments, and a stray colon turns a descriptive comment into a
// malformed HOCON snippet, which crashes metaconfig's parser with
// "next on empty iterator" from inside Position.pretty.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
