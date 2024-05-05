import ProjectKeys._
import ch.epfl.scala.sbtmissinglink.MissingLinkPlugin.missinglinkConflictsTag
import sbt.Keys.concurrentRestrictions

ThisBuild / scalaVersion := "2.13.14"

ThisBuild / concurrentRestrictions += Tags.limit(missinglinkConflictsTag, 1)

ThisBuild / evictionErrorLevel := Level.Debug

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

val utils = (project in file("utils"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Dependencies.cats ++ Dependencies.zio ++ Dependencies.scalatest ++ Dependencies.metrics
  )

val circeUtils = (project in file("circe-utils"))
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Dependencies.json ++ Dependencies.zio ++ Dependencies.scalatest
  )

val dataAccess = (project in file("dataAccess"))
  .settings(Settings.common)
  .settings(
    scalacOptions += "-language:experimental.macros",
    libraryDependencies ++= Dependencies.alleycats ++ Dependencies.cats ++ Dependencies.zio ++ Dependencies.catsTagless ++ Dependencies.mouse ++ Seq(
      "com.beachape"                 %% "enumeratum"                      % "1.7.0",
      "org.mongodb.scala"            %% "mongo-scala-driver"              % Versions.mongoScalaDriver,
      "com.chuusai"                  %% "shapeless"                       % "2.3.3",
      "org.julienrf"                 %% "enum-labels"                     % "3.1",
      "tf.tofu"                      %% "derevo-core"                     % Versions.derevo,
      "com.softwaremill.magnolia1_2" %% "magnolia"                        % "1.1.8",
      "com.google.code.findbugs"      % "jsr305"                          % "3.0.2" % Optional
    ) ++ Dependencies.scalatest ++ Dependencies.scalacheck ++ Dependencies.json ++ Dependencies.refined,
    libraryDependencies ++= Dependencies.reflect(scalaVersion.value),
    publish := {}
  )

val mockingbird = (project in file("mockingbird"))
  .aggregate(utils, circeUtils, dataAccess)
  .dependsOn(utils, circeUtils, dataAccess)
  .settings(Settings.common)
  .settings(
    name := "mockingbird",
    libraryDependencies ++= Seq(
      Dependencies.cats,
      Dependencies.catsTagless,
      Dependencies.enumeratum,
      Dependencies.scalatest,
      Dependencies.tofu,
      Dependencies.zio,
      Dependencies.refined,
      Dependencies.protobuf,
      Dependencies.tapirBase,
      Dependencies.glass,
      Dependencies.logback
    ).flatten,
    libraryDependencies ++= Seq(
      "com.iheart"                    %% "ficus"               % "1.5.0",
      "io.circe"                      %% "circe-config"        % "0.8.0",
      "com.nrinaudo"                  %% "kantan.xpath"        % "0.5.2",
      "com.lihaoyi"                   %% "scalatags"           % "0.9.1",
      "tf.tofu"                       %% "derevo-circe"        % Versions.derevo,
      "org.webjars.npm"                % "swagger-ui-dist"     % "3.32.5",
      "eu.timepit"                    %% "fs2-cron-cron4s"     % "0.9.0",
      "com.softwaremill.sttp.client4" %% "zio"                 % Versions.sttp,
      "com.softwaremill.sttp.client4" %% "circe"               % Versions.sttp,
      "org.apache.tika"                % "tika-core"           % "2.1.0",
      "io.scalaland"                  %% "chimney"             % "0.8.5",
      "com.ironcorelabs"              %% "cats-scalatest"      % "3.0.8" % Test,
      "com.google.code.findbugs"       % "jsr305"              % "3.0.2" % Optional,
      "com.github.dwickern"           %% "scala-nameof"        % "4.0.0" % Provided,
      "com.github.os72"                % "protobuf-dynamic"    % "1.0.1",
      "com.github.geirolz"            %% "advxml-core"         % "2.5.1",
      "com.github.geirolz"            %% "advxml-xpath"        % "2.5.1",
      "io.estatico"                   %% "newtype"             % "0.4.4",
      "org.mozilla"                    % "rhino"               % "1.7.14",
      "org.graalvm.polyglot"           % "js"                  % "23.1.+",
      "org.slf4j"                      % "slf4j-api"           % "1.7.30" % Provided
    ),
    Compile / unmanagedResourceDirectories += file("../frontend/dist")
  )
  .settings(
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true)          -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb"
    )
  )

