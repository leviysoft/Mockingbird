package ru.tinkoff.tcb.mockingbird.model

import scala.concurrent.duration.FiniteDuration
import scala.xml.Node

import com.github.dwickern.macros.NameOf.*
import derevo.circe.decoder
import derevo.circe.encoder
import derevo.derive
import glass.Contains
import glass.Property
import glass.Subset
import glass.macros.GenContains
import glass.macros.GenSubset
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.refined.*
import sttp.tapir.codec.refined.*
import sttp.tapir.derevo.schema
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.bson.annotation.BsonDiscriminator
import ru.tinkoff.tcb.bson.derivation.bsonDecoder
import ru.tinkoff.tcb.bson.derivation.bsonEncoder
import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.transformation.json.JsonTransformations
import ru.tinkoff.tcb.utils.transformation.xml.XmlTransformation
import ru.tinkoff.tcb.utils.xml.XMLString
import ru.tinkoff.tcb.xpath.SXpath

@derive(
  bsonDecoder,
  bsonEncoder,
  decoder(HttpStubResponse.modes, true, Some("mode")),
  encoder(HttpStubResponse.modes, Some("mode")),
  schema
)
@BsonDiscriminator("mode")
sealed trait HttpStubResponse {
  def delay: Option[FiniteDuration]
  def isTemplate: Boolean
}

object HttpStubResponse {
  val modes: Map[String, String] = Map(
    nameOfType[EmptyResponse]     -> "no_body",
    nameOfType[RawResponse]       -> "raw",
    nameOfType[JsonResponse]      -> "json",
    nameOfType[XmlResponse]       -> "xml",
    nameOfType[BinaryResponse]    -> "binary",
    nameOfType[ProxyResponse]     -> "proxy",
    nameOfType[JsonProxyResponse] -> "json-proxy",
    nameOfType[XmlProxyResponse]  -> "xml-proxy"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  val headers: Property[HttpStubResponse, Map[String, String]] =
    Vector(
      EmptyResponse.prism >> EmptyResponse.headers,
      RawResponse.prism >> RawResponse.headers,
      JsonResponse.prism >> JsonResponse.headers,
      XmlResponse.prism >> XmlResponse.headers,
      BinaryResponse.prism >> BinaryResponse.headers
    ).reduce[Property[HttpStubResponse, Map[String, String]]](_ orElse _)

  val jsonBody: Property[HttpStubResponse, Json] = JsonResponse.prism >> JsonResponse.body

  val xmlBody: Property[HttpStubResponse, Node] = XmlResponse.prism >> XmlResponse.body
}

@derive(decoder, encoder)
final case class EmptyResponse(
    code: HttpStatusCode,
    headers: Map[String, String],
    delay: Option[FiniteDuration]
) extends HttpStubResponse {
  val isTemplate: Boolean = false
}

object EmptyResponse {
  val prism: Subset[HttpStubResponse, EmptyResponse] = GenSubset[HttpStubResponse, EmptyResponse]

  val headers: Contains[EmptyResponse, Map[String, String]] = GenContains[EmptyResponse](_.headers)
}

@derive(decoder, encoder)
final case class RawResponse(
    code: HttpStatusCode,
    headers: Map[String, String],
    body: String,
    delay: Option[FiniteDuration]
) extends HttpStubResponse {
  val isTemplate: Boolean = false
}

object RawResponse {
  val prism: Subset[HttpStubResponse, RawResponse] = GenSubset[HttpStubResponse, RawResponse]

  val headers: Contains[RawResponse, Map[String, String]] = GenContains[RawResponse](_.headers)
}

final case class JsonResponse(
    code: HttpStatusCode,
    headers: Map[String, String],
    body: Json,
    delay: Option[FiniteDuration],
    isTemplate: Boolean = true
) extends HttpStubResponse

object JsonResponse {
  val prism: Subset[HttpStubResponse, JsonResponse] = GenSubset[HttpStubResponse, JsonResponse]

  val body: Contains[JsonResponse, Json] = GenContains[JsonResponse](_.body)

  val headers: Contains[JsonResponse, Map[String, String]] = GenContains[JsonResponse](_.headers)

