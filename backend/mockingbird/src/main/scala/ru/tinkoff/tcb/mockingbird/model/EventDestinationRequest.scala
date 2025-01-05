package ru.tinkoff.tcb.mockingbird.model

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import oolong.bson.*
import oolong.bson.given
import sttp.tapir.Schema

import ru.tinkoff.tcb.circe.bson.given
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.crypto.AES

final case class EventDestinationRequest(
    url: SecureString.Type,
    method: HttpMethod,
    headers: Map[String, SecureString.Type],
    body: Option[Json],
    stringifybody: Option[Boolean],
    encodeBase64: Option[Boolean]
) derives Decoder,
      Encoder,
      Schema

object EventDestinationRequest {
  implicit def eventDestinationRequestBsonEncoder(implicit aes: AES): BsonEncoder[EventDestinationRequest] =
    BsonEncoder.derived

  implicit def eventDestinationRequestBsonDecoder(implicit aes: AES): BsonDecoder[EventDestinationRequest] =
    BsonDecoder.derived
}
