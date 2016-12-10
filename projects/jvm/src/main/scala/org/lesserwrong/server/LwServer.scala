package org.lesserwrong.server

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import StatusCodes._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.ExceptionHandler
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.typesafe.config.ConfigFactory
import de.heikoseeberger.akkahttpupickle.UpickleSupport
import org.lesserwrong.model._
import org.lesserwrong.store._
import upickle.Js
import upickle.Js.{Num, Str, Value}
import upickle.default._

import scala.concurrent.{ExecutionContext, Future}
import scala.async.Async._

case class A()
case class Test(id: UUID, parent: Option[UUID] = None)

object OptionUPickler extends upickle.AttributeTagged {
  override implicit def OptionW[T: Writer]: Writer[Option[T]] = Writer {
    case None    => Js.Null
    case Some(s) => implicitly[Writer[T]].write(s)
  }

  override implicit def OptionR[T: Reader]: Reader[Option[T]] = Reader {
    case Js.Null     => None
    case v: Js.Value => Some(implicitly[Reader[T]].read.apply(v))
  }
}

trait JsonSupport extends UpickleSupport {
  implicit val instantReadWriter = ReadWriter[Instant](
    i => Num(i.toEpochMilli),
    { case Num(d) if d.isWhole => Instant.ofEpochMilli(d.toLong) }
  )

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
