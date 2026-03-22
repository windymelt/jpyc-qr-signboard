val scala3Version = "3.3.3"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "jpyc-qr-signboard",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % "4.1.0"
    )
  )

inThisBuild(List(
  organization := "dev.capslock",
  homepage := Some(url("https://github.com/windymelt/jpyc-qr-signboard")),
  licenses := List(
    "BSD-3-Clause" -> url("https://spdx.org/licenses/BSD-3-Clause.html"),
  ),
  developers := List(
    Developer(
      "windymelt",
      "Windymelt",
      "windymelt@capslock.dev",
      url("https://www.3qe.us")
    )
  )
))
