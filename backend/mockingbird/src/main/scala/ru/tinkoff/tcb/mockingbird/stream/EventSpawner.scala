package ru.tinkoff.tcb.mockingbird.stream

import scala.util.control.NonFatal

import fs2.Stream
import io.circe.DecodingFailure
import io.circe.Error as CirceError
import io.circe.parser.parse
import mouse.all.optionSyntaxMouse
import mouse.boolean.*
import neotype.*
import sttp.client4.{Backend as SttpBackend, *}
import sttp.model.Method
import zio.interop.catz.*

import ru.tinkoff.tcb.logging.MDCLogging
import ru.tinkoff.tcb.mockingbird.api.Tracing
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.config.EventConfig
import ru.tinkoff.tcb.mockingbird.error.CallbackError
import ru.tinkoff.tcb.mockingbird.error.CompoundError
import ru.tinkoff.tcb.mockingbird.error.EventProcessingError
import ru.tinkoff.tcb.mockingbird.error.ScenarioExecError
import ru.tinkoff.tcb.mockingbird.error.ScenarioSearchError
import ru.tinkoff.tcb.mockingbird.error.SourceFault
import ru.tinkoff.tcb.mockingbird.error.SpawnError
import ru.tinkoff.tcb.mockingbird.model.EventSourceRequest
import ru.tinkoff.tcb.mockingbird.model.ResponseSpec
import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.mockingbird.resource.ResourceManager
import ru.tinkoff.tcb.mockingbird.scenario.ScenarioEngine
import ru.tinkoff.tcb.utils.circe.JsonString
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID

