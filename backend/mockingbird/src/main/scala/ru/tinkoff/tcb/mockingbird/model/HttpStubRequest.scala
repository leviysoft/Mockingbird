package ru.tinkoff.tcb.mockingbird.model

import scala.util.Try
import scala.xml.Node

import com.github.dwickern.macros.NameOf.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.derivation.Configuration as CirceConfig
import io.circe.parser.parse
import neotype.*
import oolong.bson.*
import oolong.bson.annotation.BsonDiscriminator
import oolong.bson.given
import sttp.model.Part
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.form.FormPredicate
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.predicatedsl.xml.XmlPredicate
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.transformation.xml.*
import ru.tinkoff.tcb.utils.webform.decode as decodeForm
import ru.tinkoff.tcb.utils.webform.toJson
import ru.tinkoff.tcb.utils.xml.SafeXML
import ru.tinkoff.tcb.utils.xml.XMLString

@BsonDiscriminator("mode")
sealed trait HttpStubRequest derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema {
  def headers: Map[String, String]

  def query: Map[JsonOptic, Map[Keyword.Json, Json]]

  def checkHeaders(hs: Map[String, String]): Boolean =
    headers.forall { case (k, v) =>
      hs.exists {
        case (kx, vx) => k.toLowerCase == kx.toLowerCase && v == vx
        case _        => false
      }
    }

  def checkQueryParams(params: Json): Boolean =
    if (query.isEmpty) true else JsonPredicate(query).map(_(params)).toOption.getOrElse(false)

  def checkBody(rBody: RequestBody): Boolean

  def extractJson(rBody: RequestBody): Option[Json]

  def extractXML(rBody: RequestBody): Option[Node]

  def runXmlExtractors(body: Node): Json
}
object HttpStubRequest {
  val modes: Map[String, String] = Map(
    nameOfType[JsonRequest]        -> "json",
    nameOfType[XmlRequest]         -> "xml",
    nameOfType[RawRequest]         -> "raw",
    nameOfType[JLensRequest]       -> "jlens",
    nameOfType[XPathRequest]       -> "xpath",
    nameOfType[RequestWithoutBody] -> "no_body",
    nameOfType[RequestWithAnyBody] -> "any_body",
    nameOfType[WebFormRequest]     -> "web_form",
    nameOfType[MultipartRequest]   -> "multipart"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")
}

final case class JsonRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: Json
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    extractJson(rBody).contains(body)

  override def extractJson(rBody: RequestBody): Option[Json] =
    SimpleRequestBody.subset.getOption(rBody).map(_.value).flatMap(parse(_).toOption)

  override def extractXML(rBody: RequestBody): Option[Node] = None

  override def runXmlExtractors(body: Node): Json = Json.Null
}

final case class XmlRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: XMLString.Type,
    extractors: Map[String, XmlExtractor] = Map.empty,
    inlineCData: Boolean = false
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    extractXML(rBody).contains(body.unwrap)

  override def extractJson(rBody: RequestBody): Option[Json] = None

  override def extractXML(rBody: RequestBody): Option[Node] =
    SimpleRequestBody.subset.getOption(rBody).map(_.value).flatMap { bodyStr =>
      if (inlineCData) {
        Try(SafeXML.loadString(bodyStr)).toOption
          .map(_.inlineXmlFromCData)
      } else
        Try(SafeXML.loadString(bodyStr)).toOption
    }

  override def runXmlExtractors(body: Node): Json =
    extractors.view.mapValues(_(body)).collect { case (key, Some(jv)) => key -> jv } pipe Json.fromFields
}

final case class RawRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: String
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    SimpleRequestBody.subset.getOption(rBody).map(_.value).contains(body)

  override def extractJson(rBody: RequestBody): Option[Json] = None

  override def extractXML(rBody: RequestBody): Option[Node] = None

  override def runXmlExtractors(body: Node): Json = Json.Null
}

final case class JLensRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: JsonPredicate
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    extractJson(rBody).map(body).getOrElse(false)

  override def extractJson(rBody: RequestBody): Option[Json] =
    SimpleRequestBody.subset.getOption(rBody).map(_.value).flatMap(parse(_).toOption)

  override def extractXML(rBody: RequestBody): Option[Node] = None

  override def runXmlExtractors(body: Node): Json = Json.Null
}

final case class XPathRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: XmlPredicate,
    extractors: Map[String, XmlExtractor] = Map.empty,
    inlineCData: Boolean = false
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    extractXML(rBody).map(body).getOrElse(false)

  override def extractJson(rBody: RequestBody): Option[Json] = None

  override def extractXML(rBody: RequestBody): Option[Node] =
    SimpleRequestBody.subset.getOption(rBody).map(_.value).flatMap { bodyStr =>
      if (inlineCData) {
        Try(SafeXML.loadString(bodyStr)).toOption
          .map(_.inlineXmlFromCData)
      } else
        Try(SafeXML.loadString(bodyStr)).toOption
    }

  override def runXmlExtractors(body: Node): Json =
    extractors.view.mapValues(_(body)).collect { case (key, Some(jv)) => key -> jv } pipe Json.fromFields
}

final case class WebFormRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: FormPredicate
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  private def extractForm(rBody: RequestBody) =
    SimpleRequestBody.subset.getOption(rBody).map(_.value).map(decodeForm)

  override def checkBody(rBody: RequestBody): Boolean =
    extractForm(rBody).map(body).getOrElse(false)

  override def extractJson(rBody: RequestBody): Option[Json] =
    extractForm(rBody).map(toJson)

  override def extractXML(rBody: RequestBody): Option[Node] = None

  override def runXmlExtractors(body: Node): Json = Json.Null
}

