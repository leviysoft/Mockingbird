package ru.tinkoff.tcb.mockingbird.api.request

import io.circe.Decoder
import io.circe.Encoder
import sttp.tapir.Schema

import ru.tinkoff.tcb.utils.xml.XMLString
import ru.tinkoff.tcb.xpath.SXpath

final case class XPathTestRequest(xml: XMLString.Type, path: SXpath) derives Decoder, Encoder, Schema
