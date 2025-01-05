package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant

import io.circe.Decoder
import io.circe.Encoder
import io.scalaland.chimney.dsl.*
import neotype.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.Schema

import ru.tinkoff.tcb.mockingbird.api.request.CreateGrpcStubRequest
import ru.tinkoff.tcb.mockingbird.error.ValidationError
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.utils.id.SID

final case class GrpcMethodDescription(
    id: SID[GrpcMethodDescription],
    description: String,
    created: Instant,
    service: String,
    methodName: String,
    connectionType: GrpcConnectionType,
    proxyUrl: Option[String],
    requestClass: String,
    requestSchema: GrpcProtoDefinition,
    responseClass: String,
    responseSchema: GrpcProtoDefinition
) derives BsonDecoder,
      BsonEncoder,
      Decoder,
      Encoder,
      Schema

object GrpcMethodDescription {
  inline given QueryMeta[GrpcMethodDescription] = queryMeta(_.id -> "_id")

  def getRootFields(
      name: NormalizedTypeName.Type,
      types: Map[NormalizedTypeName.Type, GrpcRootMessage] = Map.empty
  ): IO[ValidationError, List[GrpcField]] =
    for {
      rootMessage <- ZIO.getOrFailWith(ValidationError(Vector(s"Root message '${name}' not found")))(
        types.get(name)
      )
      rootFields <- rootMessage match {
        case GrpcMessageSchema(_, fields, oneofs, _, _) =>
          ZIO.succeed(fields ++ oneofs.map(_.flatMap(_.options)).getOrElse(List.empty))
        case GrpcEnumSchema(_, _) =>
          ZIO.fail(ValidationError(Vector(s"Enum cannot be a root message, but '${name}' is")))
      }
    } yield rootFields

  def fromCreateRequest(
      request: CreateGrpcStubRequest,
      requestSchema: GrpcProtoDefinition,
      responseSchema: GrpcProtoDefinition,
      created: Instant,
  ): GrpcMethodDescription = {
    val proxyUrl = (GProxyResponse.prism >> GProxyResponse.endpoint).getOption(request.response).flatten

    request
      .into[GrpcMethodDescription]
      .withFieldConst(_.id, SID.random[GrpcMethodDescription])
      .withFieldConst(_.description, "")
      .withFieldConst(_.connectionType, GrpcConnectionType.Unary)
      .withFieldConst(_.proxyUrl, proxyUrl)
      .withFieldConst(_.requestSchema, requestSchema)
      .withFieldConst(_.responseSchema, responseSchema)
      .withFieldConst(_.created, created)
      .transform
  }

  object PackagePrefix extends Newtype[String] {
    def fromProtoDef(definition: GrpcProtoDefinition): PackagePrefix.Type =
      this.unsafeMake(definition.`package`.map(p => s".$p.").getOrElse("."))
  }

  extension (pp: PackagePrefix.Type) {
    def :+(nested: String): PackagePrefix.Type =
      PackagePrefix(pp.unwrap ++ nested.dropWhile(_ == '.') ++ ".")

    def resolve(n: String): NormalizedTypeName.Type =
      if (n.startsWith(".")) NormalizedTypeName.fromString(n)
      else if (n.startsWith(pp.unwrap.drop(1))) NormalizedTypeName.fromString(s".$n")
      else NormalizedTypeName.fromString(s"${pp.unwrap}$n")
  }

  object NormalizedTypeName extends Newtype[String] {
    def fromString(name: String): NormalizedTypeName.Type =
      this.unsafeMake(s".${name.dropWhile(_ == '.')}")
  }

  def makeDictTypes(p: PackagePrefix.Type, ms: Seq[GrpcRootMessage]): Vector[(NormalizedTypeName.Type, GrpcRootMessage)] =
    ms.foldLeft(Vector.empty[(NormalizedTypeName.Type, GrpcRootMessage)]) {
      case (b, m @ GrpcMessageSchema(name, _, _, nested, nestedEnums)) =>
        (b :+ (p.resolve(name) -> m)) ++
          makeDictTypes(p :+ name, nested.getOrElse(Nil)) ++
          makeDictTypes(p :+ name, nestedEnums.getOrElse(Nil))
      case (b, m @ GrpcEnumSchema(name, _)) =>
        b :+ (p.resolve(name) -> m)
    }

  def validate(methodDescription: GrpcMethodDescription)(
      requestClass: String,
      requestSchema: GrpcProtoDefinition,
      responseClass: String,
      responseSchema: GrpcProtoDefinition
  ): IO[ValidationError, Unit] = {
    val validated = methodDescription.requestClass == requestClass &&
      methodDescription.responseClass == responseClass &&
      methodDescription.requestSchema == requestSchema &&
      methodDescription.responseSchema == responseSchema

    ZIO
      .unless(validated)(
        ZIO.fail(
          ValidationError(
            Vector(s"Existing description for method ${methodDescription.methodName} differs from request description")
          )
        )
      )
      .unit
  }
}
