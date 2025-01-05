package ru.tinkoff.tcb.mockingbird.api.request

import scala.util.matching.Regex

import eu.timepit.refined.*
import eu.timepit.refined.numeric.*
import eu.timepit.refined.types.numeric.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.refined.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*

import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.Callback
import ru.tinkoff.tcb.mockingbird.model.HttpMethod
import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mockingbird.model.HttpStubRequest
import ru.tinkoff.tcb.mockingbird.model.HttpStubResponse
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic

final case class CreateStubRequest(
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[NonNegInt] = Some(refineV[NonNegative].unsafeFrom(1)),
    @description("Mock name")
    name: NonEmptyString,
    @description("HTTP method")
    method: HttpMethod,
    @description("The path suffix where the mock triggers")
    path: Option[String],
    pathPattern: Option[Regex],
    seed: Option[Json],
    @description("State search predicate")
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    @description("Request specification")
    request: HttpStubRequest,
    @description("Persisted data")
    persist: Option[Map[JsonOptic, Json]],
    @description("Response specification")
    response: HttpStubResponse,
    @description("Callback specification")
    callback: Option[Callback],
    @description("Tags")
    labels: Seq[String] = Seq.empty
) derives Decoder,
      Encoder,
      Schema

object CreateStubRequest {
  implicitly[PropSubset[CreateStubRequest, HttpStub]]
}
