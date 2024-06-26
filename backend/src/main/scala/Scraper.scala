import Connection.db
import DataBaseRw.{Games, gamesQuery}
import Steam.SteamApi.getSteamAppDetails
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.actor.typed.scaladsl.Behaviors
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import spray.json._


object Scraper extends App {

  case class SteamGame(appid: Long, name: String)

  implicit object SteamGameFormat extends RootJsonReader[Vector[SteamGame]] {
    def read(json: JsValue): Vector[SteamGame] =
      json.asJsObject.fields("applist").asJsObject.fields("apps") match {
      case JsArray(vector) => vector.map(_.asJsObject.getFields("appid", "name") match {
          case Seq(JsNumber(appId), JsString(name)) => SteamGame(appId.toLong, name)
          case _ => throw new Exception("Wrong formats")
        }
      )
      case _ => throw new Exception("Vector expected")
    }
  }


  implicit val system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "SingleRequest")
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  val getApps: Future[HttpResponse] = Http().singleRequest(
    HttpRequest(uri = "https://api.steampowered.com/ISteamApps/GetAppList/v2/")
  )
  val timeout = 1000.millis

  val apps = getApps.flatMap(res => res.entity.toStrict(timeout))
    .map(x => x.data.utf8String.parseJson.convertTo[Vector[SteamGame]])


  val addToDb2 = apps.flatMap(processSeq1)

  def processSeq1(vector: Vector[SteamGame]): Future[Int] = {
    def inner(acc: Int, tail: Vector[SteamGame]): Future[Int] = tail match {
      case Vector() => Future.successful(acc)
      case head +: tail => processItem(head).flatMap(_ => inner(acc + 1, tail))
    }

    inner(0, vector)
  }

  def processSeq2(vector: Vector[SteamGame]): Future[Int] = vector match {
    case head +: tail => processItem(head).flatMap(_ => processSeq2(tail))
  }

  def processItem(steamGame: SteamGame): Future[Int] = {
    println(s"Processing steam game(${steamGame.appid}) ${steamGame.name}")
    db.run(gamesQuery.filter(_.steamId === steamGame.appid).result.headOption).flatMap {
      case Some(_) =>
        println(s"Found a game, updating")
        Future.successful(1)
      case None =>
        println(s"Not found, creating")
        getSteamAppDetails(steamGame.appid).flatMap {
          case Some(response) =>
          db.run(
            gamesQuery += Games(
              id = 0L,
              name = steamGame.name,
              steamId = steamGame.appid,
              capsuleImageV5 = response.headerImage
            )
          )
          case None => Future.successful(1)
        }
    }
  }


  addToDb2.onComplete {
    case Success(res) =>
      println(res)
    case Failure(res) =>
      println(res)
  }
}



object TestDb extends App {
  implicit val system: ActorSystem[Any] = ActorSystem(Behaviors.empty, "SingleRequest")
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  val test = db.run(gamesQuery.filter(_.steamId === 19034806546L).result.headOption)

  test.onComplete {
    case Success(res) =>
      println(res)
    case Failure(res) =>
      println(res)
  }
}
