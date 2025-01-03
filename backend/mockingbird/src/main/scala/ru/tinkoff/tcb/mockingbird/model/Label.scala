package ru.tinkoff.tcb.mockingbird.model

import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import ru.tinkoff.tcb.utils.id.SID

final case class Label(
    id: SID[Label],
    serviceSuffix: String,
    label: String
) derives BsonDecoder, BsonEncoder

object Label {
  inline given QueryMeta[Label] = queryMeta(_.id -> "_id")
}
