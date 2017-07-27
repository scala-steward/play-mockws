package mockws

import java.net.InetSocketAddress

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.Mockito._
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FunSuite, Matchers}
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.libs.ws.{WSAuthScheme, WSClient, WSResponse, WSSignatureCalculator}
import play.api.mvc.Results._
import play.api.mvc._
import play.api.test.Helpers._
import Helpers._
import play.libs.ws.WSRequest
import play.shaded.ahc.org.asynchttpclient.Response

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Tests that [[MockWS]] simulates a WS client
 */
class MockWSTest extends FunSuite with Matchers with PropertyChecks {

  test("mock WS simulates all HTTP methods") {
    val ws = MockWS {
      case (GET, "/get")       => action { Ok("get ok") }
      case (POST, "/post")     => action { Ok("post ok") }
      case (PUT, "/put")       => action { Ok("put ok") }
      case (DELETE, "/delete") => action { Ok("delete ok") }
      case ("PATCH", "/patch") => action { Ok("patch ok") }
    }

    await(ws.url("/get").get()).body       shouldEqual "get ok"
    await(ws.url("/post").post("")).body   shouldEqual "post ok"
    await(ws.url("/put").put("")).body     shouldEqual "put ok"
    await(ws.url("/delete").delete()).body shouldEqual "delete ok"
    await(ws.url("/patch").patch("")).body shouldEqual "patch ok"
    ws.close()
  }


  test("mock WS simulates the HTTP status code") {
    val ws = MockWS {
      case (GET, "/get200") => action { Ok("") }
      case (GET, "/get201") => action { Created("") }
      case (GET, "/get404") => action { NotFound("") }
    }

    await(ws.url("/get200").get()).status shouldEqual OK
    await(ws.url("/get201").get()).status shouldEqual CREATED
    await(ws.url("/get404").get()).status shouldEqual NOT_FOUND
    ws.close()
  }


  test("mock WS simulates a POST with a JSON payload") {
    val ws = MockWS {
      case (POST, "/") => action { request =>
        Ok((request.body.asJson.get \ "result").as[String])
      }
    }

    val json = Json.parse("""{"result": "OK"}""")

    val response = await(ws.url("/").post(json))
    response.status shouldEqual OK
    response.body shouldEqual "OK"
    ws.close()
  }


  test("mock WS simulates a POST with a JSON payload with a custom content type") {
    val ws = MockWS {
      case (POST, "/") => action(bodyParser.tolerantJson) { request =>
        Ok((request.body \ "result").as[String])
      }
    }

    val json = Json.parse("""{"result": "OK"}""")

    val response = await(ws.url("/").addHttpHeaders(CONTENT_TYPE -> "application/my-json").post(json))
    response.status shouldEqual OK
    response.body shouldEqual "OK"
    ws.close()
  }


  test("mock WS sets the response content type") {
    val ws = MockWS {
      case (GET, "/text") => action { Ok("text") }
      case (GET, "/json") => action { Ok(Json.parse("""{ "type": "json" }""")) }
    }

    val text = await(ws.url("/text").get())
    val json = await(ws.url("/json").get())

    text.header(CONTENT_TYPE) shouldEqual Some("text/plain; charset=utf-8")
    json.header(CONTENT_TYPE) shouldEqual Some("application/json")
    ws.close()
  }


  test("mock WS can produce JSON") {
    val ws = MockWS {
      case (GET, "/json") => action {
        Ok(Json.obj("field" -> "value"))
      }
    }

    val wsResponse = await( ws.url("/json").get() )
    wsResponse.body shouldEqual """{"field":"value"}"""
    (wsResponse.json \ "field").asOpt[String] shouldEqual Some("value")
    ws.close()
  }


  test("mock WS can produce XML") {
    val ws = MockWS {
      case (GET, "/xml") => action {
        Ok(<foo><bar>value</bar></foo>)
      }
    }

    val wsResponse = await( ws.url("/xml").get() )
    wsResponse.body shouldEqual "<foo><bar>value</bar></foo>"
    (wsResponse.xml \ "bar").text shouldEqual "value"
    ws.close()
  }


  test("a call to an unknown route causes an exception") {
    val ws = MockWS {
      case (GET, "/url") => action { Ok("") }
    }

    the [Exception] thrownBy {
      ws.url("/url2").get()
    } should have message "no route defined for GET /url2"

    the [Exception] thrownBy {
      ws.url("/url").delete()
    } should have message "no route defined for DELETE /url"
    ws.close()
  }

