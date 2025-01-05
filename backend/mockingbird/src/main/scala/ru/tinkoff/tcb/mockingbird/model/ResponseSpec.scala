package ru.tinkoff.tcb.mockingbird.model

import scala.util.Try

import com.github.dwickern.macros.NameOf.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.derivation.Configuration as CirceConfig
import io.circe.parser.parse
import neotype.*
import oolong.bson.*
import oolong.bson.given
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.predicatedsl.xml.XmlPredicate
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.xml.SafeXML
import ru.tinkoff.tcb.utils.xml.XMLString

sealed trait ResponseSpec derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema {
  val code: Option[Int]
  def checkBody(data: String): Boolean
}

object ResponseSpec {
  val modes: Map[String, String] = Map(
    nameOfType[JsonResponseSpec]  -> "json",
    nameOfType[XmlResponseSpec]   -> "xml",
    nameOfType[RawResponseSpec]   -> "raw",
    nameOfType[JLensResponseSpec] -> "jlens",
    nameOfType[XPathResponseSpec] -> "xpath"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")
}

final case class RawResponseSpec(code: Option[Int], body: Option[String]) extends ResponseSpec
    derives Decoder,
      Encoder {
  override def checkBody(data: String): Boolean = body.forall(_ == data)
}

final case class JsonResponseSpec(code: Option[Int], body: Option[Json]) extends ResponseSpec derives Decoder, Encoder {
  override def checkBody(data: String): Boolean = parse(data).map(jx => body.forall(_ == jx)).getOrElse(false)
}

final case class XmlResponseSpec(code: Option[Int], body: Option[XMLString.Type]) extends ResponseSpec
    derives Decoder,
      Encoder {
  override def checkBody(data: String): Boolean =
    Try(SafeXML.loadString(data)).toOption.exists(nx => body.forall(_.unwrap == nx))
}

final case class JLensResponseSpec(code: Option[Int], body: Option[JsonPredicate]) extends ResponseSpec
    derives Decoder,
      Encoder {
  override def checkBody(data: String): Boolean = parse(data).map(jx => body.forall(_(jx))).getOrElse(false)
}

final case class XPathResponseSpec(code: Option[Int], body: Option[XmlPredicate]) extends ResponseSpec
    derives Decoder,
      Encoder {
  override def checkBody(data: String): Boolean =
    Try(SafeXML.loadString(data)).exists(nx => body.forall(_(nx)))
}
