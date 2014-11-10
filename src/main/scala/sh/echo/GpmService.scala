package sh.echo

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Success

import spray.client.pipelining._
import spray.http._
import spray.httpx.unmarshalling._
import spray.httpx.SprayJsonSupport._
import spray.json._

object GpmService {

  sealed trait SearchResult

  object SongSearchResult {
    import JsonProtocol._

    implicit def jf = jsonFormat5(SongSearchResult.apply)
  }
  case class SongSearchResult(
      id: String,
      title: String,
      artist: String,
      album: String,
      durationMillis: Int) extends SearchResult {
    override def toString = s"$artist - $title ($album)"
  }

  case class SongNotFound(songId: String) extends Exception(s"Could not resolve song id '$songId'.")

  object AlbumSearchResult {
    import JsonProtocol._

    implicit def jf = jsonFormat3(AlbumSearchResult.apply)
  }
  case class AlbumSearchResult(
    id: String,
    title: String,
    artist: String) extends SearchResult {
    override def toString = s"$artist - $title"
  }

  object AlbumInfo {
    import JsonProtocol._

    implicit def jf = jsonFormat4(AlbumInfo.apply)
  }
  case class AlbumInfo(
    id: String,
    title: String,
    artist: String,
    tracks: List[SongSearchResult])
}
class GpmService(host: String, port: Int) {

  implicit val arf = Actors.system
  import arf.dispatcher
  import GpmService._
  import JsonProtocol._

  val _infoCache: mutable.Map[String, SongSearchResult] = mutable.Map.empty

  def search(query: String): Future[List[SongSearchResult]] = {
    val pipeline: HttpRequest ⇒ Future[List[SongSearchResult]] = (
      sendReceive ~> unmarshal[List[SongSearchResult]]
    )

    pipeline(Get(Uri(s"http://$host:$port/search").withQuery("q" → query))) andThen {
      case Success(searchResults) ⇒
        _infoCache.synchronized {
          searchResults foreach { searchResult ⇒
            _infoCache.update(searchResult.id, searchResult)
          }
        }
    }
  }

  def searchAlbum(query: String): Future[List[AlbumSearchResult]] = {
    val pipeline: HttpRequest ⇒ Future[List[AlbumSearchResult]] = (
      sendReceive ~> unmarshal[List[AlbumSearchResult]]
    )

    pipeline(Get(Uri(s"http://$host:$port/search").withQuery("q" → query, "t" → "album")))
  }

  def unmarshalOption[T: FromResponseUnmarshaller]: HttpResponse ⇒ Option[T] = {
    response ⇒
      if (response.status == StatusCodes.NotFound)
        None
      else
        Option(unmarshal[T](implicitly[FromResponseUnmarshaller[T]])(response))
  }
  def info(id: String): Future[SongSearchResult] = {
    val pipeline: HttpRequest ⇒ Future[Option[SongSearchResult]] = (
      sendReceive ~> unmarshalOption[SongSearchResult]
    )

    _infoCache.get(id) match {
      case Some(cachedResult) ⇒
        Future.successful(cachedResult)
      case None ⇒
        pipeline(Get(Uri(s"http://$host:$port/info/$id"))) map {
          case Some(searchResult) ⇒
            _infoCache.synchronized {
              _infoCache.update(searchResult.id, searchResult)
            }
            searchResult
          case None ⇒ throw SongNotFound(id)
        }
    }
  }
  def albumInfo(id: String): Future[AlbumInfo] = {
    val pipeline: HttpRequest ⇒ Future[Option[AlbumInfo]] = (
      sendReceive ~> unmarshalOption[AlbumInfo]
    )

    pipeline(Get(Uri(s"http://$host:$port/info/$id").withQuery("t" → "album"))) map {
      case Some(albumInfo) ⇒
        albumInfo.tracks foreach { songInfo ⇒
          _infoCache.synchronized {
            _infoCache.update(songInfo.id, songInfo)
          }
        }
        albumInfo
      case None ⇒ throw SongNotFound(id)
    }
  }
}

