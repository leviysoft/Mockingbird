import scalafix.sbt.ScalafixPlugin.autoImport._

import ProjectKeys._
import ch.epfl.scala.sbtmissinglink.MissingLinkPlugin.autoImport._
import com.github.sbt.git.SbtGit.git
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.docker._
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
import coursierapi.{MavenRepository => CoursierMvnRepo}
import wartremover.WartRemover.autoImport._
import wartremover.contrib.ContribWart
import sbt.Keys._
import sbt._

object Settings {
  private val env = sys.env ++ sys.props

  val ciEnabled: Boolean                    = env.get("CI").contains("true")
  val ciDockerTag: Option[String]           = env.get("DOCKER_TAG")
  val ciDockerRegistry: Option[String]      = env.get("DOCKER_REGISTRY")
  val ciDockerRegistryProxy: Option[String] = env.get("DOCKER_REGISTRY_PROXY")

  def prelude(predicate: String => Boolean = _ => true) =
    "-Yimports:" + Seq("java.lang", "scala", "scala.Predef", "scala.util.chaining", "cats", "cats.syntax.all", "zio")
      .filter(predicate)
      .mkString(",")

  val common = Seq(
    organization := "ru.tinkoff",
    scalaVersion := "3.5.2",
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    Compile / doc / sources := Seq.empty,
    scalacOptions ++= Seq(
      "-encoding",
      "utf8",
      "-deprecation",
      "-explain-cyclic",
      "-explaintypes",
      "-feature",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-release:11",
      "-source:3.4",
      "-unchecked",
      "-Xmax-inlines", "64",
      "-Ybackend-parallelism",
      java.lang.Runtime.getRuntime.availableProcessors().toString,
      "-Yretain-trees", //for accessing default values by magnolia
      prelude(), // standart imports + zio
      // warning settings
      //"-Wconf:any:wv", // shows warning categories for nowarn (https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html)
      "-Wunused:imports",
      "-Wunused:privates",
      "-Wvalue-discard"
    ),
    wartremoverDependencies ~= (_.filterNot(_.name == "wartremover-contrib")),
    wartremoverDependencies += "org.wartremover" % "wartremover-contrib_3" % ContribWart.ContribVersion,
    wartremoverErrors ++= Seq(
      Wart.ExplicitImplicitTypes,
      Wart.FinalCaseClass,
      //Wart.IterableOps,
      Wart.LeakingSealed,
      ContribWart.DiscardedFuture,
      //ContribWart.MissingOverride
    ),
    missinglinkExcludedDependencies ++= Seq(
      moduleFilter(organization = "ch.qos.logback", name = "logback-core" | "logback-classic"),
      // missinglink does not correctly handle optional scope
      moduleFilter(organization = "org.mongodb", name = "mongodb-driver-core" | "mongodb-driver-reactivestreams"),
      moduleFilter(organization = "io.netty"),
      // something awful here, artifact uses classes from dependencies, which are absent in pom.xml
      moduleFilter(organization = "io.projectreactor", name = "reactor-core")
    ),
    missinglinkIgnoreDestinationPackages ++= Seq(
      // optional dependency in bson
      IgnoredPackage("org.slf4j"),
      IgnoredPackage("ch.qos.logback"),
      // optional dependency in vertx-core
      IgnoredPackage("com.fasterxml.jackson.databind"),
      IgnoredPackage("io.vertx.core.json.jackson"),
      IgnoredPackage("io.netty.handler.codec.haproxy"),
      IgnoredPackage("io.netty.channel.kqueue"),
      IgnoredPackage("io.netty.channel.epoll")
    ),
    dockerize := ciEnabled,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true
  )

  def docker(
      entryPoint: String,
      appName: String,
      ports: List[Int],
      user: String = "mockingbird",
      userId: Option[String] = Some("2048")
  ): Project => Project = { prj: Project =>
    import com.typesafe.sbt.packager.archetypes.jar.LauncherJarPlugin
    import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}
    import com.typesafe.sbt.packager.docker.DockerPlugin
    import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{dockerPermissionStrategy, Docker}
    import com.typesafe.sbt.packager.Keys._
    import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport.{daemonUser, defaultLinuxInstallLocation}
    import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
    import com.github.sbt.git.GitVersioning
    import com.github.sbt.git.SbtGit.git

