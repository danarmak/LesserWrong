package org.lesserwrong.store

import java.time.Instant

import org.lesserwrong.model.Post.Id
import org.lesserwrong.model.User.Name
import org.lesserwrong.model._

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/** Stores all data in memory. Useful for testing, and (one day) for fast access, after adding appropriate in-mem indexes. */
class MemoryStore extends Store {
  private val success = Future.successful(())

  private def notFound(id: Post.Id) = Future.failed(PostNotFoundException(id))

  private def alreadyExists(id: Post.Id) = Future.failed(PostAlreadyExistsException(id))

  private val posts = TrieMap[Post.Id, PostTree]()
  private val votes = TrieMap[Post.Id, TrieMap[User.Name, Vote]]()

  override def getPost(id: Post.Id): Future[Option[Post]] = Future.successful(posts.get(id).map(_.post))

  override def addPost(post: Post): Future[Unit] = {
    posts.putIfAbsent(post.id, PostTree(post)) match {
      case Some(value) => alreadyExists(post.id)
      case None =>
        if (post.parentId.isDefined) addChildPost(PostTree(post))
        success
    }
  }

  @tailrec private def addChildPost(childTree: PostTree): Unit = {
    val id = childTree.post.parentId.get
    val tree = posts(id)
    val newTree = tree.copy(children = tree.children :+ childTree)
    if (! posts.replace(id, tree, newTree)) addChildPost(childTree)
  }

  override def updatePost(id: Post.Id, newContent: Content): Future[Unit] = posts.get(id) match {
    case Some(tree) => Future.successful(updatePost(id, tree, newContent))
    case None => notFound(id)
  }

  @tailrec private def updatePost(id: Post.Id, tree: PostTree, newContent: Content): Unit = {
    val newPost = tree.post.copy(content = newContent, modified = Instant.now)
    val newTree = tree.copy(post = newPost)
    if (!posts.replace(id, tree, newTree)) {
      updatePost(id, posts(id), newContent) // Posts cannot be deleted, so we can use posts.apply
    }
  }

  override def getPostTree(id: Id): Future[Option[PostTree]] = Future.successful(posts.get(id))

  override def getVote(user: Name, postId: Id): Future[Option[Vote]] = Future.successful(votes.get(postId).flatMap(_.get(user)))

  override def putVote(vote: Vote): Future[Unit] = {
    if (!posts.contains(vote.postId)) notFound(vote.postId)
    else Future.successful {
      val postVotes = votes.getOrElseUpdate(vote.postId, new TrieMap[User.Name, Vote])
      val oldVote = postVotes.put(vote.user, vote)
      val diff = vote.value - oldVote.map(_.value).getOrElse(0)
      if (diff != 0) {
        updateVoteTotal(vote.postId, diff)
      }
    }
  }

  @tailrec private def updateVoteTotal(postId: Post.Id, diff: Int): Unit = {
    val tree = posts(postId)
    val newTree = tree.copy(post = tree.post.copy(votes = tree.post.votes + diff))
    if (!posts.replace(postId, tree, newTree)) updateVoteTotal(postId, diff)
  }

  override def removeVote(vote: Vote): Future[Unit] = {
    if (!posts.contains(vote.postId)) notFound(vote.postId)
    else {
      val postVotes = votes.getOrElseUpdate(vote.postId, new TrieMap[User.Name, Vote])
      if (postVotes.remove(vote.user, vote)) success
      else Future.failed(VoteNotFoundException(vote))
    }
  }
}

