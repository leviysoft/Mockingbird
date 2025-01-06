package ru.tinkoff.tcb.mockingbird.api.request

import eu.timepit.refined.*
import eu.timepit.refined.numeric.*
import eu.timepit.refined.types.numeric.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.derivation.ConfiguredDecoder
import io.circe.derivation.ConfiguredEncoder
import io.circe.refined.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import oolong.bson.refined.given
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.mockingbird.model.Callback
import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.mockingbird.model.Scenario
import ru.tinkoff.tcb.mockingbird.model.ScenarioInput
import ru.tinkoff.tcb.mockingbird.model.ScenarioOutput
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.given
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID

final case class UpdateScenarioRequest(
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[NonNegInt] = Some(refineV[NonNegative].unsafeFrom(1)),
    service: NonEmptyString,
    @description("Scenario name (shown in logs, handy for debugging)")
    name: NonEmptyString,
    @description("Event source name")
    source: SID[SourceConfiguration],
    seed: Option[Json],
    @description("Event specification")
    input: ScenarioInput,
    @description("State search predicate")
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    @description("Persisted data")
    persist: Option[Map[JsonOptic, Json]],
    @description("Destination name")
    destination: Option[SID[DestinationConfiguration]],
    @description("Response specification")
    output: Option[ScenarioOutput],
    @description("Callback specification")
    callback: Option[Callback],
    @description("Tags")
    labels: Seq[String]
) derives ConfiguredDecoder,
      ConfiguredEncoder,
      Schema
object UpdateScenarioRequest {
  implicitly[PropSubset[UpdateScenarioRequest, ScenarioPatch]]
}

final case class ScenarioPatch(
    id: SID[Scenario],
    scope: Scope,
    times: Option[NonNegInt],
    service: NonEmptyString,
    name: NonEmptyString,
    source: SID[SourceConfiguration],
    seed: Option[Json],
    input: ScenarioInput,
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    persist: Option[Map[JsonOptic, Json]],
    destination: Option[SID[DestinationConfiguration]],
    output: Option[ScenarioOutput],
    callback: Option[Callback],
    labels: Seq[String]
) derives BsonEncoder

object ScenarioPatch {
  inline given QueryMeta[ScenarioPatch] = queryMeta(_.id -> "_id")
}