    prj
      .enablePlugins(GitVersioning, DockerPlugin, LauncherJarPlugin)
      .settings(
        run / mainClass := Some(entryPoint),
      )
      .settings(
        Docker / daemonUser := user,
        Docker / daemonUserUid := userId,
        Docker / daemonGroup := userId.fold((Docker / daemonGroup).value)(_ => user),
        Docker / daemonGroupGid := userId,
        Docker / defaultLinuxInstallLocation := s"/opt/$appName",
        Docker / packageName := appName,
        Docker / version := ciDockerTag.getOrElse {
          val branch = git.gitCurrentBranch.value.split('/').last
          val commit = git.gitHeadCommit.value.getOrElse("no-commit")
          s"$branch-$commit"
        },
        dockerExposedPorts := ports,
        dockerRepository := ciDockerRegistry.map(_ + "/tcb"),
        dockerBaseImage := "eclipse-temurin:21-jre-jammy",
        dockerCommands := dockerCommands.value.patch(
          8,
          Seq(
            Cmd("RUN", "apt-get", "update"),
            Cmd("RUN", "apt-get", "install", "-y", "unzip"),
            Cmd("RUN", "unzip", "/opt/mockingbird/protoc-25.2-linux-x86_64.zip -d", "/opt/protoc"),
            Cmd("RUN", "rm", "/opt/mockingbird/protoc-25.2-linux-x86_64.zip"),
            Cmd("ENV", "PATH=\"$PATH:/opt/protoc/bin:${PATH}\"")
          ),
          0
        ),
        dockerPermissionStrategy := DockerPermissionStrategy.CopyChown,
        Universal / javaOptions := Seq(
          "-XX:+UseG1GC",
          "-XX:G1HeapRegionSize=2M",
          "-XX:G1ReservePercent=20"
        ).map("-J" + _),
      )
  }

  def dockerNative(
      appName: String,
      ports: List[Int],
      user: String = "mockingbird",
      userId: Option[String] = Some("2048"),
      imageName: String = "ubuntu:22.04"
  ): Project => Project = { prj: Project =>
    import com.typesafe.sbt.packager.docker.DockerChmodType
    import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}
    import com.typesafe.sbt.packager.docker.DockerPlugin
    import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{dockerPermissionStrategy, Docker, dockerChmodType, dockerAdditionalPermissions}
    import com.typesafe.sbt.packager.Keys._
    import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport.{daemonUser, defaultLinuxInstallLocation}
    import com.typesafe.sbt.packager.graalvmnativeimage.GraalVMNativeImagePlugin.autoImport._
    import com.github.sbt.git.GitVersioning
    import com.github.sbt.git.SbtGit.git

    val dockerBasePath = "/opt/docker/bin"

    prj
      .enablePlugins(GitVersioning, DockerPlugin)
      .settings(
        Docker / daemonUser := user,
        Docker / daemonUserUid := userId,
        Docker / daemonGroup := userId.fold((Docker / daemonGroup).value)(_ => user),
        Docker / daemonGroupGid := userId,
        Docker / defaultLinuxInstallLocation := s"/opt/$appName",
        Docker / packageName := appName,
        Docker / version := ciDockerTag.getOrElse {
          val branch = git.gitCurrentBranch.value.split('/').last
          val commit = git.gitHeadCommit.value.getOrElse("no-commit")
          s"$branch-$commit"
        },
        Docker / mappings := (Docker / mappings).value.filter { case (file, name) =>
          !name.endsWith(".jar")
        },
        Docker / mappings ++= Seq(
          ((GraalVMNativeImage / target).value / name.value) -> ((Docker / defaultLinuxInstallLocation).value + "/" + name.value)
        ),
        dockerExposedPorts := ports,
        dockerRepository := ciDockerRegistry.map(_ + "/tcb"),
        dockerBaseImage := Seq(ciDockerRegistryProxy, Some(imageName)).flatten.mkString("/"),
        dockerCommands := dockerCommands.value.patch(
          5,
          Seq(
            Cmd("RUN", "apt-get", "update"),
            Cmd("RUN", "apt-get", "install", "-y", "unzip"),
            Cmd("RUN", "unzip", "/opt/mockingbird-native/protoc-25.2-linux-x86_64.zip -d", "/opt/protoc"),
            Cmd("RUN", "rm", "/opt/mockingbird-native/protoc-25.2-linux-x86_64.zip"),
            Cmd("ENV", "PATH=\"$PATH:/opt/protoc/bin:${PATH}\"")
          ),
          0
        ),
        dockerCommands += Cmd("ENV", "MALLOC_ARENA_MAX=1"),
        dockerPermissionStrategy := DockerPermissionStrategy.CopyChown,
        dockerEntrypoint := Seq(((Docker / defaultLinuxInstallLocation).value + "/" + name.value)),
        dockerChmodType := DockerChmodType.Custom("ugo=rwX"),
        dockerAdditionalPermissions += (DockerChmodType.Custom(
          "ugo=rwx"
        ), defaultLinuxInstallLocation.value),
      )
  }
}
