package ru.tinkoff.tcb.mockingbird.api.response

import io.circe.Decoder
import io.circe.Encoder
import sttp.tapir.Schema

final case class OperationResult[T](status: String, id: Option[T] = None) derives Decoder, Encoder, Schema

object OperationResult {
  def apply[T](status: String, id: T): OperationResult[T] = OperationResult(status, Some(id))
}
