val scala3Version = "3.3.3"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "jpyc-qr-signboard",
    version      := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % "4.1.0"
    ),
    assembly / assemblyJarName := s"jpyc-qr-signboard-${version.value}.jar",
  )
