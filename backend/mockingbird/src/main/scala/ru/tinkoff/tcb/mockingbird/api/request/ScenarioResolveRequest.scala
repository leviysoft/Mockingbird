package ru.tinkoff.tcb.mockingbird.api.request

import io.circe.Decoder
import io.circe.Encoder
import sttp.tapir.Schema

import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.utils.id.SID

final case class ScenarioResolveRequest(source: SID[SourceConfiguration], message: String)
    derives Encoder,
      Decoder,
      Schema
