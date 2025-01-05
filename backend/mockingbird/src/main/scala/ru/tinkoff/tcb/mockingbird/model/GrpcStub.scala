package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant

import eu.timepit.refined.types.numeric.NonNegInt
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.refined.*
import mouse.boolean.*
import oolong.bson.*
import oolong.bson.given
import oolong.bson.refined.given
import oolong.bson.meta.QueryMeta
import oolong.bson.meta.queryMeta
import sttp.tapir.codec.refined.*
import sttp.tapir.Schema

import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.mockingbird.error.ValidationError
import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.primitiveTypes
import ru.tinkoff.tcb.mockingbird.model.GrpcMethodDescription.NormalizedTypeName
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.validation.Rule

final case class GrpcStub(
    id: SID[GrpcStub],
    methodDescriptionId: SID[GrpcMethodDescription],
    scope: Scope,
    created: Instant,
    times: Option[Int],
    name: String,
    response: GrpcStubResponse,
    seed: Option[Json],
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    requestPredicates: JsonPredicate,
    persist: Option[Map[JsonOptic, Json]],
    labels: Seq[String]
) derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema

object GrpcStub {
  private val indexRegex = "\\[([\\d]+)\\]".r

  inline given QueryMeta[GrpcStub] = queryMeta(_.id -> "_id")

  def validateOptics(
      optic: JsonOptic,
      types: Map[NormalizedTypeName.Type, GrpcRootMessage],
      rootFields: List[GrpcField]
  ): IO[ValidationError, Unit] = for {
    fields <- Ref.make(rootFields)
    opticFields = optic.path.split("\\.").map {
      case indexRegex(x) => Left(x.toInt)
      case other         => Right(other)
    }
    _ <- ZIO.foreachDiscard(opticFields) {
      case Left(_) => ZIO.unit
      case Right(fieldName) =>
        for {
          fs <- fields.get
          field <- ZIO.getOrFailWith(ValidationError(Vector(s"Field $fieldName not found")))(fs.find(_.name == fieldName))
          _ <-
            if (primitiveTypes.values.exists(_ == field.typeName)) fields.set(List.empty)
            else
              types.get(NormalizedTypeName.fromString(field.typeName)) match {
                case Some(message) =>
                  message match {
                    case GrpcMessageSchema(_, fs, oneofs, _, _) =>
                      fields.set(fs ++ oneofs.map(_.flatMap(_.options)).getOrElse(List.empty))
                    case GrpcEnumSchema(_, _) => fields.set(List.empty)
                  }
                case None =>
                  ZIO.fail(
                    ValidationError(
                      Vector(s"Message with type ${field.typeName} not found")
                    )
                  )
              }
        } yield ()
    }
  } yield ()

  private val stateNonEmpty: Rule[GrpcStub] =
    _.state.exists(_.isEmpty).valueOrZero(Vector("The state predicate cannot be empty"))

  val validationRules: Rule[GrpcStub] = Vector(stateNonEmpty).reduce(_ |+| _)
}
