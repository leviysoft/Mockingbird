package ru.tinkoff.tcb.utils.sandboxing

import java.lang as jl
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import alleycats.std.map.*
import io.circe.Json
import io.circe.JsonNumber
import io.circe.JsonObject
import neotype.*
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.*

package object conversion {
  object GValue extends Newtype[AnyRef]

  val circe2js: Json.Folder[GValue.Type] = new Json.Folder[GValue.Type] {
    override def onNull: GValue.Type = GValue(null)

    override def onBoolean(value: Boolean): GValue.Type = GValue(jl.Boolean.valueOf(value))

    override def onNumber(value: JsonNumber): GValue.Type =
      GValue(value.toLong.map(jl.Long.valueOf).getOrElse(jl.Float.valueOf(value.toFloat)))

    override def onString(value: String): GValue.Type = GValue(value)

    override def onArray(value: Vector[Json]): GValue.Type = GValue(new ProxyArray {
      override def get(index: Long): AnyRef             = value(index.toInt).foldWith(circe2js).unwrap
      override def set(index: Long, value: Value): Unit = throw new UnsupportedOperationException()
      override def getSize: Long                        = value.size
    })

    override def onObject(value: JsonObject): GValue.Type = GValue(new ProxyObject {
      override def getMember(key: String): AnyRef = value.apply(key).map(_.foldWith(circe2js).unwrap).orNull
      override def getMemberKeys: AnyRef = new ProxyArray {
        private val keys                                  = value.keys.toVector
        override def get(index: Long): AnyRef             = keys(index.toInt)
        override def set(index: Long, value: Value): Unit = throw new UnsupportedOperationException()
        override def getSize: Long                        = keys.size
      }
      override def hasMember(key: String): Boolean            = value.keys.exists(_ == key)
      override def putMember(key: String, value: Value): Unit = throw new UnsupportedOperationException()
    })
  }

  implicit class ValueConverter(private val value: Value) extends AnyVal {
    def toJson: Try[Json] =
      value match {
        case v if v.isNull    => Success(Json.Null)
        case v if v.isBoolean => Success(if (v.asBoolean()) Json.True else Json.False)
        case v if v.isNumber && (v.fitsInByte() || v.fitsInShort() || v.fitsInInt()) => Success(Json.fromInt(v.asInt()))
        case v if v.isNumber && v.fitsInLong()       => Success(Json.fromLong(v.asLong()))
        case v if v.isNumber && v.fitsInBigInteger() => Success(Json.fromBigInt(v.asBigInteger()))
        case v if v.isNumber && v.fitsInFloat()      => Success(Json.fromFloatOrNull(v.asFloat()))
        case v if v.isNumber && v.fitsInDouble()     => Success(Json.fromDoubleOrNull(v.asDouble()))
        case v if v.isString                         => Success(Json.fromString(v.asString()))
        case v if v.hasArrayElements =>
          Vector.tabulate(v.getArraySize.toInt)(v.getArrayElement(_)).traverse(_.toJson).map(Json.fromValues)
        case v if v.hasMembers =>
          v.getMemberKeys.asScala.map(k => k -> v.getMember(k)).toMap.traverse(_.toJson).map(Json.fromFields)
        case v if v.isException => Try(v.throwException()).asInstanceOf[Try[Nothing]]
        case other => Failure(new IllegalArgumentException(s"Unsupported value: ${printValueFlags(other)}"))
      }
  }

  private def printValueFlags(value: Value): String =
    Map(
      "isBoolean"         -> value.isBoolean,
      "isDate"            -> value.isDate,
      "isDuration"        -> value.isDuration,
      "isException"       -> value.isException,
      "isHostObject"      -> value.isHostObject,
      "isInstant"         -> value.isInstant,
      "isIterator"        -> value.isIterator,
      "isMetaObject"      -> value.isMetaObject,
      "isNativePointer"   -> value.isNativePointer,
      "isNull"            -> value.isNull,
      "isNumber"          -> value.isNumber,
      "isProxyObject"     -> value.isProxyObject,
      "isString"          -> value.isString,
      "isTime"            -> value.isTime,
      "isTimeZone"        -> value.isTimeZone,
      "hasArrayElements"  -> value.hasArrayElements,
      "hasBufferElements" -> value.hasBufferElements,
      "hasHashEntries"    -> value.hasHashEntries,
      "hasIterator"       -> value.hasIterator,
      "hasMembers"        -> value.hasMembers,
      "hasMetaParents"    -> value.hasMetaParents
    ).filter(_._2).keySet.mkString("{", ", ", "}")
}
