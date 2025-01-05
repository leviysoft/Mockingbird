package ru.tinkoff.tcb.mockingbird.api.request

import cats.data.NonEmptyVector
import io.circe.Decoder
import io.circe.Encoder
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description

import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.EventSourceRequest
import ru.tinkoff.tcb.mockingbird.model.ResourceRequest
import ru.tinkoff.tcb.mockingbird.model.ResponseSpec
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.protocol.schema.*

final case class UpdateSourceConfigurationRequest(
    @description("Configuration description")
    description: String,
    service: String,
    @description("Request specification")
    request: EventSourceRequest,
    @description("Initializer specification")
    init: Option[NonEmptyVector[ResourceRequest]],
    @description("Finalizer specification")
    shutdown: Option[NonEmptyVector[ResourceRequest]],
    @description("Reinitialization triggers specification")
    reInitTriggers: Option[NonEmptyVector[ResponseSpec]]
) derives Decoder,
      Encoder,
      Schema

object UpdateSourceConfigurationRequest {
  implicitly[PropSubset[UpdateSourceConfigurationRequest, SourceConfiguration]]
}