  implicit val jrEncoder: Encoder.AsObject[JsonResponse] =
    Encoder.forProduct4(
      nameOf[JsonResponse](_.code),
      nameOf[JsonResponse](_.headers),
      nameOf[JsonResponse](_.body),
      nameOf[JsonResponse](_.delay)
    )(jr => (jr.code, jr.headers, jr.body, jr.delay))

  implicit val jrDecoder: Decoder[JsonResponse] = Decoder.forProduct4(
    nameOf[JsonResponse](_.code),
    nameOf[JsonResponse](_.headers),
    nameOf[JsonResponse](_.body),
    nameOf[JsonResponse](_.delay)
  )((code, headers, bdy, delay) => JsonResponse(code, headers, bdy, delay, bdy.isTemplate))
}

final case class XmlResponse(
    code: HttpStatusCode,
    headers: Map[String, String],
    body: XMLString,
    delay: Option[FiniteDuration],
    isTemplate: Boolean = true
) extends HttpStubResponse {
  lazy val node: Node = body.toNode
}

object XmlResponse {
  val prism: Subset[HttpStubResponse, XmlResponse] = GenSubset[HttpStubResponse, XmlResponse]

  val body: Contains[XmlResponse, Node] = new Contains[XmlResponse, Node] {
    override def set(s: XmlResponse, b: Node): XmlResponse =
      s.copy(body = XMLString.fromNode(b))

    override def extract(s: XmlResponse): Node = s.node
  }

  val headers: Contains[XmlResponse, Map[String, String]] = GenContains[XmlResponse](_.headers)

  implicit val xrEncoder: Encoder.AsObject[XmlResponse] =
    Encoder.forProduct4(
      nameOf[XmlResponse](_.code),
      nameOf[XmlResponse](_.headers),
      nameOf[XmlResponse](_.body),
      nameOf[XmlResponse](_.delay)
    )(xr => (xr.code, xr.headers, xr.body, xr.delay))

  implicit val xrDecoder: Decoder[XmlResponse] =
    Decoder.forProduct4(
      nameOf[XmlResponse](_.code),
      nameOf[XmlResponse](_.headers),
      nameOf[XmlResponse](_.body),
      nameOf[XmlResponse](_.delay)
    )((code, headers, bdy, delay) => XmlResponse(code, headers, bdy, delay, bdy.toNode.isTemplate))
}

@derive(decoder, encoder)
final case class BinaryResponse(
    code: HttpStatusCode,
    headers: Map[String, String],
    body: ByteArray,
    delay: Option[FiniteDuration]
) extends HttpStubResponse {
  val isTemplate: Boolean = false
}

object BinaryResponse {
  val prism: Subset[HttpStubResponse, BinaryResponse] = GenSubset[HttpStubResponse, BinaryResponse]

  val headers: Contains[BinaryResponse, Map[String, String]] = GenContains[BinaryResponse](_.headers)
}

@derive(decoder, encoder)
final case class ProxyResponse(
    uri: String,
    delay: Option[FiniteDuration],
    timeout: Option[FiniteDuration]
) extends HttpStubResponse {
  val isTemplate: Boolean = false
}

@derive(decoder, encoder)
final case class JsonProxyResponse(
    uri: String,
    patch: Map[JsonOptic, String],
    delay: Option[FiniteDuration],
    timeout: Option[FiniteDuration]
) extends HttpStubResponse {
  val isTemplate: Boolean = false
}

@derive(decoder, encoder)
final case class XmlProxyResponse(
    uri: String,
    patch: Map[SXpath, String],
    delay: Option[FiniteDuration],
    timeout: Option[FiniteDuration]
) extends HttpStubResponse {
  val isTemplate: Boolean = false
}

object StubCode {
  def unapply(stub: HttpStubResponse): Option[HttpStatusCode] =
    stub match {
      case EmptyResponse(code, _, _)      => Some(code)
      case RawResponse(code, _, _, _)     => Some(code)
      case JsonResponse(code, _, _, _, _) => Some(code)
      case XmlResponse(code, _, _, _, _)  => Some(code)
      case BinaryResponse(code, _, _, _)  => Some(code)
      case ProxyResponse(_, _, _)         => None
      case JsonProxyResponse(_, _, _, _)  => None
      case XmlProxyResponse(_, _, _, _)   => None
    }
}
