package ru.tinkoff.tcb.mockingbird.stream

import eu.timepit.fs2cron.cron4s.Cron4sScheduler
import fs2.Stream
import oolong.bson.given
import oolong.dsl.*
import oolong.mongo.*
import tofu.logging.Logging
import tofu.logging.impl.ZUniversalLogging
import zio.interop.catz.*

import ru.tinkoff.tcb.mockingbird.dal.GrpcStubDAO
import ru.tinkoff.tcb.mockingbird.dal.HttpStubDAO
import ru.tinkoff.tcb.mockingbird.dal.ScenarioDAO
import ru.tinkoff.tcb.mockingbird.model.GrpcStub
import ru.tinkoff.tcb.mockingbird.model.HttpStub
import ru.tinkoff.tcb.mockingbird.model.Scenario
import ru.tinkoff.tcb.mockingbird.model.Scope

final class EphemeralCleaner(
    stubDAO: HttpStubDAO[Task],
    scenarioDAO: ScenarioDAO[Task],
    grpcStubDAO: GrpcStubDAO[Task]
) {
  private val log: Logging[UIO] = new ZUniversalLogging(this.getClass.getName)

  private val cronScheduler = Cron4sScheduler.systemDefault[Task]

  private val trigger = cronScheduler.awakeEvery(midnight)

  private val secondsInDay = 60 * 60 * 24

  private val cleanup = Stream.eval[Task, Long] {
    for {
      current <- ZIO.clock.flatMap(_.instant)
      threshold = current.minusSeconds(secondsInDay)
      deleted <- stubDAO.delete(
        query[HttpStub](hs => Set[Scope](lift(Scope.Ephemeral), lift(Scope.Countdown)).contains(hs.scope) && hs.created.isBefore(lift(threshold)))
      )
      _ <- log.info("Purging expired stubs: {} deleted", deleted)
      deleted2 <- scenarioDAO.delete(
        query[Scenario](s => Set[Scope](lift(Scope.Ephemeral), lift(Scope.Countdown)).contains(s.scope) && s.created.isBefore(lift(threshold)))
      )
      _ <- log.info("Purging expired scenarios: {} deleted", deleted2)
      deleted3 <- grpcStubDAO.delete(
        query[GrpcStub](gs => Set[Scope](lift(Scope.Ephemeral), lift(Scope.Countdown)).contains(gs.scope) && gs.created.isBefore(lift(threshold)))
      )
      _ <- log.info("Purging expired grpc stubs: {} deleted", deleted3)
      deleted4 <- stubDAO.delete(
        query[HttpStub](hs => hs.scope == lift(Scope.Countdown) && hs.times.!! <= 0)
      )
      _ <- log.info("Purging countdown stubs: {} deleted", deleted4)
      deleted5 <- scenarioDAO.delete(
        query[Scenario](s => s.scope == lift(Scope.Countdown) && s.times.!! <= 0)
      )
      _ <- log.info("Purging countdown scenarios: {} deleted", deleted5)
      deleted6 <- grpcStubDAO.delete(
        query[GrpcStub](gs => gs.scope == lift(Scope.Countdown) && gs.times.!! <= 0)
      )
      _ <- log.info("Purging countdown grpc stubs: {} deleted", deleted6)
    } yield deleted
  }

  private val stream = trigger >> cleanup

  def run: Task[Unit] = stream.compile.drain
}

object EphemeralCleaner {
  val live: URLayer[HttpStubDAO[Task] & ScenarioDAO[Task] & GrpcStubDAO[Task], EphemeralCleaner] =
    ZLayer.fromFunction(new EphemeralCleaner(_, _, _))
}
