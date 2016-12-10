package org.lesserwrong.store

import org.lesserwrong.model._

import scala.concurrent.Future

/** All getters that return an Option return None if the post is not found. */
trait Store {
  def getPost(id: Post.Id): Future[Option[Post]]

  /** Fails if post with this ID already exists */
  def addPost(post: Post): Future[Unit]

  /** Changes the content of an existing post. The old version may be stored but won't be directly accessible.
    *
    * Fails if trying to create new post, or to modify an immutable prop of an existing post
    * (author, created timestamp, parent post id, etc).
    */
  def updatePost(id: Post.Id, newContent: Content): Future[Unit]

  def getPostTree(id: Post.Id): Future[Option[PostTree]]

  def getVote(user: User.Name, postId: Post.Id): Future[Option[Vote]]

  /** Add vote, or replace existing vote by the same user. */
  def putVote(vote: Vote): Future[Unit]
  def removeVote(vote: Vote): Future[Unit]
}

case class PostNotFoundException(id: Post.Id) extends Exception(s"No such post $id")
case class PostAlreadyExistsException(id: Post.Id) extends Exception(s"Post $id already exists")

case class VoteNotFoundException(vote: Vote) extends Exception(s"No such vote $vote")
