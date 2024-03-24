package ru.tinkoff.tcb.mockingbird.model

import java.time.Instant

import derevo.circe.decoder
import derevo.circe.encoder
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.*
import eu.timepit.refined.numeric.*
import io.circe.Json
import io.circe.refined.*
import mouse.boolean.*
import sttp.tapir.codec.refined.*
import sttp.tapir.derevo.schema

import ru.tinkoff.tcb.bson.*
import ru.tinkoff.tcb.bson.annotation.BsonKey
import ru.tinkoff.tcb.bson.derivation.bsonDecoder
import ru.tinkoff.tcb.bson.derivation.bsonEncoder
import ru.tinkoff.tcb.circe.bson.*
import ru.tinkoff.tcb.mockingbird.error.ValidationError
import ru.tinkoff.tcb.mockingbird.grpc.GrpcExractor.primitiveTypes
import ru.tinkoff.tcb.predicatedsl.Keyword
import ru.tinkoff.tcb.predicatedsl.json.JsonPredicate
import ru.tinkoff.tcb.protocol.bson.*
import ru.tinkoff.tcb.protocol.json.*
import ru.tinkoff.tcb.protocol.schema.*
import ru.tinkoff.tcb.utils.circe.optics.JsonOptic
import ru.tinkoff.tcb.utils.id.SID
import ru.tinkoff.tcb.validation.Rule

@derive(bsonDecoder, bsonEncoder, decoder, encoder, schema)
final case class GrpcStub(
    @BsonKey("_id") id: SID[GrpcStub],
    scope: Scope,
    created: Instant,
    service: String Refined NonEmpty,
    times: Option[Int Refined NonNegative],
    methodName: String,
    name: String Refined NonEmpty,
    requestSchema: GrpcProtoDefinition,
    requestClass: String,
    responseSchema: GrpcProtoDefinition,
    responseClass: String,
    response: GrpcStubResponse,
    seed: Option[Json],
    state: Option[Map[JsonOptic, Map[Keyword.Json, Json]]],
    requestPredicates: JsonPredicate,
    persist: Option[Map[JsonOptic, Json]],
    labels: Seq[String]
)

object GrpcStub {
  private val indexRegex = "\\[([\\d]+)\\]".r

  def getRootFields(className: String, definition: GrpcProtoDefinition): IO[ValidationError, List[GrpcField]] = {
    val name = definition.`package`.map(p => className.stripPrefix(p ++ ".")).getOrElse(className)
    for {
      rootMessage <- ZIO.getOrFailWith(ValidationError(Vector(s"Root message '$className' not found")))(
        definition.schemas.find(_.name == name)
      )
      rootFields <- rootMessage match {
        case GrpcMessageSchema(_, fields, oneofs, _, _) =>
          ZIO.succeed(fields ++ oneofs.map(_.flatMap(_.options)).getOrElse(List.empty))
        case GrpcEnumSchema(_, _) =>
          ZIO.fail(ValidationError(Vector(s"Enum cannot be a root message, but '$className' is")))
      }
    } yield rootFields
  }

  def validateOptics(
      optic: JsonOptic,
      definition: GrpcProtoDefinition,
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
          pkgPrefix = definition.`package`.map(p => s".$p.").getOrElse(".")
          field <- ZIO.getOrFailWith(ValidationError(Vector(s"Field $fieldName not found")))(fs.find(_.name == fieldName))
          _ <-
            if (primitiveTypes.values.exists(_ == field.typeName)) fields.set(List.empty)
            else
              definition.schemas.find(_.name == field.typeName.stripPrefix(pkgPrefix)) match {
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
    _.state.exists(_.isEmpty).valueOrZero(Vector("Предикат state не может быть пустым"))

  val validationRules: Rule[GrpcStub] = Vector(stateNonEmpty).reduce(_ |+| _)
}
