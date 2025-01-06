package ru.tinkoff.tcb.mockingbird.model

import io.circe.Decoder
import io.circe.Encoder
import oolong.bson.*
import oolong.bson.given
import sttp.tapir.Schema

import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.crypto.AES

final case class EventSourceRequest(
    url: SecureString.Type,
    method: HttpMethod,
    headers: Map[String, SecureString.Type],
    body: Option[SecureString.Type],
    jenumerate: Option[JsonOptic],
    jextract: Option[JsonOptic],
    bypassCodes: Option[Set[Int]],
    jstringdecode: Boolean = false
) derives Decoder,
      Encoder,
      Schema

object EventSourceRequest {
  implicit def eventSourceRequestBsonEncoder(implicit aes: AES): BsonEncoder[EventSourceRequest] =
    BsonEncoder.derived

  implicit def eventSourceRequestBsonDecoder(implicit aes: AES): BsonDecoder[EventSourceRequest] =
    BsonDecoder.derived
}
