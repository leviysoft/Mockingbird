package ru.tinkoff.tcb.mockingbird.model

import io.circe.Decoder
import io.circe.Encoder
import oolong.bson.BsonDecoder
import oolong.bson.BsonEncoder
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.Schema

final case class Service(suffix: String, name: String) derives BsonEncoder, BsonDecoder, Decoder, Encoder, Schema

object Service {
  inline given QueryMeta[Service] = queryMeta(_.suffix -> "_id")
}
