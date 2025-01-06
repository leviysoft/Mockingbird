package ru.tinkoff.tcb.mockingbird.dal

import scala.annotation.implicitNotFound

import com.github.dwickern.macros.NameOf.nameOf
import oolong.bson.given
import oolong.dsl.*
import oolong.mongo.*
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.Indexes.ascending

import ru.tinkoff.tcb.dataaccess.UpdateResult
import ru.tinkoff.tcb.mockingbird.model.Label
import ru.tinkoff.tcb.mongo.DAOBase
import ru.tinkoff.tcb.mongo.MongoDAO
import ru.tinkoff.tcb.utils.id.SID

@implicitNotFound("Could not find an instance of LabelDAO for ${F}")
trait LabelDAO[F[_]] extends MongoDAO[F, Label] {
  def ensureLabels(service: String, labels: Vector[String]): F[UpdateResult]
}

object LabelDAO

class LabelDAOImpl(collection: MongoCollection[BsonDocument]) extends DAOBase[Label](collection) with LabelDAO[Task] {
  def createIndexes: Task[Unit] =
    createIndex(
      ascending(nameOf[Label](_.serviceSuffix)),
    ) *> createIndex(
      ascending(nameOf[Label](_.serviceSuffix), nameOf[Label](_.label)),
      IndexOptions().unique(true)
    )

  override def ensureLabels(service: String, labels: Vector[String]): Task[UpdateResult] =
    if (labels.isEmpty) ZIO.attempt(UpdateResult.empty)
    else {
      for {
        existing <- findChunk(
          query[Label](l => l.serviceSuffix == lift(service) && lift(labels).contains(l.label)),
          0,
          labels.size
        )
        existingLabels = existing.map(_.label).toSet
        labelsToCreate = labels.filterNot(existingLabels)
        inserted <- insertMany(labelsToCreate.map(Label(SID.random[Label], service, _)))
      } yield UpdateResult(inserted)
    }
}

object LabelDAOImpl {
  val live = ZLayer {
    for {
      mc <- ZIO.service[MongoCollection[BsonDocument]]
      sd = new LabelDAOImpl(mc)
      _ <- sd.createIndexes
    } yield sd.asInstanceOf[LabelDAO[Task]]
  }
}
