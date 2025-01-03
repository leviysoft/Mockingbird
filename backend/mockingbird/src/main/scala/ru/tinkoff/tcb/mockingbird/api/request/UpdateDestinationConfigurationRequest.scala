package ru.tinkoff.tcb.mockingbird.api.request

import cats.data.NonEmptyVector
import io.circe.Decoder
import io.circe.Encoder
import sttp.tapir.Schema.annotations.description
import sttp.tapir.Schema

import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.mockingbird.model.EventDestinationRequest
import ru.tinkoff.tcb.mockingbird.model.ResourceRequest
import ru.tinkoff.tcb.protocol.schema.*

final case class UpdateDestinationConfigurationRequest(
    @description("Configuration description")
    description: String,
    service: String,
    @description("Request specification")
    request: EventDestinationRequest,
    @description("Initializer specification")
    init: Option[NonEmptyVector[ResourceRequest]],
    @description("Finalizer specification")
    shutdown: Option[NonEmptyVector[ResourceRequest]],
) derives Decoder, Encoder, Schema

object UpdateDestinationConfigurationRequest {
  implicitly[PropSubset[UpdateDestinationConfigurationRequest, DestinationConfiguration]]
}
