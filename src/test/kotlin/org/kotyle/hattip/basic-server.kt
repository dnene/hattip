/*
Copyright 2016 Dhananjay Nene

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package org.kotyle.hattip

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.kotyle.kylix.either.Either
import org.kotyle.kylix.either.Either.Left
import org.kotyle.kylix.either.Either.Right
import org.kotyle.kylix.option.Option
import org.kotyle.kylix.option.Option.Some
import org.kotyle.kylix.option.Option.None
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpServer(val port: Int) : NanoHTTPD(port) {
    var params: Map<String,String>? = null
    var headers: Map<String,String>? = null
    init {
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        this.params = session.parms
        this.headers = session.headers
        when(session.method.name.toLowerCase()) {
            "get" -> when(session.uri) {
                "/hello" -> return newFixedLengthResponse("hello")
                "/params" -> return newFixedLengthResponse("params")
                "/secure" -> {
                    if (session.headers.containsKey("authorization")) {
                        val auth = session.headers["authorization"]
                        if (auth.equals("Basic Zm9vOmJhcg==")) {
                            return newFixedLengthResponse("secure")
                        } else {
                            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
                        }
                    } else {
                        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
                    }
                }
                "/returnedHeaders" -> {
                    val response = newFixedLengthResponse("returnedHeaders")
                    response.addHeader("foo", "bar")
                    response.addHeader("baz", "buz")
                    return response
                }
                "/redirectedFrom" -> {
                    val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "Moved Permanently")
                    response.addHeader("Location", "http://localhost:${port}/redirectedTo")
                    return response
                }
                "/redirectedTo" -> return newFixedLengthResponse("redirectedTo")
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
            "post" ->return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
}

class ServerResource(val port: Int): ExternalResource() {
    var server: HttpServer? = null
    override fun before() { server = HttpServer(port)
    }
    override fun after() { server?.stop() }
    fun lastParams() = server?.params
    fun lastHeaders() = server?.headers
}

fun ByteArray.equalsArray(other: ByteArray) = Arrays.equals(this, other)

fun assertResponseIsSuccess(data: String, result: Either<*, Response>) =
    assertTrue(result is Right && 200 == result.value.code && data.toByteArray().equalsArray(result.value.data))

fun assertResponseIsFailure(code: Option<Int>, result: Either<HttpError,*>) =
    assertTrue(result is Left && code == result.value.code)

class SimpleTest {
    companion object {
        val port = 9898
        @ClassRule @JvmField
        val resource = ServerResource(port)
    }

    @Test
    fun helloPageGet() {
        val result = "http://localhost:${port}/hello".http().perform()
        assertResponseIsSuccess("hello", result)
    }

    @Test
    fun nonexistentPageGet() {
        val result = "http://localhost:${port}/doesNotExist".http().perform()
        assertResponseIsFailure(Some(404), result)
    }

    @Test
    fun paramsGet() {
        val foo = "http://localhost:${port}/params?a=b&c=d".http()
        val result = foo.perform()
        assertResponseIsSuccess("params", result)
        val lastParams = resource.lastParams()
        assert(lastParams?.size == 2)
        assert(lastParams?.contains("a") ?: false)
        assert(lastParams?.contains("c") ?: false)
        assert(lastParams?.get("a").equals("b"))
        assert(lastParams?.get("c").equals("d"))
    }

    @Test
    fun paramsGet2() {
        val result = "http://localhost:${port}/params".http().withParams("a" to "b", "c" to "d").perform()
        assertResponseIsSuccess("params", result)
        val lastParams = resource.lastParams()
        assert(lastParams?.size == 2)
        assert(lastParams?.contains("a") ?: false)
        assert(lastParams?.contains("c") ?: false)
        assert(lastParams?.get("a").equals("b"))
        assert(lastParams?.get("c").equals("d"))
    }

    @Test
    fun setHeaders() {
        val result = "http://localhost:${port}/params".http().
                withParams("a" to "b", "c" to "d").
                withHeaders("foo" to "bar", "baz" to "buz").perform()
        assertResponseIsSuccess("params", result)
        val lastParams = resource.lastParams()
        val lastHeaders = resource.lastHeaders()
        assert(lastParams?.size == 2)
        assert(lastParams?.contains("a") ?: false)
        assert(lastParams?.contains("c") ?: false)
        assert(lastParams?.get("a").equals("b"))
        assert(lastParams?.get("c").equals("d"))

        assert(lastHeaders != null && lastHeaders.size > 2)
        assert(lastHeaders?.contains("foo") ?: false)
        assert(lastHeaders?.contains("baz") ?: false)
        assert(lastHeaders?.get("foo").equals("bar"))
        assert(lastHeaders?.get("baz").equals("buz"))
    }

    @Test
    fun setBasicAuthentication() {
        val result = "http://localhost:${port}/secure".http().
                withParams("a" to "b", "c" to "d").
                withHeaders("foo" to "bar", "baz" to "buz").
                withCredentials(Credentials("foo", "bar", None)).perform()
        assertResponseIsSuccess("secure", result)

        val lastParams = resource.lastParams()
        val lastHeaders = resource.lastHeaders()
        assert(lastParams?.size == 2)
        assert(lastParams?.contains("a") ?: false)
        assert(lastParams?.contains("c") ?: false)
        assert(lastParams?.get("a").equals("b"))
        assert(lastParams?.get("c").equals("d"))

        assert(lastHeaders != null && lastHeaders.size > 2)
        assert(lastHeaders?.contains("foo") ?: false)
        assert(lastHeaders?.contains("baz") ?: false)
        assert(lastHeaders?.get("foo").equals("bar"))
        assert(lastHeaders?.get("baz").equals("buz"))
    }

    @Test
    fun failedAuthentication() {
        val result = "http://localhost:${port}/secure".http().
                withParams("a" to "b", "c" to "d").
                withHeaders("foo" to "bar", "baz" to "buz").
                withCredentials(Credentials("foo", "baz", None)).perform()
        assertResponseIsFailure(Some(401), result)
    }

    @Test
    fun missingAuthentication() {
        val result = "http://localhost:${port}/secure".http().
                withParams("a" to "b", "c" to "d").
                withHeaders("foo" to "bar", "baz" to "buz").perform()
        assertResponseIsFailure(Some(401), result)
    }

    @Test
    fun returnedHeaders() {
        val result = "http://localhost:${port}/returnedHeaders".http().perform()
        assertResponseIsSuccess("returnedHeaders", result)
        if (result is Right) {
            assert(result.value.headers.containsKey("foo"))
            assert(result.value.headers.containsKey("baz"))
            assert(result.value.headers["foo"]?.get(0).equals("bar"))
            assert(result.value.headers["baz"]?.get(0).equals("buz"))
        }
    }

    @Test
    fun redirection() {
        val result = "http://localhost:${port}/redirectedFrom".http().perform()
        assertResponseIsSuccess("redirectedTo", result)
    }

    @Test
    fun testOnGet() {
        assertEquals(200, "http://localhost:${port}/hello".http().perform({it.code}, {it.code}))
        assertEquals(Some(404), "http://localhost:${port}/doesNotExist".http().perform({it.code}, {it.code}))
    }

    @Test
    fun callable() {
        "http://localhost:${port}/hello".http().asCallable().call()
    }

    @Test
    fun async() {
        var helloCount = 0
        var notExistsCount = 0
        val executor = Executors.newSingleThreadExecutor()
        "http://localhost:${port}/hello".http().perform(executor, { helloCount-- }, { helloCount++ })
        "http://localhost:${port}/doesNotExist".http().perform(executor, { notExistsCount-- }, { notExistsCount++ } )
        TimeUnit.SECONDS.sleep(2)
        executor.shutdownNow()
        assertEquals(1,helloCount)
        assertEquals(-1,notExistsCount)
    }

    @Test
    fun expectations() {
        fun headerMatches(name: String, value: String, err: String): (Response) -> Option<HttpError> {
            return fun (r: Response):  Option<HttpError> {
                return if (!r.headers.contains(name) ||
                        r.headers[name]!!.size != 1 ||
                        !r.headers[name]!![0].equals(value)) Some(HttpError(Some(r.code), err, None)) else None
            }
        }
        val matchingExpectations = listOf<(Response) -> Option<HttpError>> (
                headerMatches("Content-Length", "6", "content-length-mismatch"),
                headerMatches("Content-Type","text/html", "content-type-mismatch")
            )
        val nonMatchingExpectations = listOf<(Response) -> Option<HttpError>> (
                headerMatches("Content-Length", "4", "content-length-mismatch"),
                headerMatches("Content-Type","text/json", "content-type-mismatch")
        )

        assertEquals(200, "http://localhost:${port}/params?a=b&c=d".http().withExpectations(matchingExpectations).perform({it.code}, {it.code}))
        val result = "http://localhost:${port}/params?a=b&c=d".http().withExpectations(nonMatchingExpectations).perform()
        assert(result is Left && result.value is HttpError && result.value.message.equals("content-length-mismatch"))
    }
}