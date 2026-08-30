lazy val V = (
  betterMonadicFor = "0.3.1",
  brotli = "0.1.2",
  brotli4j = "1.23.0",
  catsEffect = "3.7.1",
  commonsCompress = "1.28.0",
  fs2 = "3.13.0",
  logbackClassic = "1.6.3",
  lz4 = "1.8.1",
  munitCatsEffect = "2.2.0",
  snappy = "1.1.10.8",
  zip4j = "2.11.6",
  zstdJni = "1.5.7-16"
)

lazy val scalaVersions = Seq("3.3.8", "2.13.18", "2.12.21")

scalaVersion := scalaVersions.head
versionScheme := Some("early-semver")
organization := "de.lhns"

lazy val commonSettings: SettingsDefinition = Def.settings(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env
      .get("CI_VERSION")
      .collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage := scmInfo.value.map(_.browseUrl),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/lhns/fs2-compress"),
      "scm:git@github.com:lhns/fs2-compress.git"
    )
  ),
  developers := List(
    Developer(
      id = "lhns",
      name = "Pierre Kisters",
      email = "pierrekisters@gmail.com",
      url = url("https://github.com/lhns/")
    )
  ),
  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
    "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test
  ),
  testFrameworks += new TestFramework("munit.Framework"),
  // Run tests in their own JVM with a heap of their own, so that how much memory they have does not depend on how sbt
  // was started. Without this they share the build tool's heap, which differs between a native sbt and a BSP server.
  // Only the JVM rows: Scala.js refuses to run its tests in a forked JVM, and the root project is not a matrix and has
  // no tests of its own.
  Test / fork := virtualAxes.?.value.getOrElse(Seq.empty).contains(VirtualAxis.jvm),
  // The heap is deliberately settable: the memory checks run with a small one, so that a codec holding on to what it is
  // given runs out of memory rather than merely being slower. See MemorySuite. Only where the tests fork, since sbt
  // warns about java options it cannot pass on.
  Test / javaOptions ++= {
    if ((Test / fork).value) Seq(sys.env.getOrElse("FS2_COMPRESS_TEST_XMX", "-Xmx1G")) else Nil
  },
  // Keep the memory checks out of an ordinary run. They are slow and only mean anything with a small heap, so the
  // memory-check workflow sets FS2_COMPRESS_MEMORY_CHECK and runs them on their own.
  Test / testOptions ++= {
    if (sys.env.contains("FS2_COMPRESS_MEMORY_CHECK")) Nil
    else Seq(Tests.Filter(name => !name.endsWith("MemorySuite")))
  },
  libraryDependencies ++= virtualAxes.?.value.getOrElse(Seq.empty).collectFirst {
    case VirtualAxis.ScalaVersionAxis(version, _) if version.startsWith("2.") =>
      compilerPlugin("com.olegpy" %% "better-monadic-for" % V.betterMonadicFor)
  },
  Compile / doc / sources := Seq.empty,
  publishMavenStyle := true,
  // sbt knows how to publish to the Central Portal but does not point publishTo anywhere by
  // itself. A release is staged on disk and uploaded from there by sonaRelease, which refuses a
  // snapshot, so a build without a tag goes to the snapshot repository instead.
  publishTo := {
    val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
    if (version.value.endsWith("-SNAPSHOT")) Some("central-snapshots".at(centralSnapshots))
    else localStaging.value
  },
  // Publishing to the Central Portal looks credentials up by host.
  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "central.sonatype.com",
    username,
    password
  )).toList
)

// The root aggregate needs a name of its own: sharing core's would put the two in the same output directory.
lazy val root: Project =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      publishArtifact := false,
      publish / skip := true
    )
    .aggregate(core.projectRefs: _*)
    .aggregate(gzip.projectRefs: _*)
    .aggregate(zip.projectRefs: _*)
    .aggregate(zip4j.projectRefs: _*)
    .aggregate(tar.projectRefs: _*)
    .aggregate(zstd.projectRefs: _*)
    .aggregate(bzip2.projectRefs: _*)
    .aggregate(brotli.projectRefs: _*)
    .aggregate(brotli4j.projectRefs: _*)
    .aggregate(lz4.projectRefs: _*)
    .aggregate(snappy.projectRefs: _*)

lazy val core = projectMatrix
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "fs2-compress",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % V.fs2,
      "org.typelevel" %% "cats-effect" % V.catsEffect
    )
  )
  // fs2-io is JVM only here: OutputStreams wraps fs2.io.readOutputStream, which does not exist on JS.
  .jvmPlatform(
    scalaVersions,
    Seq(libraryDependencies += "co.fs2" %% "fs2-io" % V.fs2)
  )
  .jsPlatform(
    scalaVersions,
    // Only the Scala.js rows have a linker to configure.
    Seq(Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
  )

lazy val gzip = projectMatrix
  .in(file("gzip"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-gzip",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2
    )
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(
    scalaVersions,
    // Only the Scala.js rows have a linker to configure.
    Seq(Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
  )

lazy val zip = projectMatrix
  .in(file("zip"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-zip",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2
    )
  )
  .jvmPlatform(scalaVersions)

lazy val zip4j = projectMatrix
  .in(file("zip4j"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-zip4j",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "net.lingala.zip4j" % "zip4j" % V.zip4j
    )
  )
  .jvmPlatform(scalaVersions)

lazy val tar = projectMatrix
  .in(file("tar"))
  .dependsOn(core % "compile->compile;test->test")
  .dependsOn(gzip % "test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-tar",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.apache.commons" % "commons-compress" % V.commonsCompress
    )
  )
  .jvmPlatform(scalaVersions)

lazy val zstd = projectMatrix
  .in(file("zstd"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-zstd",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "com.github.luben" % "zstd-jni" % V.zstdJni
    )
  )
  .jvmPlatform(scalaVersions)

lazy val bzip2 = projectMatrix
  .in(file("bzip2"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-bzip2",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.apache.commons" % "commons-compress" % V.commonsCompress
    )
  )
  .jvmPlatform(scalaVersions)

lazy val brotli = projectMatrix
  .in(file("brotli"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-brotli",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.brotli" % "dec" % V.brotli
    )
  )
  .jvmPlatform(scalaVersions)

lazy val brotli4j = projectMatrix
  .in(file("brotli4j"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-brotli4j",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "com.aayushatharva.brotli4j" % "brotli4j" % V.brotli4j
    )
  )
  .jvmPlatform(scalaVersions)

lazy val lz4 = projectMatrix
  .in(file("lz4"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-lz4",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.lz4" % "lz4-java" % V.lz4
    )
  )
  .jvmPlatform(scalaVersions)

lazy val snappy = projectMatrix
  .in(file("snappy"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-snappy",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.xerial.snappy" % "snappy-java" % V.snappy
    )
  )
  .jvmPlatform(scalaVersions)
