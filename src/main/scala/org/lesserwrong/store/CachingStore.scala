package org.lesserwrong.store

import com.github.blemale.scaffeine.Scaffeine
import org.lesserwrong.model.Post.Id
import org.lesserwrong.model.User.Name
import org.lesserwrong.model.{Content, Post, PostTree, Vote}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.async.Async._

/** Queries a cache, writes through to the backing store. Updates are not reflected in reads until the cache expires.
  *
  * A more efficient implementation would store one cache entry per top level post and have a mapping of post Ids to
  * (sub) trees stored in that entry.
  */
class CachingStore(backing: Store, refreshAfter: FiniteDuration = 500.millis)(implicit ec: ExecutionContext) extends Store {
  private val cache = Scaffeine().refreshAfterWrite(refreshAfter).buildAsyncFuture[Post.Id, Option[PostTree]](backing.getPostTree)

  override def getPostTree(id: Id): Future[Option[PostTree]] = cache.get(id)

  override def getPost(id: Id): Future[Option[Post]] = async {
    await(cache.get(id)).map(_.post)
  }

  override def addPost(post: Post): Future[Unit] = backing.addPost(post)
  override def updatePost(id: Id, newContent: Content): Future[Unit] = backing.updatePost(id, newContent)

  override def getVote(user: Name, postId: Id): Future[Option[Vote]] = backing.getVote(user, postId)
  override def putVote(vote: Vote): Future[Unit] = backing.putVote(vote)
  override def removeVote(vote: Vote): Future[Unit] = backing.removeVote(vote)
}
