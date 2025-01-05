package ru.tinkoff.tcb.mockingbird.api

import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import sttp.model.Header
import sttp.model.StatusCode
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.RequestInterceptor
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interceptor.RequestResult.Failure
import sttp.tapir.server.interceptor.RequestResult.Response
import sttp.tapir.server.vertx.zio.VertxZioServerInterpreter
import sttp.tapir.server.vertx.zio.VertxZioServerOptions
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*

import ru.tinkoff.tcb.mockingbird.api.exec.*
import ru.tinkoff.tcb.mockingbird.build.BuildInfo
import ru.tinkoff.tcb.mockingbird.model.BinaryResponse
import ru.tinkoff.tcb.mockingbird.model.EmptyResponse
import ru.tinkoff.tcb.mockingbird.model.HttpMethod
import ru.tinkoff.tcb.mockingbird.model.HttpStubResponse
import ru.tinkoff.tcb.mockingbird.model.JsonProxyResponse
import ru.tinkoff.tcb.mockingbird.model.JsonResponse
import ru.tinkoff.tcb.mockingbird.model.ProxyResponse
import ru.tinkoff.tcb.mockingbird.model.RawResponse
import ru.tinkoff.tcb.mockingbird.model.RequestBody
import ru.tinkoff.tcb.mockingbird.model.XmlProxyResponse
import ru.tinkoff.tcb.mockingbird.model.XmlResponse
import ru.tinkoff.tcb.mockingbird.wldRuntime

final class PublicHttp(handler: PublicApiHandler) {
  private val withString = endpointsWithString.map(_.zServerLogic[WLD](handle.tupled))
  private val withMultipart =
    endpointsWithMultipart.map(_.zServerLogic[WLD](handle.tupled))

  private val swaggerEndpoints =
    SwaggerInterpreter(
      swaggerUIOptions = SwaggerUIOptions(
        "api" :: "mockingbird" :: "swagger" :: Nil,
        "docs.yaml",
        Nil,
        useRelativePaths = false,
        showExtensions = false
      )
    ).fromEndpoints[[X] =>> RIO[WLD, X]](
      endpointsWithString ++ endpointsWithMultipart,
      "Mockingbird",
      BuildInfo.version
    )

  private val options =
    VertxZioServerOptions
      .customiseInterceptors[WLD]
      .notAcceptableInterceptor(None)
      .addInterceptor(
        RequestInterceptor.effect(r =>
          for {
            tracing <- ZIO.service[Tracing]
            _       <- Tracing.init
            _       <- tracing.fillWithHeaders(r.headers)
          } yield ()
        )
      )
      .addInterceptor(
        RequestInterceptor.transformResult(new RequestInterceptor.RequestResultTransform[[X] =>> RIO[Tracing, X]] {
          override def apply[B](request: ServerRequest, result: RequestResult[B]): RIO[Tracing, RequestResult[B]] =
            for {
              tracing <- ZIO.service[Tracing]
              headers <- tracing.toHeaders()
            } yield result match {
              case fail @ Failure(_)  => fail
              case Response(response) => Response(response.addHeaders(headers))
            }
        })
      )
      .options

  val http: List[Router => Route] =
    (withString ++ withMultipart ++ swaggerEndpoints)
      .map(VertxZioServerInterpreter(options).route(_)(wldRuntime))

  private def handle(
      method: HttpMethod,
      path: String,
      headers: Map[String, String],
      query: Seq[(String, Seq[String])],
      body: RequestBody
  ): ZIO[WLD, Throwable, (List[Header], StatusCode, HttpStubResponse)] =
    handler
      .exec(method, path, headers, query, body)
      .tap(_.delay.fold[UIO[Unit]](ZIO.unit)(fd => ZIO.sleep(Duration.fromScala(fd))))
      .map {
        case r @ EmptyResponse(sc, headers, _) =>
          (headers.map { case (name, value) => Header(name, value) }.to(List), StatusCode(sc.value), r)
        case r @ RawResponse(sc, headers, _, _) =>
          (headers.map { case (name, value) => Header(name, value) }.to(List), StatusCode(sc.value), r)
        case j @ JsonResponse(sc, headers, _, _, _) =>
          (headers.map { case (name, value) => Header(name, value) }.to(List), StatusCode(sc.value), j)
        case x @ XmlResponse(sc, headers, _, _, _) =>
          (headers.map { case (name, value) => Header(name, value) }.to(List), StatusCode(sc.value), x)
        case b @ BinaryResponse(sc, headers, _, _) =>
          (headers.map { case (name, value) => Header(name, value) }.to(List), StatusCode(sc.value), b)
        case p @ ProxyResponse(_, _, _)         => (Nil, StatusCode.InternalServerError, p)
        case jp @ JsonProxyResponse(_, _, _, _) => (Nil, StatusCode.InternalServerError, jp)
        case xp @ XmlProxyResponse(_, _, _, _)  => (Nil, StatusCode.InternalServerError, xp)
      }
}

object PublicHttp {
  def live: RLayer[PublicApiHandler, PublicHttp] = ZLayer.fromFunction(new PublicHttp(_))
}
