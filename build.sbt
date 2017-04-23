import sbt._
import Keys._
import com.typesafe.sbt.pgp.PgpKeys._

crossScalaVersions := Seq("2.11.11", "2.12.2")

val commonSettings = Seq(
  organization := "io.suzaku",
  version := Version.library,
  scalaVersion := "2.12.2",
  scalacOptions := Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture"
  ),
  scalacOptions in Compile -= "-Ywarn-value-discard",
  scalacOptions in (Compile, doc) -= "-Xfatal-warnings",
  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.0.1" % "test"
  ),
  resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
)

val publishSettings = Seq(
  scmInfo := Some(
    ScmInfo(url("https://github.com/suzaku-io/arteria"),
            "scm:git:git@github.com:suzaku-io/arteria.git",
            Some("scm:git:git@github.com:suzaku-io/arteria.git"))),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomExtra :=
    <url>https://github.com/suzaku-io/arteria</url>
      <licenses>
        <license>
          <name>Apache 2.0 license</name>
          <url>http://www.opensource.org/licenses/Apache-2.0</url>
        </license>
      </licenses>
      <developers>
        <developer>
          <id>ochrons</id>
          <name>Otto Chrons</name>
          <url>https://github.com/ochrons</url>
        </developer>
      </developers>,
  pomIncludeRepository := { _ =>
    false
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  }
)

val sourceMapSetting =
  Def.setting(
    if (isSnapshot.value) Seq.empty
    else
      Seq({
        val a = baseDirectory.value.toURI.toString.replaceFirst("[^/]+/?$", "")
        val g = "https://raw.githubusercontent.com/suzaku-io/arteria"
        s"-P:scalajs:mapSourceURI:$a->$g/v${version.value}/${name.value}/"
      })
  )

def preventPublication(p: Project) =
  p.settings(
    publish := (),
    publishLocal := (),
    publishSigned := (),
    publishLocalSigned := (),
    publishArtifact := false,
    publishTo := Some(Resolver.file("Unused transient repository", target.value / "fakepublish")),
    packagedArtifacts := Map.empty
  )

lazy val arteriaCore = crossProject
  .in(file("arteria-core"))
  .settings(commonSettings: _*)
  .settings(publishSettings: _*)
  .settings(
    name := "arteria-core",
    libraryDependencies ++= Seq(
      "io.suzaku" %%% "boopickle" % "1.2.6"
    )
  )
  .jsSettings(
    scalacOptions ++= sourceMapSetting.value
  )
  .jvmSettings()

lazy val arteriaCoreJS = arteriaCore.js

lazy val arteriaCoreJVM = arteriaCore.jvm

lazy val root = preventPublication(project.in(file(".")))
  .settings(commonSettings: _*)
  .aggregate(arteriaCoreJS, arteriaCoreJVM)