final class EventSpawner(
    eventConfig: EventConfig,
    fetcher: SDFetcher,
    private val httpBackend: SttpBackend[Task],
    engine: ScenarioEngine,
    rm: ResourceManager
) {
  private val log = MDCLogging.`for`[WLD](this)

  private def asStringBypass(bypassCodes: Set[Int]): ResponseAs[Either[String, String]] =
    asStringAlways("utf-8").mapWithMetadata { (s, m) =>
      if (m.isSuccess) Right(s) else if (bypassCodes(m.code.code)) Right("") else Left(s)
    }

  private val jvectorize: JsonOptic => String => Either[CirceError, Vector[String]] =
    (jEmumerator: JsonOptic) =>
      (s: String) =>
        for {
          parsed <- parse(s)
          _      <- jEmumerator.validate(parsed).either(DecodingFailure(s"Can't reach ${jEmumerator.path}", Nil), ())
          values = jEmumerator.getAll(parsed)
        } yield values.map(_.noSpaces)

  private val jextract: JsonOptic => String => Either[CirceError, String] =
    (jExtractor: JsonOptic) =>
      (s: String) =>
        for {
          parsed <- parse(s)
          value  <- jExtractor.getOpt(parsed).toRight(DecodingFailure(s"Can't extract ${jExtractor.path}", Nil))
        } yield value.noSpaces

  private val jdecode: String => Either[CirceError, String] = (s: String) =>
    for {
      parsed <- parse(s)
      decoded <- parsed match {
        case js @ JsonString(str) => parse(str).orElse(Right(js))
        case otherwise            => Right(otherwise)
      }
    } yield decoded.noSpaces

  private def fetch(req: EventSourceRequest, triggers: Vector[ResponseSpec]): Task[Vector[String]] = {
    val request = basicRequest
      .headers(req.headers.view.mapValues(_.unwrap).toMap)
      .pipe(r => req.body.cata(b => r.body(b.unwrap), r))
      .method(Method(req.method.entryName), uri"${req.url.unwrap}")
      .response(asStringBypass(req.bypassCodes.getOrElse(Set())))

    for {
      response <- request.send(httpBackend)
      reInit = triggers.exists(sp => sp.code.forall(_ == response.code.code) && sp.checkBody(response.body.merge))
      body <- ZIO
        .fromEither(response.body)
        .mapError[Exception](err =>
          (if (reInit) SourceFault(_) else EventProcessingError(_))(
            s"The request to ${req.url.unwrap} ended with an error ($err)"
          )
        )
      processed <- ZIO.fromEither {
        for {
          vectorized <- req.jenumerate.map(jvectorize).getOrElse((s: String) => Right(Vector(s)))(body)
          extracted  <- vectorized.traverse(req.jextract.map(jextract).getOrElse(Right(_: String)))
          decoded    <- req.jstringdecode.fold(extracted.traverse(jdecode), Right(extracted))
        } yield decoded
      }
    } yield processed
  }

  private def fetchStream: Stream[[X] =>> RIO[WLD, X], Unit] =
    Stream
      .awakeEvery[[X] =>> RIO[WLD, X]](eventConfig.fetchInterval)
      .evalMap(_ => fetcher.getSources)
      .evalMap(
        ZIO
          .validateParDiscard(_) { sourceConf =>
            (for {
              _ <- Tracing.init
              res <- fetch(sourceConf.request, sourceConf.reInitTriggers.map(_.toVector).orEmpty)
                .mapError[Exception](SpawnError(sourceConf.name, _))
              neRes = res.filter(_.nonEmpty)
              _ <- ZIO.when(neRes.nonEmpty)(log.info(s"Sent for processing: ${neRes.length}"))
              _ <- ZIO
                .validateDiscard(neRes) {
                  engine.perform(sourceConf.name, _)
                }
                .mapError(CompoundError(_))
            } yield ())
              .catchSomeDefect { case NonFatal(ex) =>
                ZIO.fail(SpawnError(sourceConf.name, ex))
              }
          }
          .mapError(CompoundError(_))
      )
      .handleErrorWith {
        case thr if recover.isDefinedAt(thr) =>
          Stream.eval(recover(thr)) ++ Stream.sleep[[X] =>> RIO[WLD, X]](eventConfig.fetchInterval) ++ fetchStream
        case CompoundError(errs) =>
          val recoverable = errs.filter(recover.isDefinedAt)
          val fatal       = errs.find(!recover.isDefinedAt(_))

          Stream.evalSeq(ZIO.foreach(recoverable)(recover)) ++ Stream.raiseError[[X] =>> RIO[WLD, X]](fatal.get).as(())
        case thr =>
          Stream.raiseError[[X] =>> RIO[WLD, X]](thr).as(())
      }

  def run: RIO[WLD, Unit] = fetchStream.compile.drain

  private lazy val recover: PartialFunction[Throwable, URIO[WLD, Unit]] = {
    case CompoundError(errs) if errs.forall(recover.isDefinedAt) =>
      ZIO.foreachDiscard(errs)(recover)
    case SpawnError(sid, SourceFault(_)) =>
      rm.reinitialize(sid.asInstanceOf[SID[SourceConfiguration]])
    case EventProcessingError(err) =>
      log.warn(s"Error processing the event: $err")
    case ScenarioExecError(err) =>
      log.warn(s"Error executing the scenario: $err")
    case ScenarioSearchError(err) =>
      log.warn(s"Error searching for the scenario: $err")
    case CallbackError(err) =>
      log.warn(s"Error executing the callback: $err")
    case SpawnError(sid, err) =>
      log.errorCause(s"Error retrieving event from {}", err, sid)
    case NonFatal(t) =>
      log.errorCause("Error retrieving event", t)
  }
}

object EventSpawner {
  val live = ZLayer {
    for {
      config         <- ZIO.service[EventConfig]
      fetcher        <- ZIO.service[SDFetcher]
      sttpClient     <- ZIO.service[SttpBackend[Task]]
      scenarioEngine <- ZIO.service[ScenarioEngine]
      rm             <- ZIO.service[ResourceManager]
    } yield new EventSpawner(config, fetcher, sttpClient, scenarioEngine, rm)
  }
}
