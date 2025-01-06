package ru.tinkoff.tcb.mongo

import oolong.bson.BsonDecoder
import oolong.bson.BsonEncoder
import oolong.bson.given
import org.mongodb.scala.bson.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.changestream.ChangeStreamDocument

import ru.tinkoff.tcb.dataaccess.DAO
import ru.tinkoff.tcb.dataaccess.UpdateResult
import ru.tinkoff.tcb.generic.Fields
import ru.tinkoff.tcb.generic.PropSubset
import ru.tinkoff.tcb.generic.RootOptionFields

trait MongoDAO[F[_], T] extends DAO[F, T] {
  override type Query = Bson
  override type Patch = Bson
  override type Sort  = Bson

  def findById[Id: BsonEncoder](id: Id): F[Option[T]] =
    findOne(Document("_id" -> id.bson))

  def findByIds[Id: BsonEncoder](ids: Id*): F[Vector[T]] =
    findChunk(Document("_id" -> Document("$in" -> ids.to(Vector).bson)), 0, ids.length)

  def updateById[Id: BsonEncoder](id: Id, patches: Patch*): F[UpdateResult] =
    update(Document("_id" -> id.bson), patches)

  def deleteById[Id: BsonEncoder](id: Id): F[Long] =
    delete(Document("_id" -> id.bson))

  def upsert[Id: BsonEncoder](appId: Id, create: => T, patches: Patch*): F[UpdateResult]

  // Projections

  def findOneProjection[P: BsonDecoder](
      query: Query
  )(implicit fields: Fields[P], ps: PropSubset[P, T]): F[Option[P]]

  def findChunkProjection[P: BsonDecoder](
      query: Query,
      offset: Int,
      size: Int,
      sort: Iterable[Sort]
  )(implicit fields: Fields[P], ps: PropSubset[P, T]): F[Vector[P]]

  def patch[P: BsonEncoder](patch: P)(implicit rof: RootOptionFields[P], ps: PropSubset[P, T]): F[UpdateResult]

  def patchIf[P: BsonEncoder](query: Query, patch: P)(implicit
      rof: RootOptionFields[P],
      ps: PropSubset[P, T]
  ): F[UpdateResult]

  def subscribe(consumer: ChangeStreamDocument[T] => Unit): Unit

  def createIndex(fields: Sort, options: IndexOptions = IndexOptions()): F[Unit]
}

object MongoDAO {
  def apply[F[_], T](implicit dao: MongoDAO[F, T]): MongoDAO[F, T] = dao
}
