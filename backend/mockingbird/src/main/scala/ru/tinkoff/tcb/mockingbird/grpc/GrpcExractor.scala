package ru.tinkoff.tcb.mockingbird.grpc

import scala.jdk.CollectionConverters.*

import com.github.os72.protobuf.dynamic.DynamicSchema
import com.github.os72.protobuf.dynamic.EnumDefinition
import com.github.os72.protobuf.dynamic.MessageDefinition
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.DynamicMessage
import com.google.protobuf.util.JsonFormat
import io.circe.Json
import io.circe.parser.*
import mouse.boolean.*
import mouse.ignore
import neotype.*
import org.apache.commons.io.output.ByteArrayOutputStream

import ru.tinkoff.tcb.mockingbird.model.*

object GrpcExractor {

  val primitiveTypes = Map(
    "TYPE_DOUBLE"   -> "double",
    "TYPE_FLOAT"    -> "float",
    "TYPE_INT32"    -> "int32",
    "TYPE_INT64"    -> "int64",
    "TYPE_UINT32"   -> "uint32",
    "TYPE_UINT64"   -> "uint64",
    "TYPE_SINT32"   -> "sint32",
    "TYPE_SINT64"   -> "sint64",
    "TYPE_FIXED32"  -> "fixed32",
    "TYPE_FIXED64"  -> "fixed64",
    "TYPE_SFIXED32" -> "sfixed32",
    "TYPE_SFIXED64" -> "sfixed64",
    "TYPE_BOOL"     -> "bool",
    "TYPE_STRING"   -> "string",
    "TYPE_BYTES"    -> "bytes"
  )

  def addSchemaToRegistry(schema: GrpcRootMessage, registry: DynamicSchema.Builder): Unit =
    schema match {
      case m: GrpcMessageSchema =>
        ignore(registry.addMessageDefinition(buildMessageDefinition(m)))
      case m: GrpcEnumSchema =>
        ignore(registry.addEnumDefinition(buildEnumDefinition(m)))
    }

  def buildMessageDefinition(gm: GrpcMessageSchema): MessageDefinition = {
    val builder = MessageDefinition.newBuilder(gm.name)

    gm.oneofs.getOrElse(List.empty).foreach { oneof =>
      val oneOfBuilder = builder.addOneof(oneof.name)
      oneof.options.foreach { of =>
        oneOfBuilder.addField(of.typeName, of.name, of.order)
      }
    }

    gm.fields.foreach {
      case f if f.isProto3Optional.getOrElse(false) =>
        val oneOfBuilder = builder.addOneof(s"_${f.name}")
        oneOfBuilder.addField(f.typeName, f.name, f.order)
      case f =>
        builder.addField(f.label.entryName, f.typeName, f.name, f.order)
    }

    gm.nested
      .getOrElse(List.empty)
      .foreach(
        buildMessageDefinition andThen builder.addMessageDefinition
      )

    gm.nestedEnums
      .getOrElse(List.empty)
      .foreach(
        buildEnumDefinition andThen builder.addEnumDefinition
      )

    builder.build()
  }

  def buildEnumDefinition(ge: GrpcEnumSchema): EnumDefinition =
    ge.values
      .foldLeft(EnumDefinition.newBuilder(ge.name)) { case (builder, (name, number)) =>
        builder.addValue(name.unwrap, number.unwrap)
      }
      .build()

  private val jsonPrinter = JsonFormat.printer().preservingProtoFieldNames().includingDefaultValueFields()

  extension (definition: GrpcProtoDefinition) {
    def toDynamicSchema: DynamicSchema = {
      val registryBuilder: DynamicSchema.Builder = DynamicSchema.newBuilder()
      val messageSchemas                         = definition.schemas
      registryBuilder.setName(definition.name)
      definition.`package`.foreach(registryBuilder.setPackage)
      messageSchemas.foreach(addSchemaToRegistry(_, registryBuilder))
      registryBuilder.build()
    }

    def parseFrom(bytes: Array[Byte], className: String): DynamicMessage =
      DynamicMessage.parseFrom(toDynamicSchema.getMessageDescriptor(className), bytes)

