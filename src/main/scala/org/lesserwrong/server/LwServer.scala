package org.lesserwrong.server

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.ExceptionHandler
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.typesafe.config.ConfigFactory
import org.lesserwrong.model._
import org.lesserwrong.store._
import spray.json.{DefaultJsonProtocol, DeserializationException, JsString, JsValue, JsonFormat, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}
import scala.async.Async._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  def via[A, B](to: A => B, from: B => A)(implicit aformat: JsonFormat[A]): JsonFormat[B] =
    new JsonFormat[B] {
      override def write(obj: B) = aformat.write(from(obj))
      override def read(json: JsValue) = to(aformat.read(json))
    }

  // Like `lazyFormat` but with RootJsonFormat type
  def lazyRootFormat[T](format: => RootJsonFormat[T]) = new RootJsonFormat[T] {
    lazy val delegate = format
    def write(x: T) = delegate.write(x)
    def read(value: JsValue) = delegate.read(value)
  }

  implicit val uuidFormat = via[String, UUID](UUID.fromString, _.toString)
  implicit val instantFormat = via[Long, Instant](Instant.ofEpochMilli, _.toEpochMilli)
  implicit val contentFormat = jsonFormat2(Content)
  implicit val postFormat = jsonFormat7(Post.apply)
  implicit val treeFormat: RootJsonFormat[PostTree] = lazyRootFormat(jsonFormat2(PostTree))
  implicit val voteFormat = jsonFormat3(Vote)

  implicit val uuidUnmarshaller = Unmarshaller.strict(UUID.fromString)
}

case class HttpError(status: StatusCode, content: ResponseEntity = HttpEntity.Empty) extends Exception

class LwServer(interface: String, port: Option[Int],
               store: Store)(implicit val system: ActorSystem) extends JsonSupport {
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val materializer = ActorMaterializer()

  import system.log

  implicit val exceptionHandler = ExceptionHandler {
    case HttpError(status, content) => complete(HttpResponse(status, Nil, content))
    case PostNotFoundException(postId) => complete(HttpResponse(NotFound))
    case VoteNotFoundException(postId) => complete(HttpResponse(NotFound))
    case PostAlreadyExistsException(id) => complete(HttpResponse(Conflict, entity = s"Post with id $id already exists"))
  }

  val route =
    path("posts") {
      parameters('id.as[Post.Id]) { id =>
        // Get post
        get {
          parameter('tree.as[Boolean].?) { tree =>
            complete {
              async {
                if (tree.getOrElse(false)) {
                  await(store.getPostTree(id)) match {
                    case Some(tree) => tree: ToResponseMarshallable
                    case None => NotFound: ToResponseMarshallable
                  }
                }
                else {
                  await(store.getPost(id)) match {
                    case Some(post) => post: ToResponseMarshallable
                    case None => NotFound: ToResponseMarshallable
                  }
                }
              }
            }
          }
        } ~
          // Create post
          put {
            entity(as[Post]) { post =>
              complete {
                async {
                  await(store.addPost(post))
                  Created
                }
              }
            }
          } ~
          // Update post content
          post {
            entity(as[Content]) { content =>
              complete {
                async {
                  await(store.updatePost(id, content))
                  OK
                } recover {
                  case PostNotFoundException(id) => NotFound
                }
              }
            }
          }
      }
    } ~
      path("votes") {
        parameters('user.as[User.Name], 'postId.as[Post.Id]) { (user, postId) =>
          // Get vote. Returns 1 or -1, or 0 if not voted.
          get {
            complete {
              async {
                await(store.getVote(user, postId)) match {
                  case Some(vote) => vote.value.toString
                  case None => 0.toString
                }
              }
            }
          } ~
            // Add or change vote
            post {
              parameter('up.as[Boolean]) { up =>
                complete {
                  async {
                    await(store.putVote(Vote(user, postId, up)))
                    OK
                  }
                }
              }
            } ~
            // Unvote
            delete {
              parameter('up.as[Boolean]) { up =>
                complete {
                  async {
                    await(store.removeVote(Vote(user, postId, up)))
                    OK
                  }
                }
              }
            }
        }
      }

  lazy val startServer = async {
    val binding = await(Http().bindAndHandle(route, interface, port.getOrElse(-1)))
    log.info(s"Server listening at $binding")
    binding
  }

  def close(): Future[Unit] = async {
    await(await(startServer).unbind())
    log.info(s"Server shut down")
  }
}

object LwServer {
  def apply() = {
    val config = ConfigFactory.load()
    val interface = config.getString("server.interface")
    val system = ActorSystem("LwServer")

    val store = new CachingStore(SqlStore)(system.dispatcher)

    new LwServer(interface, None, store)(system)
  }
}
