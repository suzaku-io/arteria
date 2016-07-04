import sbt._
import Keys._
import com.typesafe.sbt.pgp.PgpKeys._

crossScalaVersions := Seq("2.11.8")

val commonSettings = Seq(
  organization := "me.chrons",
  version := Version.library,
  scalaVersion := "2.11.8",
  scalacOptions := Seq(
    "-deprecation",
    "-encoding", "UTF-8",
    "-feature",
    "-unchecked",
    "-Xfatal-warnings",
    "-Xlint",
    "-Yinline-warnings",
    "-Yno-adapted-args",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture"),
  scalacOptions in Compile -= "-Ywarn-value-discard",
  scalacOptions in(Compile, doc) -= "-Xfatal-warnings",
  libraryDependencies ++= Seq(
    "org.scalatest" %%% "scalatest" % "3.0.0-RC3" % "test"
  )
)

val publishSettings = Seq(
  scmInfo := Some(ScmInfo(
    url("https://github.com/ochrons/arteria"),
    "scm:git:git@github.com:ochrons/arteria.git",
    Some("scm:git:git@github.com:ochrons/arteria.git"))),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomExtra :=
    <url>https://github.com/ochrons/arteria</url>
      <licenses>
        <license>
          <name>MIT license</name>
          <url>http://www.opensource.org/licenses/mit-license.php</url>
        </license>
      </licenses>
      <developers>
        <developer>
          <id>ochrons</id>
          <name>Otto Chrons</name>
          <url>https://github.com/ochrons</url>
        </developer>
      </developers>,
  pomIncludeRepository := { _ => false },
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
    else Seq({
      val a = baseDirectory.value.toURI.toString.replaceFirst("[^/]+/?$", "")
      val g = "https://raw.githubusercontent.com/ochrons/arteria"
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
    packagedArtifacts := Map.empty)

lazy val arteriaCore = crossProject.in(file("arteria-core"))
  .settings(commonSettings: _*)
  .settings(publishSettings: _*)
  .settings(
    name := "arteria-core",
    libraryDependencies ++= Seq(
      "me.chrons" %%% "boopickle" % "1.2.4"
    )
  )
  .jsSettings(
    scalacOptions ++= sourceMapSetting.value,
    scalaJSUseRhino in Global := false
  )
  .jvmSettings()

lazy val arteriaCoreJS = arteriaCore.js

lazy val arteriaCoreJVM = arteriaCore.jvm

lazy val root = preventPublication(project.in(file(".")))
  .settings()
  .aggregate(arteriaCoreJS, arteriaCoreJVM)