/*
   A separate subproject was created to avoid disabling coursier throughout the entire project.
   See https://github.com/coursier/coursier/issues/2016.
   Since netty-transport-epoll is no longer used, the code from here can be moved to mockingbird.
 */
lazy val `mockingbird-api` = (project in file("mockingbird-api"))
  .enablePlugins(BuildInfoPlugin)
  .aggregate(mockingbird)
  .dependsOn(mockingbird)
  .settings(Settings.common)
  .configure(Settings.docker("ru.tinkoff.tcb.mockingbird.Mockingbird", "mockingbird", 8228 :: 9000 :: Nil))
  .settings(
    name := "mockingbird-api",
    libraryDependencies ++= Seq(
      Dependencies.tapir
    ).flatten,
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "ru.tinkoff.tcb.mockingbird.build",
    Compile / packageDoc / mappings := Seq(),
    run / fork := true,
    run / javaOptions += "-Dconfig.resource=local.conf",
    Compile / unmanagedResourceDirectories += file("../frontend/dist")
  )
  .settings(
    addCommandAlias(
      "fixCheck",
      "scalafixAll --check; scalafmtCheck"
    ),
    addCommandAlias(
      "lintAll",
      "scalafixAll; scalafmtAll"
    )
  )

lazy val `mockingbird-native` = (project in file("mockingbird-native"))
  .dependsOn(`mockingbird-api`)
  .enablePlugins(
    GraalVMNativeImagePlugin,
    NativeImagePlugin
  )
  .settings(libraryDependencies -= "org.scalameta" % "svm-subs" % "101.0.0")
  .aggregate(mockingbird)
  .dependsOn(mockingbird)
  .settings(Settings.common)
  .configure(Settings.dockerNative("mockingbird-native", 8228 :: 9000 :: Nil))
  .settings(
    name := "mockingbird-native",
    Compile / run / mainClass := Some("ru.tinkoff.tcb.mockingbird.Mockingbird"),
    Compile / packageDoc / mappings := Seq(),
    GraalVMNativeImage / mainClass := Some("ru.tinkoff.tcb.mockingbird.Mockingbird"),
    GraalVMNativeImage / graalVMNativeImageOptions ++= Seq(
      "-H:+StaticExecutableWithDynamicLibC",
      "--gc=G1"
    ).filter(_ => dockerize.value),
    nativeImageInstalled := true,
    nativeImageAgentMerge := true,
    run / javaOptions += "-Dconfig.resource=local.conf",
  )
  .settings(
    addCommandAlias(
      "fixCheck",
      "scalafixAll --check; scalafmtCheck"
    ),
    addCommandAlias(
      "lintAll",
      "scalafixAll; scalafmtAll"
    )
  )

val edsl = (project in file("edsl"))
  .dependsOn(utils, circeUtils)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.cats,
      Dependencies.tofu,
      Dependencies.mouse,
      Dependencies.enumeratum,
      Dependencies.scalatestMain,
      Dependencies.scalamock,
      Dependencies.refined,
    ).flatten,
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %% "circe"               % Versions.sttp,
      "pl.muninn"                     %% "scala-md-tag"        % "0.2.3",
    ),
  )
  .settings(
    Compile / doc / sources := (file("edsl/src/main") ** "*.scala").get,
    Compile / doc / scalacOptions ++= Seq("-groups", "-skip-packages", "sttp")
  )

val examples = (project in file("examples"))
  .dependsOn(edsl)
  .settings(Settings.common)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.scalatest,
      Dependencies.scalamock,
      Dependencies.testContainers,
      Dependencies.logback
    ).flatten,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-cli" % "0.5.0",
      "org.typelevel" %% "cats-effect" % "3.5.4"
    ),
    Test / parallelExecution := false,
  )
  .settings(
    addCommandAlias(
      "fixCheck",
      "scalafixAll --check; scalafmtCheck"
    ),
    addCommandAlias(
      "lintAll",
      "scalafixAll; scalafmtAll"
    )
  )

val root = (project in file("."))
  .disablePlugins(ContribWarts)
  .aggregate(
    utils,
    circeUtils,
    dataAccess,
    mockingbird,
    `mockingbird-api`,
    `mockingbird-native`,
    `edsl`
  )
  .settings(
    run / aggregate := false,
  )
