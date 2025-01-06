package ru.tinkoff.tcb.mockingbird.config

import scala.concurrent.duration.FiniteDuration

import com.github.dwickern.macros.NameOf.*
import com.typesafe.config.Config
import enumeratum.*
import pureconfig.*
import pureconfig.error.CannotConvert
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.*

final case class JsSandboxConfig(allowedClasses: Set[String] = Set())

object JsSandboxConfig {
  given ProductHint[JsSandboxConfig] = ProductHint[JsSandboxConfig](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[JsSandboxConfig] = deriveReader
}

final case class ServerConfig(
    interface: String,
    port: Int,
    allowedOrigins: Seq[String],
    healthCheckRoute: Option[String],
    sandbox: JsSandboxConfig = JsSandboxConfig(),
    vertx: Config
)

object ServerConfig {
  given ProductHint[ServerConfig] = ProductHint[ServerConfig](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[ServerConfig] = deriveReader
}

final case class SecurityConfig(secret: String) derives ConfigReader

final case class ProxyServerAuth(user: String, password: String) derives ConfigReader

sealed trait ProxyServerType extends EnumEntry
object ProxyServerType extends Enum[ProxyServerType] with PureconfigEnum[ProxyServerType] {
  case object Http extends ProxyServerType
  case object Socks extends ProxyServerType
  val values = findValues
}

final case class ProxyServerConfig(
    `type`: ProxyServerType,
    host: String,
    port: Int,
    nonProxy: Seq[String] = Seq(),
    onlyProxy: Seq[String] = Seq(),
    auth: Option[ProxyServerAuth]
)

object ProxyServerConfig {
  given ProductHint[ProxyServerConfig] = ProductHint[ProxyServerConfig](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[ProxyServerConfig] = deriveReader
}

sealed trait HttpVersion extends EnumEntry
object HttpVersion extends Enum[HttpVersion] {
  val values = findValues
  case object HTTP_1_1 extends HttpVersion
  case object HTTP_2 extends HttpVersion

  implicit val configReader: ConfigReader[HttpVersion] =
    ConfigReader.fromString { s =>
      namesToValuesMap.get(s) match {
        case Some(v) => Right(v)
        case None =>
          Left(CannotConvert(s, nameOfType[HttpVersion], s"Cannot get instance of enum HttpVersion from the value of $s"))
      }
    }
}

final case class ProxyConfig(
    excludedRequestHeaders: Seq[String],
    excludedResponseHeaders: Set[String],
    proxyServer: Option[ProxyServerConfig],
    insecureHosts: Seq[String],
    logOutgoingRequests: Boolean,
    disableAutoDecompressForRaw: Boolean,
    httpVersion: HttpVersion
)

object ProxyConfig {
  given ProductHint[ProxyConfig] = ProductHint[ProxyConfig](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[ProxyConfig] = deriveReader
}

final case class EventConfig(fetchInterval: FiniteDuration, reloadInterval: FiniteDuration)

object EventConfig {
  given ProductHint[EventConfig] = ProductHint[EventConfig](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[EventConfig] = deriveReader
}

final case class MongoConfig(uri: String, collections: MongoCollections) derives ConfigReader

final case class MongoCollections(
    stub: String,
    state: String,
    scenario: String,
    service: String,
    label: String,
    grpcStub: String,
    grpcMethodDescription: String,
    source: String,
    destination: String
)

object MongoCollections {
  given ProductHint[MongoCollections] = ProductHint[MongoCollections](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[MongoCollections] = deriveReader
}

final case class TracingConfig(
    required: List[String] = List.empty,
    incomingHeaders: Map[String, String] = Map.empty,
    outcomingHeaders: Map[String, String] = Map.empty,
)

object TracingConfig {
  given ProductHint[TracingConfig] = ProductHint[TracingConfig](ConfigFieldMapping(CamelCase, CamelCase))
  given ConfigReader[TracingConfig] = deriveReader
}

final case class MockingbirdConfiguration(
    server: ServerConfig,
    security: SecurityConfig,
    mongo: MongoConfig,
    proxy: ProxyConfig,
    event: EventConfig,
    tracing: TracingConfig,
) derives ConfigReader

object MockingbirdConfiguration {
  // implicit private def hint[T]: ProductHint[T] =
  // ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  def load(): MockingbirdConfiguration =
    load(ConfigSource.default.at("ru.tinkoff.tcb"))

  def load(config: ConfigSource): MockingbirdConfiguration =
    MockingbirdConfiguration(
      config.at("server").loadOrThrow[ServerConfig],
      config.at("security").loadOrThrow[SecurityConfig],
      config.at("db.mongo").loadOrThrow[MongoConfig],
      config.at("proxy").loadOrThrow[ProxyConfig],
      config.at("event").loadOrThrow[EventConfig],
      config.at("tracing").loadOrThrow[TracingConfig],
    )

  private lazy val conf = load()

  val server: ULayer[ServerConfig]     = ZLayer.succeed(conf.server)
  val security: ULayer[SecurityConfig] = ZLayer.succeed(conf.security)
  val mongo: ULayer[MongoConfig]       = ZLayer.succeed(conf.mongo)
  val proxy: ULayer[ProxyConfig]       = ZLayer.succeed(conf.proxy)
  val event: ULayer[EventConfig]       = ZLayer.succeed(conf.event)
  val tracing: ULayer[TracingConfig]   = ZLayer.succeed(conf.tracing)
}
