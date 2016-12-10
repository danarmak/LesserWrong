package org.lesserwrong.model

import java.time.Instant
import java.util.UUID

object User {
  type Name = String
}

case class MediaType(value: String) {
  require(value.count(_ == '/') == 1, "Must contain exactly one forward slash")

  def main = value.take(value.indexOf('/'))
  def sub = value.drop(value.indexOf('/') + 1)
}

/** Post content. The `value` is the exact data input by the user. Any rendering happens clientside. */
case class Content(value: String, mediaType: String = "text/markdown") {
  require(mediaType.count(_ == '/') == 1, "Must contain exactly one forward slash")
}

object Content {
  implicit val writer = upickle.default.macroW[Content]
}

case class Post(id: Post.Id, parentId: Option[Post.Id] = None, author: User.Name, created: Instant, modified: Instant,
                content: Content, votes: Int) {
  def isComment = parentId.isDefined
}

object Post {
  /** Not Int, to allow code to create new IDs without consulting a single locking authority */
  type Id = UUID
}

case class Vote(user: User.Name, postId: Post.Id, up: Boolean) {
  def value = if (up) 1 else -1
}

/** A tree of inline comments and votes, rooted at a comment or a top level post. */
case class PostTree(post: Post, children: Vector[PostTree] = Vector.empty)
