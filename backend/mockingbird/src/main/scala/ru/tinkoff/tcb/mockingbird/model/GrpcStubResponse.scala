package ru.tinkoff.tcb.mockingbird.model

import scala.concurrent.duration.FiniteDuration

import com.github.dwickern.macros.NameOf.*
import eu.timepit.refined.types.numeric.PosInt
import glass.Contains
import glass.Subset
import glass.macros.GenContains
import glass.macros.GenSubset
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.derivation.Configuration as CirceConfig
import io.circe.refined.*
import oolong.bson.*
import oolong.bson.annotation.BsonDiscriminator
import oolong.bson.given
import oolong.bson.refined.given
import sttp.tapir.Schema
import sttp.tapir.codec.refined.*
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic

@BsonDiscriminator("mode")
sealed trait GrpcStubResponse derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema {
  def delay: Option[FiniteDuration]
}

object GrpcStubResponse {
  val modes: Map[String, String] = Map(
    nameOfType[FillResponse]       -> "fill",
    nameOfType[GProxyResponse]     -> "proxy",
    nameOfType[FillStreamResponse] -> "fill_stream",
    nameOfType[NoBodyResponse]     -> "no_body",
    nameOfType[RepeatResponse]     -> "repeat"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("mode").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("mode")
}

final case class FillResponse(
    data: Json,
    delay: Option[FiniteDuration]
) extends GrpcStubResponse
    derives Decoder,
      Encoder

final case class FillStreamResponse(
    data: Vector[Json],
    delay: Option[FiniteDuration],
    streamDelay: Option[FiniteDuration]
) extends GrpcStubResponse
    derives Decoder,
      Encoder

final case class GProxyResponse(
    endpoint: Option[String],
    patch: Map[JsonOptic, String],
    delay: Option[FiniteDuration]
) extends GrpcStubResponse
    derives Decoder,
      Encoder

object GProxyResponse {
  val prism: Subset[GrpcStubResponse, GProxyResponse] = GenSubset[GrpcStubResponse, GProxyResponse]

  val endpoint: Contains[GProxyResponse, Option[String]] = GenContains[GProxyResponse](_.endpoint)
}

final case class NoBodyResponse(
    delay: Option[FiniteDuration]
) extends GrpcStubResponse
    derives Decoder,
      Encoder

final case class RepeatResponse(
    data: Json,
    repeats: PosInt,
    delay: Option[FiniteDuration],
    streamDelay: Option[FiniteDuration]
) extends GrpcStubResponse
    derives Decoder,
      Encoder
