package ru.tinkoff.tcb.mockingbird.api.request

import io.circe.Decoder
import io.circe.Encoder
import eu.timepit.refined.api.Refined
import eu.timepit.refined.boolean.*
import eu.timepit.refined.collection.*
import eu.timepit.refined.string.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined.*
import sttp.tapir.codec.refined.*
import sttp.tapir.Schema

import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.Service

final case class CreateServiceRequest(
    suffix: String Refined And[NonEmpty, MatchesRegex["[\\w-]+"]],
    name: NonEmptyString
) derives Decoder, Encoder, Schema

object CreateServiceRequest {
  implicitly[PropSubset[CreateServiceRequest, Service]]
}