  test("mock WS supports custom response content types") {
    val ws = MockWS {
      case (_, _) => action {
        Ok("hello").as("hello/world")
      }
    }

    val wsResponse = await( ws.url("/").get() )
    wsResponse.status shouldEqual OK
    wsResponse.header(CONTENT_TYPE) shouldEqual Some("hello/world")
    wsResponse.body shouldEqual "hello"
    ws.close()
  }

  test("mock WS supports custom request content types") {
    val ws = MockWS {
      case (_, _) => action { request =>
        request.contentType match {
          case Some(ct) => Ok(ct)
          case None => BadRequest("no content type")
        }
      }
    }

    val wsResponse = await( ws.url("/").addHttpHeaders(CONTENT_TYPE -> "hello/world").get)
    wsResponse.status shouldEqual OK
    wsResponse.body shouldEqual "hello/world"
    ws.close()
  }

  test("mock WS supports query parameter") {
    forAll { (q: String, v: String) =>
      whenever(q.nonEmpty) {

        val ws = MockWS {
          case (GET, "/uri") => action { request =>
            request.getQueryString(q).fold[Result](NotFound) {
              id => Ok(id)
            }
          }
        }

        val wsResponse =  await( ws.url("/uri").addQueryStringParameters(q -> v).get)
        wsResponse.status shouldEqual OK
        wsResponse.body shouldEqual v
        ws.close()
      }
    }
  }

  test("mock WS supports varargs passed as immutable Seqs") {
    forAll { (q: String, v: String) =>
      whenever(q.nonEmpty) {

        val ws = MockWS {
          case (GET, "/uri") => action { request =>
            request.getQueryString(q).fold[Result](NotFound) {
              id => Ok(id)
            }
          }
        }

        await( ws.url("/uri").addHttpHeaders(Seq(q -> v): _*).get )
        await( ws.url("/uri").addQueryStringParameters(Seq(q -> v): _*).get )
        ws.close()
      }
    }
  }

  test("mock WS supports method in execute") {
    val ws = MockWS {
      case (GET, "/get")       => action { Ok("get ok") }
      case (POST, "/post")     => action { Ok("post ok") }
      case (PUT, "/put")       => action { Ok("put ok") }
      case (DELETE, "/delete") => action { Ok("delete ok") }
    }
    
    await(ws.url("/get").withMethod("GET").execute()).body       shouldEqual "get ok"
    await(ws.url("/post").withMethod("POST").execute()).body     shouldEqual "post ok"
    await(ws.url("/put").withMethod("PUT").execute()).body       shouldEqual "put ok"
    await(ws.url("/delete").withMethod("DELETE").execute()).body shouldEqual "delete ok"
    ws.close()
  }
  
  test("should not raise NullPointerExceptions on method chaining") {
    val ws = MockWS {
      case (GET, "/get") => action { Ok("get ok") }
    }

    await(ws
      .url("/get")
      .sign(mock(classOf[WSSignatureCalculator]))
      .withVirtualHost("bla")
      .withFollowRedirects(follow = true)
      .withAuth("user", "password", WSAuthScheme.BASIC)
      .withRequestTimeout(10.millis)
      .get()).body shouldEqual "get ok"
    ws.close()
  }

  test("should not raise Exceptions when asking for the used sockets") {
    val ws = MockWS {
      case (GET, "/get") => action { Ok("get ok") }
    }

    val request : Future[WSResponse] = ws
      .url("/get")
      .sign(mock(classOf[WSSignatureCalculator]))
      .withVirtualHost("bla")
      .withFollowRedirects(follow = true)
      .withRequestTimeout(10.millis)
      .get()


    val response : WSResponse = await(request)

    response.body shouldEqual "get ok"

    response.underlying[play.shaded.ahc.org.asynchttpclient.Response].getLocalAddress shouldBe InetSocketAddress.createUnresolved("127.0.0.1", 8383)
    response.underlying[play.shaded.ahc.org.asynchttpclient.Response].getRemoteAddress shouldBe InetSocketAddress.createUnresolved("127.0.0.1", 8384)

    ws.close()
  }

  test("multiple headers with same name should be retained & merged correctly") {
    val ws = MockWS {
      case (GET, "/get") => action {
        req =>
        Ok(req.headers.getAll("v1").zipWithIndex.toString) }
    }
    val request = ws.url("/get")
      .addHttpHeaders(("v1", "first"),("v1", "second"))
    .get()

    val response = await(request)
    response.body shouldEqual "List((first,0), (second,1))"

    ws.close()
  }
}
