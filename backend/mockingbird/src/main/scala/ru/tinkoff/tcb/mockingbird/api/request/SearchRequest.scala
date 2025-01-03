package ru.tinkoff.tcb.mockingbird.api.request

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import sttp.tapir.Schema

import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic

final case class SearchRequest(query: Map[JsonOptic, Map[Keyword.Json, Json]]) derives Encoder, Decoder, Schema
