package org.lesserwrong.store

import java.sql.{JDBCType, Timestamp}
import java.time.Instant
import java.util.UUID

import org.lesserwrong.model.Post.Id
import org.lesserwrong.model.User.Name
import org.lesserwrong.model._
import org.postgresql.util.PSQLException
import slick.jdbc.{GetResult, JdbcBackend, PositionedParameters, SetParameter}
import slick.driver.PostgresDriver.api._

import scala.concurrent.{ExecutionContext, Future}
import scala.async.Async._

class DbPost(tag: Tag) extends Table[Post](tag, "posts") {
  implicit def instantType = Schema.instantType

  def id = column[Post.Id]("id", O.PrimaryKey)
  def parentId = column[Option[Post.Id]]("parent_id")
  def author = column[User.Name]("author")
  def created = column[Instant]("created")
  def modified = column[Instant]("modified")
  def value = column[String]("value")
  def mediaType = column[String]("media_type")
  def votes = column[Int]("votes")

  def * = (id, parentId, author, created, modified, value, mediaType, votes).shaped.<>(
    { case (id, parentId, author, created, modified, value, mediaType, votes) => Post(id, parentId, author, created, modified, Content(value, mediaType), votes) },
    (post: Post) => Some((post.id, post.parentId, post.author, post.created, post.modified, post.content.value, post.content.mediaType, post.votes))
  )

  def parent = foreignKey("_parent", parentId, Schema.posts)(_.id)
}

class DbVote(tag: Tag) extends Table[Vote](tag, "votes") {
  def user = column[User.Name]("user")
  def postId = column[Post.Id]("post_id")
  def up = column[Boolean]("up")

  def * = (user, postId, up) <> (Vote.tupled, Vote.unapply)

  def pk = primaryKey("_pk", (user, postId))
  def post = foreignKey("_post", postId, Schema.posts)(_.id, onDelete = ForeignKeyAction.Cascade)
}

object Schema {
  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val instantType = MappedColumnType.base[Instant, Timestamp](
    i => new Timestamp(i.toEpochMilli),
    t => Instant.ofEpochMilli(t.getTime)
  )

  lazy val db = JdbcBackend.Database.forConfig("db")

  lazy val posts = TableQuery[DbPost]
  lazy val votes = TableQuery[DbVote]

  lazy val schema = posts.schema ++ votes.schema

  def initSchema(): Future[Unit] = db.run(schema.create)
  def dropSchema(): Future[Unit] = db.run(schema.drop)
}

object PostgresErrorCodes {

  object ForeignKeyViolation {
    def unapply(e: PSQLException): Boolean = e.getSQLState == "23503"
  }

  object UniqueViolation {
    def unapply(e: PSQLException): Boolean = e.getSQLState == "23505"
  }

}

object SqlStore extends Store {

  import Schema._
  import PostgresErrorCodes._

  override def getPost(id: Post.Id): Future[Option[Post]] = db.run(posts.filter(_.id === id).result.headOption)

  override def addPost(post: Post): Future[Unit] = async {
    await(db.run(posts += post))
    ()
  } recover {
    case UniqueViolation() => throw PostAlreadyExistsException(post.id)
    case ForeignKeyViolation() => throw new IllegalArgumentException(s"Bad parentId ${post.parentId}")
  }

  override def updatePost(id: Post.Id, newContent: Content): Future[Unit] = async {
    val updated = await(db.run(posts.map(p => (p.value, p.mediaType, p.modified)).update((newContent.value, newContent.mediaType, Instant.now))))
    if (updated == 0) throw PostNotFoundException(id)
  }

  private implicit val setUuid = new SetParameter[UUID] {
    def apply(v: UUID, pp: PositionedParameters): Unit = {
      pp.setObject(v, JDBCType.BINARY.getVendorTypeNumber)
    }
  }

  private implicit val getUuid = GetResult(r => r.nextObject().asInstanceOf[UUID])
  private implicit val getUuidOption = GetResult(r => r.nextObjectOption().map(_.asInstanceOf[UUID]))
  private implicit val getInstant = GetResult(r => Instant.ofEpochMilli(r.nextTimestamp().getTime))

  private implicit val toPost = GetResult(r => Post(r.<<, r.<<, r.<<, r.<<, r.<<, Content(r.<<, r.<<), r.<<))

  override def getPostTree(id: Post.Id): Future[Option[PostTree]] = async {
    val treeQuery =
      sql"""with recursive children as (
              select * from posts where id = $id
              union all
              select p.* from posts p, children c
              where p.parent_id = c.id
            )
            select * from children;
      """.as[Post]
    val posts = await(db.run(treeQuery))
    if (posts.isEmpty) None
    else {
      val byId = posts.map(p => p.id -> p).toMap
      val byParent = posts.groupBy(_.parentId)

      def tree(id: Post.Id): PostTree = PostTree(byId(id), byParent.getOrElse(Some(id), Vector.empty).map(p => tree(p.id)))
      Some(tree(id))
    }
  }

  override def getVote(user: Name, postId: Post.Id): Future[Option[Vote]] =
    db.run(votes.filter(v => v.user === user && v.postId === postId).result.headOption)

  override def putVote(vote: Vote): Future[Unit] = async {
    val updateVotes =
      sqlu"""with diff as (
                select (
                  case
                    when v.user is not null and v.up = ${vote.up} then 0
                    when v.user is not null and v.up and not ${vote.up} then -2
                    when v.user is not null and not v.up and ${vote.up} then 2
                    when v.user is null and ${vote.up} then 1
                    when v.user is null and not ${vote.up} then -1
                  end
                ) as diff
                from posts p
                left join votes v on p.id = v.post_id and v.user = ${vote.user}
                where p.id = ${vote.postId}
             )

             update posts p
             set votes = p.votes + d.diff
             from diff d
             where p.id = ${vote.postId}
        """

    // Can't use `votes.insertOrUpdate(vote)` because of Slick bug #966
    val upsertVote =
      sqlu"""insert into votes values (${vote.user}, ${vote.postId}, ${vote.up})
             on conflict on constraint _pk do update set up = ${vote.up}
          """

    await(db.run(
      DBIO.seq(
        updateVotes,
        upsertVote
      ).transactionally
    ))
  }

  override def removeVote(vote: Vote): Future[Unit] = async {
    val updateVotes =
      sqlu"""update posts p
             set votes = p.votes +
               case
                 when v.user is not null and v.up then -1
                 when v.user is not null and not v.up then 1
                 else 0
               end
             from votes v
             where p.id = ${vote.postId} and p.id = v.post_id and v.user = ${vote.user};
             """

    val count = await(db.run(
      updateVotes.andThen(
        votes.filter(v => v.user === vote.user && v.postId === vote.postId).delete
      ).transactionally
    ))

    if (count == 0) throw VoteNotFoundException(vote)
  }
}
