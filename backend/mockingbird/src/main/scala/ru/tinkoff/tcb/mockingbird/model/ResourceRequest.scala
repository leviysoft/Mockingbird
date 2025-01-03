package ru.tinkoff.tcb.mockingbird.model

import io.circe.{Decoder, Encoder}
import oolong.bson.*
import oolong.bson.given
import sttp.tapir.Schema
import ru.tinkoff.tcb.utils.crypto.AES

final case class ResourceRequest(
    url: SecureString.Type,
    method: HttpMethod,
    headers: Map[String, SecureString.Type] = Map(),
    body: Option[SecureString.Type] = None,
) derives Decoder, Encoder, Schema

object ResourceRequest {
  implicit def resourceRequestBsonEncoder(implicit aes: AES): BsonEncoder[ResourceRequest] =
    BsonEncoder.derived

  implicit def resourceRequestBsonDecoder(implicit aes: AES): BsonDecoder[ResourceRequest] =
    BsonDecoder.derived
}
