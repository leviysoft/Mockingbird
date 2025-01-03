package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant
import java.util.UUID

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.Schema

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.id.SID

final case class PersistentState(
    id: SID[PersistentState],
    data: Json,
    created: Instant
) derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema

object PersistentState {
  inline given QueryMeta[PersistentState] = queryMeta(_.id -> "_id")

  def fresh: Task[PersistentState] =
    ZIO.clockWith(_.instant).map(PersistentState(SID(UUID.randomUUID().toString), Json.obj(), _))
}
