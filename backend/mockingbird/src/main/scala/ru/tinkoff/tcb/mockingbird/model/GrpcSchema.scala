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
sealed trait GrpcSchema {
  def name: String
}

//TODO
//@BsonDiscriminator("type")
sealed trait GrpcRootMessage extends GrpcSchema

object GrpcRootMessage {
  val modes: Map[String, String] = Map(
    nameOfType[GrpcEnumSchema]    -> "enum",
    nameOfType[GrpcMessageSchema] -> "message",
  ).withDefault(identity)

  given TapirConfig = TapirConfig.default.withDiscriminator("type").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(
    transformConstructorNames = modes,
    useDefaults = true,
    discriminator = Some("type")
  )

  // These instances are defined as implicit defs due to Scala 3 derivation limitations
  implicit def bd: BsonDecoder[GrpcRootMessage] = BsonDecoder.derived
  implicit def be: BsonEncoder[GrpcRootMessage] = BsonEncoder.derived
  implicit def enc: Encoder[GrpcRootMessage]    = Encoder.AsObject.derivedConfigured
  implicit def dec: Decoder[GrpcRootMessage]    = Decoder.derivedConfigured
  implicit def sch: Schema[GrpcRootMessage]     = Schema.derived
}

object GrpcSchema {
  val modes: Map[String, String] = Map(
    nameOfType[GrpcEnumSchema]    -> "enum",
    nameOfType[GrpcMessageSchema] -> "message",
    nameOfType[GrpcOneOfSchema]   -> "oneof"
  ).withDefault(identity)

  given TapirConfig = TapirConfig.default.withDiscriminator("type").copy(toEncodedName = modes)

  given CirceConfig = CirceConfig(
    transformConstructorNames = modes,
    useDefaults = true,
    discriminator = Some("type")
  )

  // These instances are defined as implicit defs due to Scala 3 derivation limitations
  implicit def bd: BsonDecoder[GrpcSchema] = BsonDecoder.derived
  implicit def be: BsonEncoder[GrpcSchema] = BsonEncoder.derived
  implicit def enc: Encoder[GrpcSchema]    = Encoder.AsObject.derivedConfigured
  implicit def dec: Decoder[GrpcSchema]    = Decoder.derivedConfigured
  implicit def sch: Schema[GrpcSchema]     = Schema.derived
}

final case class GrpcMessageSchema(
    name: String,
    fields: List[GrpcField],
    oneofs: Option[List[GrpcOneOfSchema]] = None,
    nested: Option[List[GrpcMessageSchema]] = None,
    nestedEnums: Option[List[GrpcEnumSchema]] = None,
) extends GrpcRootMessage

object GrpcMessageSchema {
  // These instances are defined as implicit defs due to Scala 3 derivation limitations
  implicit def bd: BsonDecoder[GrpcMessageSchema] = BsonDecoder.derived
  implicit def be: BsonEncoder[GrpcMessageSchema] = BsonEncoder.derived
  implicit def enc: Encoder[GrpcMessageSchema]    = Encoder.derived
  implicit def dec: Decoder[GrpcMessageSchema]    = Decoder.derived
  implicit def sch: Schema[GrpcMessageSchema]     = Schema.derived
}

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
