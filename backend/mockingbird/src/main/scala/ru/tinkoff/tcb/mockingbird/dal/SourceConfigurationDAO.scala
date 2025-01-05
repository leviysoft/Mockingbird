package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Indexes.descending

import ru.tinkoff.tcb.mockingbird.model.SourceConfiguration
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO
import ru.tinkoff.tcb.utils.crypto.AES
import ru.tinkoff.tcb.utils.id.SID

@implicitNotFound("Could not find an instance of SourceConfigurationDAO for ${F}")
trait SourceConfigurationDAO[F[_]] extends MongoDAO[F, SourceConfiguration] {
  def getAll: F[Vector[SourceConfiguration]] = findChunk(BsonDocument(), 0, Int.MaxValue)
  def getAllNames: F[Vector[SID[SourceConfiguration]]]
}

object SourceConfigurationDAO

class SourceConfigurationDAOImpl(collection: MongoCollection[BsonDocument])(implicit aes: AES)
    extends DAOBase[SourceConfiguration](collection)
    with SourceConfigurationDAO[Task] {
  override def getAllNames: Task[Vector[SID[SourceConfiguration]]] = getAll.map(_.map(_.name))

  def createIndexes: Task[Unit] = createIndex(
    ascending(nameOf[SourceConfiguration](_.service))
  ) *>
    createIndex(
      descending(nameOf[SourceConfiguration](_.created))
    )
}

object SourceConfigurationDAOImpl {
  val live: RLayer[MongoCollection[BsonDocument] & AES, SourceConfigurationDAO[Task]] =
    ZLayer {
      for {
        coll      <- ZIO.service[MongoCollection[BsonDocument]]
        given AES <- ZIO.service[AES]
        scd = new SourceConfigurationDAOImpl(coll)
        _ <- scd.createIndexes
      } yield scd
    }
}
