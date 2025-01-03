package ru.tinkoff.tcb.mockingbird

import java.util.Base64
import scala.util.Try
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.KeyDecoder
import io.circe.KeyEncoder
import neotype.*
import oolong.bson.{BsonDecoder, BsonEncoder, BsonKeyDecoder, BsonKeyEncoder}
import oolong.bson.given
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.BsonString
import sttp.tapir.Schema
import ru.tinkoff.tcb.generic.RootOptionFields
import ru.tinkoff.tcb.utils.crypto.AES

package object model {
  object ByteArray extends Newtype[Array[Byte]] {
    implicit val byteArrayBsonEncoder: BsonEncoder[ByteArray.Type] =
      BsonEncoder[Array[Byte]].beforeWrite(_.unwrap)

    implicit val byteArrayBsonDecoder: BsonDecoder[ByteArray.Type] =
      BsonDecoder[Array[Byte]].afterRead(this.unsafeMake)

    implicit val byteArraySchema: Schema[ByteArray.Type] =
      Schema.schemaForString.as[ByteArray.Type].format("base64")

    implicit val byteArrayDecoder: Decoder[ByteArray.Type] =
      Decoder.decodeString.emapTry(s => Try(Base64.getDecoder.decode(s))).map(this.unsafeMake)

    implicit val byteArrayEncoder: Encoder[ByteArray.Type] =
      Encoder.encodeString.contramap(ba => Base64.getEncoder.encodeToString(ba.unwrap))

    implicit val byteArrayRof: RootOptionFields[ByteArray.Type] = RootOptionFields.mk(Set.empty)
  }

  object FieldNumber extends Newtype[Int] {
    implicit val fieldNumberSchema: Schema[FieldNumber.Type] =
      Schema.schemaForInt.as[FieldNumber.Type]

    implicit val fieldNumberEncoder: Encoder[FieldNumber.Type] =
      Encoder[Int].contramap(_.unwrap)

    implicit val fieldNumberDecoder: Decoder[FieldNumber.Type] =
      Decoder[Int].map(unsafeMake)

    implicit val fieldNumberBsonEncoder: BsonEncoder[FieldNumber.Type] =
      BsonEncoder[Int].beforeWrite(_.unwrap)

    implicit val fieldNumberBsonDecoder: BsonDecoder[FieldNumber.Type] =
      BsonDecoder[Int].afterRead(unsafeMake)
  }

  object FieldName extends Newtype[String] {
    implicit val fieldNameKeyEncoder: KeyEncoder[FieldName.Type] =
      KeyEncoder[String].contramap(_.unwrap)

    implicit val fieldNameKeyDecoder: KeyDecoder[FieldName.Type] =
      KeyDecoder[String].map(unsafeMake)

    implicit val fieldNameBsonKeyEncoder: BsonKeyEncoder[FieldName.Type] =
      (t: FieldName.Type) => t.unwrap

    implicit val fieldNameBsonKeyDecoder: BsonKeyDecoder[FieldName.Type] =
      (value: String) => Try(unsafeMake(value))
  }

  object SecureString extends Newtype[String] {
    implicit def secureStringBsonEncoder(implicit aes: AES): BsonEncoder[SecureString.Type] =
      (value: SecureString.Type) => {
        val (data, salt, iv) = aes.encrypt(value.unwrap)

        BsonDocument(
          "d" -> BsonString(data),
          "s" -> BsonString(salt),
          "i" -> BsonString(iv)
        )
      }

    implicit def secureStringBsonDecoder(implicit aes: AES): BsonDecoder[SecureString.Type] =
      BsonDecoder.ofDocument[SecureString.Type] { doc =>
        (
          Try(doc.getString("d")),
          Try(doc.getString("s")),
          Try(doc.getString("i"))
        ).mapN { case (data, salt, iv) => unsafeMake(aes.decrypt(data.getValue, salt.getValue, iv.getValue)) }
      }

    implicit val secureStringSchema: Schema[SecureString.Type] =
      Schema.schemaForString.as[SecureString.Type]

    implicit val secureStringDecoder: Decoder[SecureString.Type] =
      Decoder.decodeString.map(this.unsafeMake)

    implicit val secureStringEncoder: Encoder[SecureString.Type] =
      Encoder.encodeString.contramap(_.unwrap)

    implicit val secureStringRof: RootOptionFields[SecureString.Type] = RootOptionFields.mk(Set.empty)
  }

  type HttpStatusCodeRange = Interval.ClosedOpen[100, 600]
  type HttpStatusCode      = Int Refined HttpStatusCodeRange
}
