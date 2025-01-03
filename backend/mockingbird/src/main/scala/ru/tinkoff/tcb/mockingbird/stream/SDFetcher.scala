package ru.tinkoff.tcb.mockingbird.stream

import scala.annotation.nowarn
import scala.util.control.NonFatal

import fs2.Stream
import zio.interop.catz.*

import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.config.EventConfig
import ru.tinkoff.tcb.mockingbird.dal.DestinationConfigurationDAO
import ru.tinkoff.tcb.mockingbird.dal.SourceConfigurationDAO
import ru.tinkoff.tcb.mockingbird.model.DestinationConfiguration
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration

final class SDFetcher(
    eventConfig: EventConfig,
    sourceCache: Ref[Vector[SourceConfiguration]],
    sourceDAO: SourceConfigurationDAO[Task],
    destionationCache: Ref[Vector[DestinationConfiguration]],
    destinationDAO: DestinationConfigurationDAO[Task]
) {
  private val log = MDCLogging.`for`[WLD](this)

  private def reloadSrc: Stream[[X] =>> RIO[WLD, X], Unit] =
    Stream
      .awakeEvery[[X] =>> RIO[WLD, X]](eventConfig.reloadInterval)
      .evalMap(_ => sourceDAO.getAll)
      .evalTap(sourceCache.set)
      .evalMap(srcs => log.info("Sources received: {}", srcs.map(_.name)))
      .handleErrorWith { case NonFatal(t) =>
        Stream.eval(log.errorCause("Error loading sources", t)) ++
          Stream.sleep[[X] =>> RIO[WLD, X]](eventConfig.reloadInterval) ++ reloadSrc
      }

  private def reloadDest: Stream[[X] =>> RIO[WLD, X], Unit] =
    Stream
      .awakeEvery[[X] =>> RIO[WLD, X]](eventConfig.reloadInterval)
      .evalMap(_ => destinationDAO.getAll)
      .evalTap(destionationCache.set)
      .evalMap(dsts => log.info("Destinations received: {}", dsts.map(_.name)))
      .handleErrorWith { case NonFatal(t) =>
        Stream.eval(log.errorCause("Error loading destinations", t)) ++
          Stream.sleep[[X] =>> RIO[WLD, X]](eventConfig.reloadInterval) ++ reloadDest
      }

  def getSources: UIO[Vector[SourceConfiguration]] = sourceCache.get

  def getDestinations: UIO[Vector[DestinationConfiguration]] = destionationCache.get

  def run: RIO[WLD, Unit] =
    reloadSrc.compile.drain <&> reloadDest.compile.drain
}

object SDFetcher {
  val live = ZLayer {
    for {
      config    <- ZIO.service[EventConfig]
      scache    <- Ref.make(Vector.empty[SourceConfiguration])
      sourceDAO <- ZIO.service[SourceConfigurationDAO[Task]]
      dcache    <- Ref.make(Vector.empty[DestinationConfiguration])
      destDAO   <- ZIO.service[DestinationConfigurationDAO[Task]]
    } yield new SDFetcher(config, scache, sourceDAO, dcache, destDAO)
  }
}