final case class RequestWithoutBody(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    AbsentRequestBody.subset.getOption(rBody).isDefined ||
      SimpleRequestBody.subset.getOption(rBody).exists(_.value.isEmpty)

  override def extractJson(rBody: RequestBody): Option[Json] = None

  override def extractXML(rBody: RequestBody): Option[Node] = None

  override def runXmlExtractors(body: Node): Json = Json.Null
}

final case class RequestWithAnyBody(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    SimpleRequestBody.subset.getOption(rBody).exists(_.value.nonEmpty)

  override def extractJson(rBody: RequestBody): Option[Json] = None

  override def extractXML(rBody: RequestBody): Option[Node] = None

  override def runXmlExtractors(body: Node): Json = Json.Null
}

@BsonDiscriminator("mode")
sealed trait RequestPart derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema {
  def headers: Map[String, String]

  def checkHeaders(hs: Map[String, String]): Boolean =
    headers.forall { case (k, v) =>
      hs.exists {
        case (kx, vx) => k.toLowerCase == kx.toLowerCase && v == vx
        case _        => false
      }
    }

  def checkPart(part: Part[String]): Boolean =
    checkHeaders(part.headers.map(h => h.name -> h.value).toMap) && checkBody(part.body)

  def checkBody(value: String): Boolean

  def extractJson(body: String): Option[Json]

  def extractXML(body: String): Option[Node]
}
object RequestPart {
  val modes: Map[String, String] = Map(
    nameOfType[AnyContentPart] -> "any",
    nameOfType[RawPart]        -> "raw",
    nameOfType[JsonPart]       -> "json",
    nameOfType[XMLPart]        -> "xml",
    nameOfType[UrlEncodedPart] -> "urlencoded",
    nameOfType[JLensPart]      -> "jlens",
    nameOfType[XPathPart]      -> "xpath"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")
}

final case class AnyContentPart(headers: Map[String, String]) extends RequestPart derives Decoder, Encoder {
  override def checkBody(value: String): Boolean = true

  override def extractJson(body: String): Option[Json] = None

  override def extractXML(body: String): Option[Node] = None
}

final case class RawPart(headers: Map[String, String], body: String) extends RequestPart derives Decoder, Encoder {
  override def checkBody(value: String): Boolean = value == body

  override def extractJson(body: String): Option[Json] = None

  override def extractXML(body: String): Option[Node] = None
}

final case class UrlEncodedPart(headers: Map[String, String], body: FormPredicate) extends RequestPart
    derives Decoder,
      Encoder {
  override def checkBody(value: String): Boolean = body(decodeForm(value))

  override def extractJson(body: String): Option[Json] = Option(toJson(decodeForm(body)))

  override def extractXML(body: String): Option[Node] = None
}

final case class JsonPart(headers: Map[String, String], body: Json) extends RequestPart derives Decoder, Encoder {
  override def checkBody(value: String): Boolean = parse(value).contains(body)

  override def extractJson(body: String): Option[Json] = parse(body).toOption

  override def extractXML(body: String): Option[Node] = None
}

final case class XMLPart(headers: Map[String, String], body: XMLString.Type) extends RequestPart
    derives Decoder,
      Encoder {
  override def checkBody(value: String): Boolean = Try(SafeXML.loadString(value)).exists(_ == body.unwrap)

  override def extractJson(body: String): Option[Json] = None

  override def extractXML(body: String): Option[Node] = Try(SafeXML.loadString(body)).toOption
}

final case class JLensPart(headers: Map[String, String], body: JsonPredicate) extends RequestPart
    derives Decoder,
      Encoder {
  override def checkBody(value: String): Boolean = parse(value).map(body).getOrElse(false)

  override def extractJson(body: String): Option[Json] = parse(body).toOption

  override def extractXML(body: String): Option[Node] = None
}

final case class XPathPart(headers: Map[String, String], body: XmlPredicate) extends RequestPart
    derives Decoder,
      Encoder {
  override def checkBody(value: String): Boolean = Try(SafeXML.loadString(value)).map(body).getOrElse(false)

  override def extractJson(body: String): Option[Json] = None

  override def extractXML(body: String): Option[Node] = Try(SafeXML.loadString(body)).toOption
}

final case class MultipartRequest(
    headers: Map[String, String],
    query: Map[JsonOptic, Map[Keyword.Json, Json]] = Map.empty,
    body: Map[String, RequestPart],
    bypassUnknownParts: Boolean = true
) extends HttpStubRequest
    derives Decoder,
      Encoder {
  override def checkBody(rBody: RequestBody): Boolean =
    MultipartRequestBody.subset.getOption(rBody).exists { requestBody =>
      requestBody.value.forall { requestBodyPart =>
        (!body.keySet.contains(requestBodyPart.name) && bypassUnknownParts) || body(requestBodyPart.name)
          .checkPart(requestBodyPart)
      }
    }

  override def extractJson(rBody: RequestBody): Option[Json] =
    MultipartRequestBody.subset.getOption(rBody).flatMap { requestBody =>
      val jo = requestBody.value
        .map(p => body.get(p.name).flatMap(rp => rp.extractJson(p.body)).map(p.name -> _))
        .collect { case Some(kv) => kv }
        .foldLeft(JsonObject.empty) { case (acc, (key, value)) => acc.add(key, value) }
      if (jo.isEmpty) None else Some(Json.fromJsonObject(jo))
    }

  override def extractXML(rBody: RequestBody): Option[Node] =
    MultipartRequestBody.subset.getOption(rBody).flatMap { requestBody =>
      requestBody.value.view.map(p => body.get(p.name).flatMap(rp => rp.extractXML(p.body))).collectFirst {
        case Some(node) => node
      }
    }

  override def runXmlExtractors(body: Node): Json = Json.Null
}
