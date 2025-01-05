package ru.tinkoff.tcb.mockingbird.scenario

import scala.xml.Node

import eu.timepit.refined.*
import eu.timepit.refined.numeric.*
import io.circe.Json
import mouse.boolean.*
import mouse.option.*
import oolong.bson.*
import oolong.bson.given
import oolong.dsl.*
import oolong.mongo.*
import zio.interop.catz.core.*

import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.dal.PersistentStateDAO
import ru.tinkoff.tcb.mockingbird.dal.ScenarioDAO
import ru.tinkoff.tcb.mockingbird.error.EarlyReturn
import ru.tinkoff.tcb.mockingbird.error.ScenarioSearchError
import ru.tinkoff.tcb.mockingbird.misc.Renderable.ops.*
import ru.tinkoff.tcb.mockingbird.model.PersistentState
import ru.tinkoff.tcb.mockingbird.model.Scenario
import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox

class ScenarioResolver(
    scenarioDAO: ScenarioDAO[Task],
    stateDAO: PersistentStateDAO[Task],
    implicit val jsSandbox: GraalJsSandbox
) {
  private val log = MDCLogging.`for`[WLD](this)

  private type StateSpec = Map[JsonOptic, Map[Keyword.Json, Json]]

  def findScenarioAndState(source: SID[SourceConfiguration], message: String)(
      scope: Scope
  ): RIO[WLD, Option[(Scenario, Option[PersistentState])]] =
    (for {
      _ <- log.info("Searching for scenarios for source {} of type {}", source, scope)
      condition0 = if (scope != Scope.Countdown) query[Scenario](s => s.source == lift(source) && s.scope == lift(scope))
                   else query[Scenario](s => s.source == lift(source) && s.scope == lift(scope) && s.times.!! > 0)
      scenarios0 <- scenarioDAO.findChunk(condition0, 0, Int.MaxValue)
      _ <- ZIO.when(scenarios0.isEmpty)(
        log.info("No handlers found for source {} of type {}", source, scope) *>
          ZIO.fail(EarlyReturn)
      )
      _ <- log.info("Candidates are: {}", scenarios0.map(_.id))
      scenarios1 = scenarios0.filter(_.input.checkMessage(message))
      _ <- ZIO.when(scenarios1.isEmpty)(
        log
          .warn("After validating the message, there are no candidates left. Please verify the message: {}", message) *>
          ZIO.fail(EarlyReturn)
      )
      _ <- log.info("After message validation: {}", scenarios1.map(_.id))
      scenarios2 <- scenarios1.traverse { scenc =>
        val bodyJson = scenc.input.extractJson(message)
        val bodyXml  = scenc.input.extractXML(message)
        computeStateSpec(scenc.state, bodyJson, bodyXml)
          .cata(
            spec => findStates(scenc.id, spec).map(scenc -> _),
            ZIO.succeed(scenc -> Vector.empty[PersistentState])
          )
      }
      _ <- ZIO.when(scenarios2.exists(_._2.size > 1))(
        log.error("For one or more scenarios, multiple suitable states were found") *>
          ZIO.fail(
            ScenarioSearchError("For one or more scenarios, multiple suitable states were found")
          )
      )
      _ <- ZIO.when(scenarios2.count(_._2.nonEmpty) > 1)(
        log.error("For more than one scenario, suitable states were found") *>
          ZIO.fail(ScenarioSearchError("For more than one scenario, suitable states were found"))
      )
      _ <- ZIO.when(scenarios2.size > 1 && scenarios2.forall(c => c._1.state.isDefined && c._2.isEmpty))(
        log.error("No suitable states found for any scenario") *>
          ZIO.fail(ScenarioSearchError("No suitable states found for any scenario"))
      )
      _ <- ZIO.when(scenarios2.size > 1 && scenarios2.forall(_._1.state.isEmpty))(
        log.error("More than one stateless scenario found") *>
          ZIO.fail(ScenarioSearchError("More than one stateless scenario found"))
      )
      res = scenarios2.find(_._2.size == 1) orElse scenarios2.find(_._1.state.isEmpty)
    } yield res.map { case (scenario, states) => scenario -> states.headOption }).catchSome { case EarlyReturn =>
      ZIO.none
    }

  private def computeStateSpec(
      spec: Option[StateSpec],
      bodyJson: Option[Json],
      bodyXml: Option[Node]
  ): Option[StateSpec] =
    (spec, bodyJson).mapN(_.fill(_)).orElse((spec, bodyXml).mapN(_.fill(_))).orElse(spec)

  private def findStates[E](id: SID[E], spec: StateSpec): RIO[WLD, Vector[PersistentState]] =
    for {
      _      <- log.info("Searching for state for {} based on condition {}", id, spec.renderJson.noSpaces)
      states <- stateDAO.findBySpec(spec)
      _ <-
        if (states.nonEmpty) log.info("States found for {}: {}", id, states.map(_.id))
        else log.info("No suitable states found for {}", id)
    } yield states
}

object ScenarioResolver {
  val live = ZLayer {
    for {
      sd        <- ZIO.service[ScenarioDAO[Task]]
      ssd       <- ZIO.service[PersistentStateDAO[Task]]
      jsSandbox <- ZIO.service[GraalJsSandbox]
    } yield new ScenarioResolver(sd, ssd, jsSandbox)
  }
}
