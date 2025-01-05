package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant

import com.github.dwickern.macros.NameOf.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.refined.*
import mouse.boolean.*
import sttp.tapir.Schema.annotations.description
import sttp.tapir.codec.refined.*
import sttp.tapir.Schema

import oolong.bson.*
import oolong.bson.given
import oolong.bson.refined.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.unpack.*
import ru.tinkoff.tcb.validation.Rule

final case class Scenario(
    @description("Scenario id")
    id: SID[Scenario],
    @description("Scenario creation time")
    created: Instant,
    @description("Scope")
    scope: Scope,
    @description("The number of possible triggers. Only relevant for scope=countdown")
    times: Option[Int],
    service: String,
    @description("Scenario name (shown in logs, handy for debugging)")
    name: String,
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
) derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema

object Scenario extends CallbackChecker {
  inline given QueryMeta[Scenario] = queryMeta(_.id -> "_id")

  private val destOutp: Rule[Scenario] = (s: Scenario) =>
    (s.destination, s.output) match {
      case Some(_) <*> Some(_) | None <*> None => Vector.empty
      case None <*> Some(_) =>
        Vector(
          s"The field ${nameOf[Scenario](_.destination)} must be filled in if ${nameOf[Scenario](_.output)} is present"
        )
      case Some(_) <*> None =>
        Vector(
          s"The field ${nameOf[Scenario](_.destination)} must be filled in ONLY if ${nameOf[Scenario](_.output)} is present"
        )
    }

  private def checkSourceId(sources: Set[SID[SourceConfiguration]]): Rule[Scenario] =
    (s: Scenario) => sources(s.source) !? Vector(s"${s.source} is not configured")

  private def checkDestinationId(destinations: Set[SID[DestinationConfiguration]]): Rule[Scenario] =
    (s: Scenario) =>
      s.destination.map(destinations).getOrElse(true) !? Vector(s"${s.destination.getOrElse("")} is not configured")

  private val stateNonEmpty: Rule[Scenario] =
    _.state.exists(_.isEmpty).valueOrZero(Vector("The state predicate cannot be empty."))

  private val persistNonEmpty: Rule[Scenario] =
    _.persist.exists(_.isEmpty).valueOrZero(Vector("The persist specification cannot be empty"))

  def validationRules(
      sources: Set[SID[SourceConfiguration]],
      destinations: Set[SID[DestinationConfiguration]]
  ): Rule[Scenario] =
    Vector(
      destOutp,
      (s: Scenario) => checkCallback(s.callback, destinations),
      checkSourceId(sources),
      checkDestinationId(destinations),
      stateNonEmpty,
      persistNonEmpty
    ).reduce(_ |+| _)
}
