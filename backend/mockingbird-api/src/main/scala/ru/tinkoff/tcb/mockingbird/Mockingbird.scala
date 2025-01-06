package ru.tinkoff.tcb.mockingbird

import java.net.http.HttpClient
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import scala.jdk.DurationConverters.*

import com.mongodb.ConnectionString
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import org.mongodb.scala.MongoClient
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.MongoDatabase
import org.mongodb.scala.bson.BsonDocument
import scalapb.zio_grpc.server.ZServerCallHandler
import sttp.client4.BackendOptions as SttpBackendOptions
import sttp.client4.httpclient.zio.HttpClientZioBackend
import tofu.logging.Logging
import tofu.logging.impl.ZUniversalLogging
import zio.managed.*
import zio.stream.Stream

import ru.tinkoff.tcb.mockingbird.api.AdminApiHandler
import ru.tinkoff.tcb.mockingbird.api.AdminHttp
import ru.tinkoff.tcb.mockingbird.api.MetricsHttp
import ru.tinkoff.tcb.mockingbird.api.PublicApiHandler
import ru.tinkoff.tcb.mockingbird.api.PublicHttp
import ru.tinkoff.tcb.mockingbird.api.StubResolver
import ru.tinkoff.tcb.mockingbird.api.Tracing
import ru.tinkoff.tcb.mockingbird.api.UIHttp
import ru.tinkoff.tcb.mockingbird.api.WLD
import ru.tinkoff.tcb.mockingbird.api.WebAPI
import ru.tinkoff.tcb.mockingbird.config.HttpVersion
import ru.tinkoff.tcb.mockingbird.config.MockingbirdConfiguration
import ru.tinkoff.tcb.mockingbird.config.MongoCollections
import ru.tinkoff.tcb.mockingbird.config.MongoConfig
import ru.tinkoff.tcb.mockingbird.config.ProxyConfig
import ru.tinkoff.tcb.mockingbird.config.ProxyServerType
import ru.tinkoff.tcb.mockingbird.config.ServerConfig
import ru.tinkoff.tcb.mockingbird.dal.*
import ru.tinkoff.tcb.mockingbird.grpc.GrpcRequestHandler
import ru.tinkoff.tcb.mockingbird.grpc.GrpcRequestHandlerImpl
import ru.tinkoff.tcb.mockingbird.grpc.GrpcStubResolverImpl
import ru.tinkoff.tcb.mockingbird.grpc.ProtobufSchemaResolverImpl
import ru.tinkoff.tcb.mockingbird.grpc.UniversalHandlerRegistry
import ru.tinkoff.tcb.mockingbird.resource.ResourceManager
import ru.tinkoff.tcb.mockingbird.scenario.ScenarioEngine
import ru.tinkoff.tcb.mockingbird.scenario.ScenarioResolver
import ru.tinkoff.tcb.mockingbird.stream.EphemeralCleaner
import ru.tinkoff.tcb.mockingbird.stream.EventSpawner
import ru.tinkoff.tcb.mockingbird.stream.SDFetcher
import ru.tinkoff.tcb.utils.metrics.makeRegistry
import ru.tinkoff.tcb.utils.resource.readStr
import ru.tinkoff.tcb.utils.sandboxing.GraalJsSandbox

object Mockingbird {
  type FL = WLD & ServerConfig & PublicHttp & EventSpawner & ResourceManager & EphemeralCleaner & GrpcRequestHandler

  private def zioLog: Logging[UIO] = new ZUniversalLogging(this.getClass.getName)

  private def mongoLayer = ZLayer {
    for {
      config <- ZIO.service[MongoConfig]
    } yield MongoClient(config.uri).getDatabase(new ConnectionString(config.uri).getDatabase)
  }

  private def collection(
      name: MongoCollections => String
  ): URLayer[MongoConfig & MongoDatabase, MongoCollection[BsonDocument]] =
    ZLayer {
      for {
        mongo  <- ZIO.service[MongoDatabase]
        config <- ZIO.service[MongoConfig]
      } yield mongo.getCollection(name(config.collections))
    }

  private def program =
    (for {
      _       <- ZIO.unit.toManagedWith(_ => zioLog.info("Done releasing resources"))
      ec      <- ZIO.service[EphemeralCleaner].toManaged
      _       <- ec.run.forkDaemon.toManaged
      _       <- ResourceManager.managed
      fetcher <- ZIO.service[SDFetcher].toManaged
      _       <- fetcher.run.forkDaemon.toManaged
      spawner <- ZIO.service[EventSpawner].toManaged
      _       <- spawner.run.forkDaemon.toManaged
      _       <- WebAPI.managed
      _ <- zioLog
        .info(s"App started")
        .toManagedWith(_ => zioLog.info("Start releasing resources"))
    } yield ())
      .use(_ => ZIO.never)
      .exitCode

