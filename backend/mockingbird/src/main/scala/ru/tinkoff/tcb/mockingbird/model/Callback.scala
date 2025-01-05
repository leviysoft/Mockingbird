package ru.tinkoff.tcb.mockingbird.model

import scala.concurrent.duration.FiniteDuration

import com.github.dwickern.macros.NameOf.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.derivation.Configuration as CirceConfig
import oolong.bson.*
import oolong.bson.annotation.BsonDiscriminator
import oolong.bson.given
import sttp.tapir.Schema
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID

@BsonDiscriminator("type")
sealed trait Callback {
  def delay: Option[FiniteDuration]
}

object Callback {
  val modes: Map[String, String] = Map(
    nameOfType[MessageCallback] -> "message",
    nameOfType[HttpCallback]    -> "http"
  ).withDefault(identity)

  given TapirConfig = TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")

  given Schema[Callback] = Schema.derived[Callback]

  given BsonDecoder[Callback] = BsonDecoder.derived
  given BsonEncoder[Callback] = BsonEncoder.derived
  given Encoder[Callback] = Encoder.derived
  given Decoder[Callback] = Decoder.derived
}

final case class MessageCallback(
    destination: SID[DestinationConfiguration],
    output: ScenarioOutput,
    callback: Option[Callback],
    delay: Option[FiniteDuration] = None
) extends Callback

final case class HttpCallback(
    request: CallbackRequest,
    responseMode: Option[CallbackResponseMode],
    persist: Option[Map[JsonOptic, Json]],
    callback: Option[Callback],
    delay: Option[FiniteDuration] = None
) extends Callback
