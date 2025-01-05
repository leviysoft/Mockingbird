package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant

import cats.data.NonEmptyVector
import io.circe.Decoder
import io.circe.Encoder
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.Schema

import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.crypto.AES
import ru.tinkoff.tcb.utils.id.SID

final case class DestinationConfiguration(
    name: SID[DestinationConfiguration],
    created: Instant,
    description: String,
    service: String,
    request: EventDestinationRequest,
    init: Option[NonEmptyVector[ResourceRequest]],
    shutdown: Option[NonEmptyVector[ResourceRequest]],
) derives Decoder,
      Encoder,
      Schema

object DestinationConfiguration {
  inline given QueryMeta[DestinationConfiguration] = queryMeta(_.name -> "_id")

  implicit def destinationConfigurationBsonEncoder(implicit aes: AES): BsonEncoder[DestinationConfiguration] =
    BsonEncoder.derived

  implicit def destinationConfigurationBsonDecoder(implicit aes: AES): BsonDecoder[DestinationConfiguration] =
    BsonDecoder.derived
}
