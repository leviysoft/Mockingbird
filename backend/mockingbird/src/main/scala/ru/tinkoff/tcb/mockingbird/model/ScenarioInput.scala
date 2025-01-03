package ru.tinkoff.tcb.mockingbird.model

import scala.util.Try
import scala.xml.Node
import com.github.dwickern.macros.NameOf.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser.parse
import io.circe.derivation.Configuration as CirceConfig
import sttp.tapir.generic.Configuration as TapirConfig
import neotype.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.annotation.BsonDiscriminator
import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.predicatedsl.xml.XmlPredicate
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.xml.SafeXML
import ru.tinkoff.tcb.utils.xml.XMLString
import sttp.tapir.Schema

@BsonDiscriminator("mode")
sealed trait ScenarioInput derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema {
  def checkMessage(message: String): Boolean

  def extractJson(message: String): Option[Json]

  def extractXML(message: String): Option[Node]
}

object ScenarioInput {
  val modes: Map[String, String] = Map(
    nameOfType[RawInput]   -> "raw",
    nameOfType[JsonInput]  -> "json",
    nameOfType[XmlInput]   -> "xml",
    nameOfType[JLensInput] -> "jlens",
    nameOfType[XPathInput] -> "xpath"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")
}

final case class RawInput(payload: String) extends ScenarioInput derives Decoder, Encoder {
  override def checkMessage(message: String): Boolean = message == payload

  override def extractJson(message: String): Option[Json] = None

  override def extractXML(message: String): Option[Node] = None
}

final case class JsonInput(payload: Json) extends ScenarioInput derives Decoder, Encoder {
  override def checkMessage(message: String): Boolean =
    parse(message).contains(payload)

  override def extractJson(message: String): Option[Json] =
    parse(message).toOption

  override def extractXML(message: String): Option[Node] = None
}

final case class XmlInput(payload: XMLString.Type) extends ScenarioInput derives Decoder, Encoder {
  override def checkMessage(message: String): Boolean =
    extractXML(message).contains(payload.unwrap)

  override def extractJson(message: String): Option[Json] = None

  override def extractXML(message: String): Option[Node] =
    Try(SafeXML.loadString(message)).toOption
}

final case class JLensInput(payload: JsonPredicate) extends ScenarioInput derives Decoder, Encoder {
  override def checkMessage(message: String): Boolean =
    extractJson(message).map(payload).getOrElse(false)

  override def extractJson(message: String): Option[Json] =
    parse(message).toOption

  override def extractXML(message: String): Option[Node] = None
}

final case class XPathInput(payload: XmlPredicate) extends ScenarioInput derives Decoder, Encoder {
  override def checkMessage(message: String): Boolean =
    extractXML(message).exists(payload(_))

  override def extractJson(message: String): Option[Json] = None

  override def extractXML(message: String): Option[Node] =
    Try(SafeXML.loadString(message)).toOption
}
