lazy val scalaVersions = Seq("3.3.3", "2.13.13", "2.12.18")

ThisBuild / scalaVersion := scalaVersions.head
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / organization := "de.lhns"
name := (core.projectRefs.head / name).value

val V = new {
  val betterMonadicFor = "0.3.1"
  val brotli = "0.1.2"
  val catsEffect = "3.5.3"
  val commonsCompress = "1.25.0"
  val fs2 = "3.10.1"
  val logbackClassic = "1.5.3"
  val munit = "0.7.29"
  val munitTaglessFinal = "0.2.1"
  val zstdJni = "1.5.5-11"
}

lazy val commonSettings: SettingsDefinition = Def.settings(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
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
    Developer(id = "lhns", name = "Pierre Kisters", email = "pierrekisters@gmail.com", url = url("https://github.com/lhns/"))
  ),

  libraryDependencies ++= Seq(
    "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
    "de.lhns" %%% "munit-tagless-final" % V.munitTaglessFinal % Test,
    "org.scalameta" %%% "munit" % V.munit % Test,
  ),

  testFrameworks += new TestFramework("munit.Framework"),

  libraryDependencies ++= virtualAxes.?.value.getOrElse(Seq.empty).collectFirst {
    case VirtualAxis.ScalaVersionAxis(version, _) if version.startsWith("2.") =>
      compilerPlugin("com.olegpy" %% "better-monadic-for" % V.betterMonadicFor)
  },

  Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),

  Compile / doc / sources := Seq.empty,

  publishMavenStyle := true,

  publishTo := sonatypePublishToBundle.value,

  sonatypeCredentialHost := "s01.oss.sonatype.org",

  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    sonatypeCredentialHost.value,
    username,
    password
  )).toList,
)

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
    .aggregate(tar.projectRefs: _*)
    .aggregate(zstd.projectRefs: _*)
    .aggregate(bzip2.projectRefs: _*)
    .aggregate(brotli.projectRefs: _*)

lazy val core = projectMatrix.in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "fs2-compress",

    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-core" % V.fs2,
      "org.typelevel" %%% "cats-effect" % V.catsEffect,
    ),
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(scalaVersions)

lazy val gzip = projectMatrix.in(file("gzip"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-gzip",

    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-io" % V.fs2,
    ),
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(scalaVersions)

lazy val zip = projectMatrix.in(file("zip"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-zip",

    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-io" % V.fs2,
    ),
  )
  .jvmPlatform(scalaVersions)

lazy val tar = projectMatrix.in(file("tar"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-tar",

    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-io" % V.fs2,
      "org.apache.commons" % "commons-compress" % V.commonsCompress,
    ),
  )
  .jvmPlatform(scalaVersions)

lazy val zstd = projectMatrix.in(file("zstd"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-zstd",

    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "com.github.luben" % "zstd-jni" % V.zstdJni,
    ),
  )
  .jvmPlatform(scalaVersions)

lazy val bzip2 = projectMatrix.in(file("bzip2"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-bzip2",

    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.apache.commons" % "commons-compress" % V.commonsCompress,
    ),
  )
  .jvmPlatform(scalaVersions)

lazy val brotli = projectMatrix.in(file("brotli"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "fs2-compress-brotli",

    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % V.fs2,
      "org.brotli" % "dec" % V.brotli,
    ),
  )
  .jvmPlatform(scalaVersions)
