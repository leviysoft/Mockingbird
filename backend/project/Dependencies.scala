import sbt._

object Dependencies {
  val tapirBase = Seq("tapir-core", "tapir-enumeratum", "tapir-refined")
    .map("com.softwaremill.sttp.tapir" %% _ % Versions.tapir)

  val tapir = Seq(
    "tapir-vertx-server-zio",
    "tapir-json-circe",
    "tapir-swagger-ui-bundle"
  ).map("com.softwaremill.sttp.tapir" %% _ % Versions.tapir)

  val tofu = Seq(
    "tofu-logging",
    "tofu-logging-structured",
    "tofu-logging-derivation"
  ).map("tf.tofu" %% _ % "0.13.6")

  val alleycats = Seq(
    "alleycats-core"
  ).map("org.typelevel" %% _ % Versions.cats)

  val cats = Seq(
    "cats-core",
    "cats-kernel"
  ).map("org.typelevel" %% _ % Versions.cats)

  val catsTagless = Seq("org.typelevel" %% "cats-tagless-core" % "0.16.2")

  val zio = Seq(
    "dev.zio" %% "zio"                 % Versions.zio,
    "dev.zio" %% "zio-managed"         % Versions.zio,
    "dev.zio" %% "zio-interop-cats"    % "23.1.0.1",
    //"dev.zio" %% "zio-interop-twitter" % "21.2.0.2.2",
    "dev.zio" %% "zio-test"            % Versions.zio % Test,
    "dev.zio" %% "zio-test-sbt"        % Versions.zio % Test
  )

  val json = Seq(
    "io.circe" %% "circe-core"                   % "0.14.6",
    "io.circe" %% "circe-generic"                % "0.14.6",
    "io.circe" %% "circe-parser"                 % "0.14.6",
    "io.circe" %% "circe-literal"                % "0.14.6",
    "io.circe" %% "circe-jawn"                   % "0.14.6",
    //"io.circe" %% "circe-derivation"             % "0.13.0-M5",
    //"io.circe" %% "circe-derivation-annotations" % "0.13.0-M5",
    "io.circe" %% "circe-refined"                % "0.14.6"
  )

  val mouse = Seq("org.typelevel" %% "mouse" % "1.0.11")

  val enumeratum = Seq(
    "com.beachape" %% "enumeratum"       % "1.7.5",
    "com.beachape" %% "enumeratum-circe" % "1.7.5"
  )

  val scalatestMain = Seq(
    "org.scalatest"    %% "scalatest"      % "3.2.19",
    "com.ironcorelabs" %% "cats-scalatest" % "4.0.0",
  )

  val scalatest = scalatestMain.map(_ % Test)

  val scalacheck = Seq(
    "org.scalatestplus" %% "scalacheck-1-15" % "3.2.11.0" % Test,
    "org.scalacheck"    %% "scalacheck"      % "1.15.4"  % Test
  )

  val scalamock = Seq(
    "org.scalamock" %% "scalamock" % "6.1.1" % Test
  )

  lazy val testContainers = Seq(
    "testcontainers-scala-scalatest",
    "testcontainers-scala-mongodb",
  ).map("com.dimafeng" %% _ % "0.41.0" % Test)

  lazy val refined = Seq(
    "eu.timepit" %% "refined" % "0.11.3"
  )

  lazy val protobuf = Seq(
    "io.grpc"               % "grpc-netty"           % "1.61.1",
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "com.google.protobuf"   % "protobuf-java"        % "3.25.2",
    "com.google.protobuf"   % "protobuf-java-util"   % "3.25.2"
  )

  lazy val metrics: Seq[ModuleID] = Seq(
    "io.micrometer"       % "micrometer-core"                % Versions.micrometer,
    "io.micrometer"       % "micrometer-registry-prometheus" % Versions.micrometer,
    "io.github.mweirauch" % "micrometer-jvm-extras"          % "0.2.2"
  )

  lazy val glass = Seq(
    "glass-core",
    "glass-macro",
  ).map("tf.tofu" %% _ % Versions.glass)

  lazy val oolong = Seq(
    "oolong-core",
    "oolong-bson",
    "oolong-bson-refined",
    "oolong-mongo",
  ).map("io.github.leviysoft" %% _ % "0.4.4")

  lazy val logback = Seq(
    "logback-core",
    "logback-classic"
  ).map("ch.qos.logback" % _ % "1.3.14")
}
