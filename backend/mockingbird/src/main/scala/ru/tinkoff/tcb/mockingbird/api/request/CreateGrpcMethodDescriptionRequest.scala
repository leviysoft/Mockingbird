package ru.tinkoff.tcb.mockingbird.api.request

import derevo.circe.decoder
import derevo.circe.encoder
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import io.circe.refined.*
import sttp.tapir.codec.refined.*
import sttp.tapir.derevo.schema

import ru.tinkoff.tcb.mockingbird.model.Scope
import ru.tinkoff.tcb.mockingbird.model.ByteArray
import ru.tinkoff.tcb.mockingbird.model.GrpcConnectionType

@derive(decoder, encoder, schema)
final case class CreateGrpcMethodDescriptionRequest(
    scope: Scope,
    service: String Refined NonEmpty,
    methodName: String,
    connectionType: GrpcConnectionType,
    requestClass: String,
    requestCodecs: ByteArray,
    responseClass: String,
    responseCodecs: ByteArray
)