    def parseFromJson(response: Json, className: String): Array[Byte] = {
      val schema     = toDynamicSchema
      val msgBuilder = schema.newMessageBuilder(className)
      JsonFormat.parser().merge(response.spaces4, msgBuilder)
      val message      = msgBuilder.build()
      val outputStream = new ByteArrayOutputStream()
      message.writeTo(outputStream)
      outputStream.toByteArray
    }

    def convertMessageToJson(bytes: Array[Byte], className: String): Task[Json] =
      for {
        message    <- ZIO.attempt(parseFrom(bytes, className))
        jsonString <- ZIO.attempt(jsonPrinter.print(message))
        js         <- ZIO.fromEither(parse(jsonString))
      } yield js
  }

  extension (dynamicSchema: DynamicSchema) {
    def toGrpcProtoDefinition: GrpcProtoDefinition = {
      val descriptor: DescriptorProtos.FileDescriptorProto = dynamicSchema.getFileDescriptorSet.getFile(0)
      val namespace                                        = descriptor.hasPackage.option(descriptor.getPackage)
      val enums = descriptor.getEnumTypeList.asScala.map(enum2enumScheme).toList
      val messages = descriptor.getMessageTypeList.asScala
        .filter(!_.getOptions.getMapEntry)
        .map(message2messageSchema)
        .toList
      GrpcProtoDefinition(
        descriptor.getName,
        enums ++ messages,
        namespace
      )
    }
  }

  private def enum2enumScheme(enumProto: DescriptorProtos.EnumDescriptorProto): GrpcEnumSchema =
    GrpcEnumSchema(
      enumProto.getName,
      enumProto.getValueList.asScala.map(i => (FieldName(i.getName), FieldNumber(i.getNumber))).toMap
    )

  private def message2messageSchema(message: DescriptorProtos.DescriptorProto): GrpcMessageSchema = {
    val oneOfFields = message.getOneofDeclList.asScala.map(_.getName).toSet

    val (fields, oneofs) = message.getFieldList.asScala.toList
      .partition(f => !f.hasOneofIndex || isProto3OptionalField(f, oneOfFields))

    val nestedEnums = message.getEnumTypeList().asScala.toList
    val nested      = message.getNestedTypeList.asScala.toList

    GrpcMessageSchema(
      message.getName,
      fields
        .map { field =>
          val label = GrpcLabel.withValue(field.getLabel.toString.split("_").last.toLowerCase)
          getGrpcField(field, label, isProto3OptionalField(field, oneOfFields))
        },
      oneofs
        .groupMap(_.getOneofIndex) { field =>
          getGrpcField(field, GrpcLabel.Optional, false)
        }
        .map { case (index, fields) =>
          GrpcOneOfSchema(
            message.getOneofDeclList.asScala(index).getName,
            fields
          )
        }
        .toList match {
        case Nil  => None
        case list => Some(list)
      },
      nested
        .map(message2messageSchema) match {
        case Nil  => None
        case list => Some(list)
      },
      nestedEnums.map(enum2enumScheme) match {
        case Nil  => None
        case list => Some(list)
      }
    )
  }

  private def isProto3OptionalField(field: DescriptorProtos.FieldDescriptorProto, oneOfFields: Set[String]): Boolean =
    GrpcLabel.withValue(field.getLabel.toString.split("_").last.toLowerCase) == GrpcLabel.Optional &&
      oneOfFields(s"_${field.getName}")

  private def getGrpcField(
      field: DescriptorProtos.FieldDescriptorProto,
      label: GrpcLabel,
      isProto3Optional: Boolean
  ): GrpcField = {
    val grpcType = getGrpcType(field)
    GrpcField(
      grpcType,
      label,
      getFieldType(field, grpcType == GrpcType.Custom),
      field.getName,
      field.getNumber,
      isProto3Optional.some,
    )
  }

  private def getGrpcType(field: DescriptorProtos.FieldDescriptorProto): GrpcType =
    if (!primitiveTypes.isDefinedAt(field.getType.name()) || field.getTypeName != "") GrpcType.Custom
    else GrpcType.Primitive

  private def getFieldType(field: DescriptorProtos.FieldDescriptorProto, custom: Boolean): String =
    if (custom) field.getTypeName
    else primitiveTypes(field.getType.name())
}
