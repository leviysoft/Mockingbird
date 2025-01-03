package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound
import scala.util.matching.Regex

import oolong.bson.given
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument

import ru.tinkoff.tcb.mockingbird.model.Service
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO

@implicitNotFound("Could not find an instance of ServiceDAO for ${F}")
trait ServiceDAO[F[_]] extends MongoDAO[F, Service] {
  def getServiceFor(path: String): F[Option[Service]]
  def getServiceFor(pattern: Regex): F[Option[Service]]
}

object ServiceDAO

class ServiceDAOImpl(collection: MongoCollection[BsonDocument])
    extends DAOBase[Service](collection)
    with ServiceDAO[Task] {
  override def getServiceFor(path: String): Task[Option[Service]] =
    findById(path.split('/').filter(_.nonEmpty).head)

  override def getServiceFor(pattern: Regex): Task[Option[Service]] =
    findById(pattern.regex.split('/').filter(_.nonEmpty).head)
}

object ServiceDAOImpl {
  val live: URLayer[MongoCollection[BsonDocument], ServiceDAO[Task]] = ZLayer.fromFunction(new ServiceDAOImpl(_))
}
