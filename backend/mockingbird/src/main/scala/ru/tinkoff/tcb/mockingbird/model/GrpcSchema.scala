package ru.tinkoff.tcb.mockingbird.model

import com.github.dwickern.macros.NameOf.nameOfType
import enumeratum.CirceEnum
import enumeratum.Enum
import enumeratum.EnumEntry
import enumeratum.EnumEntry.Snakecase
import enumeratum.values.StringCirceEnum
import enumeratum.values.StringEnum
import enumeratum.values.StringEnumEntry
import io.circe.Decoder
import io.circe.Encoder
import io.circe.derivation.Configuration as CirceConfig
import oolong.bson.*
import oolong.bson.annotation.BsonDiscriminator
import oolong.bson.given
import sttp.tapir.Schema
import sttp.tapir.codec.enumeratum.TapirCodecEnumeratum
import sttp.tapir.generic.Configuration as TapirConfig

import ru.tinkoff.tcb.bson.enumeratum.BsonEnum
import ru.tinkoff.tcb.bson.enumeratum.values.StringBsonValueEnum
import ru.tinkoff.tcb.protocol.schema.*

sealed trait GrpcType extends EnumEntry with Snakecase
object GrpcType extends Enum[GrpcType] with CirceEnum[GrpcType] with BsonEnum[GrpcType] with TapirCodecEnumeratum {
  case object Primitive extends GrpcType
  case object Custom extends GrpcType

  override def values: IndexedSeq[GrpcType] = findValues
}

sealed abstract class GrpcLabel(val value: String) extends StringEnumEntry with Snakecase
object GrpcLabel
    extends StringEnum[GrpcLabel]
    with StringCirceEnum[GrpcLabel]
    with StringBsonValueEnum[GrpcLabel]
    with TapirCodecEnumeratum {
  case object Repeated extends GrpcLabel("repeated")
  case object Required extends GrpcLabel("required")
  case object Optional extends GrpcLabel("optional")

  override def values: IndexedSeq[GrpcLabel] = findValues
}

final case class GrpcField(
    typ: GrpcType,
    label: GrpcLabel,
    typeName: String,
    name: String,
    order: Int,
    isProto3Optional: Option[Boolean],
) derives BsonDecoder,
      BsonEncoder,
      Decoder,
      Encoder,
      Schema

@BsonDiscriminator("type")
sealed trait GrpcSchema derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema {
  def name: String
}

//TODO
//@BsonDiscriminator("type")
sealed trait GrpcRootMessage extends GrpcSchema derives BsonDecoder, BsonEncoder, Decoder, Encoder, Schema

object GrpcRootMessage {
  val modes: Map[String, String] = Map(
    nameOfType[GrpcEnumSchema]    -> "enum",
    nameOfType[GrpcMessageSchema] -> "message",
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("type").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("type")
}

object GrpcSchema {
  val modes: Map[String, String] = Map(
    nameOfType[GrpcEnumSchema]    -> "enum",
    nameOfType[GrpcMessageSchema] -> "message",
    nameOfType[GrpcOneOfSchema]   -> "oneof"
  ).withDefault(identity)

  implicit val customConfiguration: TapirConfig =
    TapirConfig.default.withDiscriminator("type").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(transformConstructorNames = modes).withDiscriminator("type")
}

final case class GrpcMessageSchema(
    name: String,
    fields: List[GrpcField],
    oneofs: Option[List[GrpcOneOfSchema]] = None,
    nested: Option[List[GrpcMessageSchema]] = None,
    nestedEnums: Option[List[GrpcEnumSchema]] = None,
) extends GrpcRootMessage
    derives BsonDecoder,
      BsonEncoder,
      Decoder,
      Encoder,
      Schema

object GrpcMessageSchema

final case class GrpcEnumSchema(
    name: String,
    values: Map[FieldName.Type, FieldNumber.Type]
) extends GrpcRootMessage
    derives BsonDecoder,
      BsonEncoder,
      Decoder,
      Encoder,
      Schema

final case class GrpcOneOfSchema(
    name: String,
    options: List[GrpcField]
) extends GrpcSchema
    derives BsonDecoder,
      BsonEncoder,
      Decoder,
      Encoder,
      Schema

final case class GrpcProtoDefinition(
    name: String,
    schemas: List[GrpcRootMessage],
    `package`: Option[String] = None
) derives BsonDecoder,
      BsonEncoder,
      Decoder,
      Encoder,
      Schema
