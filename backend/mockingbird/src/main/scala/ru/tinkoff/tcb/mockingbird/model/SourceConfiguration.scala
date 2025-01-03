package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant
import cats.data.NonEmptyVector
import io.circe.{Decoder, Encoder}
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.crypto.AES
import ru.tinkoff.tcb.utils.id.SID
import sttp.tapir.Schema

final case class SourceConfiguration(
    name: SID[SourceConfiguration],
    created: Instant,
    description: String,
    service: String,
    request: EventSourceRequest,
    init: Option[NonEmptyVector[ResourceRequest]],
    shutdown: Option[NonEmptyVector[ResourceRequest]],
    reInitTriggers: Option[NonEmptyVector[ResponseSpec]]
) derives Decoder, Encoder, Schema

object SourceConfiguration {
  inline given QueryMeta[SourceConfiguration] = queryMeta(_.name -> "_id")

  implicit def sourceConfigurationBsonEncoder(implicit aes: AES): BsonEncoder[SourceConfiguration] =
    BsonEncoder.derived

  implicit def sourceConfigurationBsonDecoder(implicit aes: AES): BsonDecoder[SourceConfiguration] =
    BsonDecoder.derived
}