  private def server = ZLayer.fromZIO {
    program
      .provide(
        Tracing.live,
        ZLayer.succeed(makeRegistry("mockingbird")),
        MockingbirdConfiguration.server,
        MockingbirdConfiguration.security,
        MockingbirdConfiguration.mongo,
        MockingbirdConfiguration.proxy,
        MockingbirdConfiguration.event,
        MockingbirdConfiguration.tracing,
        ZLayer.scoped {
          for {
            pc <- ZIO.service[ProxyConfig]
            sttpSettings = pc.proxyServer.fold(SttpBackendOptions.Default) { psc =>
              SttpBackendOptions.Default
                .copy(proxy =
                  SttpBackendOptions
                    .Proxy(
                      psc.host,
                      psc.port,
                      (psc.`type`: @unchecked) match {
                        case ProxyServerType.Http  => SttpBackendOptions.ProxyType.Http
                        case ProxyServerType.Socks => SttpBackendOptions.ProxyType.Socks
                      },
                      psc.nonProxy.to(List),
                      psc.auth.map(psa => SttpBackendOptions.ProxyAuth(psa.user, psa.password)),
                      psc.onlyProxy.to(List)
                    )
                    .some
                )
            }
            keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm).tap { kmf =>
              kmf.init(null, Array())
            }
            trustManager = TrustSomeHostsManager.of(pc.insecureHosts.to(Set))
            sslContext = SSLContext.getInstance("TLS").tap { sslc =>
              sslc.init(keyManagerFactory.getKeyManagers, Array(trustManager), new SecureRandom)
            }
            httpClient = HttpClient
              .newBuilder()
              .version(pc.httpVersion match {
                case HttpVersion.HTTP_1_1 => HttpClient.Version.HTTP_1_1
                case HttpVersion.HTTP_2   => HttpClient.Version.HTTP_2
              })
              .connectTimeout(sttpSettings.connectionTimeout.toJava)
              .pipe(b => sttpSettings.proxy.fold(b)(conf => b.proxy(conf.asJavaProxySelector)))
              .sslContext(sslContext)
              .build()
            scopedBackend <- HttpClientZioBackend.scopedUsingClient(httpClient)
          } yield scopedBackend
        },
        (ZLayer.service[ServerConfig].project(_.sandbox) ++ ZLayer.fromZIO(
          ZIO.attempt(readStr("prelude.js")).map(Option(_))
        )) >>> GraalJsSandbox.live,
        mongoLayer,
        aesEncoder,
        collection(_.stub) >>> HttpStubDAOImpl.live,
        collection(_.state) >>> PersistentStateDAOImpl.live,
        collection(_.scenario) >>> ScenarioDAOImpl.live,
        collection(_.service) >>> ServiceDAOImpl.live,
        collection(_.label) >>> LabelDAOImpl.live,
        collection(_.grpcStub) >>> GrpcStubDAOImpl.live,
        collection(_.grpcMethodDescription) >>> GrpcMethodDescriptionDAOImpl.live,
        collection(_.source) >>> SourceConfigurationDAOImpl.live,
        collection(_.destination) >>> DestinationConfigurationDAOImpl.live,
        ProtobufSchemaResolverImpl.live,
        PublicApiHandler.live,
        PublicHttp.live,
        AdminApiHandler.live,
        AdminHttp.live,
        UIHttp.live,
        MetricsHttp.live,
        EphemeralCleaner.live,
        ScenarioResolver.live,
        ScenarioEngine.live,
        StubResolver.live,
        SDFetcher.live,
        EventSpawner.live,
        ResourceManager.live
      )
  }

  def port: Int = 9000

  def builder: ServerBuilder[?] = ServerBuilder.forPort(port)

  private def grpcHandlerLayer = ZLayer.make[WLD & GrpcRequestHandler](
    Tracing.live,
    MockingbirdConfiguration.server,
    MockingbirdConfiguration.mongo,
    MockingbirdConfiguration.tracing,
    mongoLayer,
    collection(_.state) >>> PersistentStateDAOImpl.live,
    collection(_.grpcStub) >>> GrpcStubDAOImpl.live,
    collection(_.grpcMethodDescription) >>> GrpcMethodDescriptionDAOImpl.live,
    (ZLayer.service[ServerConfig].project(_.sandbox) +!+ ZLayer
      .fromZIO(ZIO.attempt(readStr("prelude.js")).map(Option(_)))) >>> GraalJsSandbox.live,
    GrpcStubResolverImpl.live,
    GrpcRequestHandlerImpl.live
  )

  def runGRPCServer: UIO[Unit] = for {
    runtime <- zio.ZIO.runtime[Any]
    handler = ZServerCallHandler.bidiCallHandler(
      runtime,
      (stream: Stream[StatusException, Array[Byte]], requestContext) =>
        GrpcRequestHandler
          .exec(stream)
          .provideLayer(ZLayer.succeed(requestContext) +!+ grpcHandlerLayer)
          .mapError((e: Throwable) => new StatusException(Status.INTERNAL.withDescription(e.getMessage)))
    )
    mutableRegistry = UniversalHandlerRegistry(
      handler
    )
    _ = builder.fallbackHandlerRegistry(mutableRegistry)
    _ = builder.build().start()
  } yield ()

  def main(args: Array[String]): Unit =
    Unsafe.unsafe { implicit us =>
      wldRuntime.unsafe.run {
        (runGRPCServer *> zioLog.info(s"GRPC server started at port: $port") *> ZIO.scoped[Any](
          server.build *> ZIO.never
        ))
          .catchAll(ex => zioLog.errorCause(ex.getMessage, ex))
          .catchAllDefect(ex => zioLog.errorCause(ex.getMessage, ex))
          .exitCode
      }
    }
}
