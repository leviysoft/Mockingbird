package ru.tinkoff.tcb.mockingbird.model

import scala.xml.Node

import com.github.dwickern.macros.NameOf.nameOfType
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.derivation.Configuration as CirceConfig
import io.circe.derivation.ConfiguredDecoder
import io.circe.derivation.ConfiguredEncoder
import io.circe.refined.*
import neotype.*
import oolong.bson.*
import oolong.bson.annotation.BsonDiscriminator
import oolong.bson.given
import oolong.bson.refined.given
import sttp.tapir.Schema
import sttp.tapir.codec.refined.*
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.xml.XMLString

@BsonDiscriminator("mode")
sealed trait CallbackRequest derives BsonDecoder, BsonEncoder, ConfiguredDecoder, ConfiguredEncoder, Schema {
  def url: NonEmptyString
  def method: HttpMethod
  def headers: Map[String, String]
}

object CallbackRequest {
  val modes: Map[String, String] = Map(
    nameOfType[CallbackRequestWithoutBody] -> "no_body",
    nameOfType[RawCallbackRequest]         -> "raw",
    nameOfType[JsonCallbackRequest]        -> "json",
    nameOfType[XMLCallbackRequest]         -> "xml"
  ).withDefault(identity)

  given TapirConfig = TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")
}

final case class CallbackRequestWithoutBody(
    url: NonEmptyString,
    method: HttpMethod,
    headers: Map[String, String]
) extends CallbackRequest

final case class RawCallbackRequest(
    url: NonEmptyString,
    method: HttpMethod,
    headers: Map[String, String],
    body: String
) extends CallbackRequest

final case class JsonCallbackRequest(
    url: NonEmptyString,
    method: HttpMethod,
    headers: Map[String, String],
    body: Json
) extends CallbackRequest

final case class XMLCallbackRequest(
    url: NonEmptyString,
    method: HttpMethod,
    headers: Map[String, String],
    body: XMLString.Type
) extends CallbackRequest {
  lazy val node: Node = body.unwrap
}
