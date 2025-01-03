package ru.tinkoff.tcb.mockingbird.api.response

import io.circe.Decoder
import io.circe.Encoder
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.Schema

import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.utils.id.SID

final case class DestinationDTO(name: SID[DestinationConfiguration], description: String) derives Encoder, Decoder, Schema, BsonDecoder

object DestinationDTO {
    inline given QueryMeta[DestinationConfiguration] = queryMeta(_.name -> "_id")
}
