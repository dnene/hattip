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

import org.kotyle.kylix.either.Either
import org.kotyle.kylix.option.Option
import org.kotyle.kylix.option.Option.Some
import org.kotyle.kylix.option.Option.None
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

val log = LoggerFactory.getLogger("org.kotyle.hattip")
class HttpError(val code: Option<Int>, val message: String, val cause: Option<Exception> = None)

enum class HttpMethod { Get, Post, Head, Options, Put, Delete, Trace}

class HttpConnection(val request: Request, val con: HttpURLConnection, val expectations: List<(Response) -> Option<HttpError>>) {
    fun response(): Either<HttpError, Response> {
        try {
            val responseCode = con.responseCode
            log.debug("Http Get for ${request.url} returned $responseCode")
            val headerFields = con.headerFields
            val buffer = ByteArrayOutputStream()
            if (responseCode == 200) {
                val input = con.inputStream

                var bytesRead: Int
                val data = ByteArray(16384)

                bytesRead = input.read(data, 0, data.size)
                while (bytesRead != -1) {
                    buffer.write(data, 0, bytesRead);
                    bytesRead = input.read(data, 0, data.size)
                }

                buffer.flush();
                val response = Response(responseCode, con.headerFields, buffer.toByteArray())
                val expectationsNotMet = expectations.map { it(response)}.flatten().firstOrNull()
                if (expectationsNotMet != null) {
                    return Either.Left(expectationsNotMet)
                } else {
                    return Either.Right(Response(responseCode, con.headerFields, buffer.toByteArray()))
                }
            } else {
                return Either.Left(HttpError(Some(responseCode), "err.not.http.success"))
            }

        } catch(e: Exception) {
            log.error("Exception ${e}")
            return Either.Left(HttpError(None, "err.exception.occurred", Some(e)))
        }
    }
}

data class Response(val code: Int, val headers: Map<String, List<String>>, val data: ByteArray) {
    constructor(code: Int, headers: Map<String, List<String>>, data: String): this(code, headers, data.toByteArray())
}

class Credentials(val username: String, val password: String, val realm: Option<String>) {
    constructor(username: String, password: String, realm: String?): this(username, password, Option(realm))
    fun realm(): String? = realm.orNull()
}

class Request(val url: URL,
              val method: HttpMethod = HttpMethod.Get,
              val params: List<Pair<String, String>> = listOf(),
              val headers: List<Pair<String, String>> = listOf(),
              val credentials: Option<Credentials> = None,
              val expectations: List<(Response) -> Option<HttpError>> = listOf()) {
    constructor(url: URL,
                method: HttpMethod = HttpMethod.Get,
                params: List<Pair<String, String>>? = null,
                headers: List<Pair<String, String>>?= null,
                credentials: Credentials? = null,
                expectations: List<(Response) -> HttpError?>? = null):
            this(url, method, params ?: listOf(), headers ?: listOf(), Option(credentials),
                    if (expectations == null) listOf() else expectations.map { it -> { t: Response -> Option(it(t))} })
    constructor(url: URL): this(url, HttpMethod.Get, listOf(), listOf(), None, listOf())
    fun withParams(vararg params: Pair<String, String>): Request =
            Request(url, this.method, this.params + params, headers, credentials, expectations)
    fun withParams(params: List<Pair<String, String>>): Request =
            Request(url, this.method, this.params + params, headers, credentials, expectations)


    fun withHeaders(vararg headers: Pair<String, String>): Request =
            Request(url, this.method, params, this.headers + headers, credentials, expectations)
    fun withHeaders(headers: List<Pair<String, String>>): Request =
            Request(url, this.method, params, this.headers + headers, credentials, expectations)

    fun withCredentials(credentials: Credentials): Request =
            Request(url, this.method, params, headers, Option(credentials), expectations)

    fun withExpectations(vararg expectations: (Response) -> Option<HttpError>): Request =
            Request(url, this.method, params, headers, credentials, this.expectations + expectations)
    fun withExpectations(expectations: List<(Response) -> Option<HttpError>>): Request =
            Request(url, this.method, params, headers, credentials, this.expectations + expectations)

    fun connect(): HttpConnection {
        val queryStr = params.map{
            URLEncoder.encode(it.first, StandardCharsets.UTF_8.name()) + "=" +
                    URLEncoder.encode(it.second, StandardCharsets.UTF_8.name())}?.joinToString("&")
        val fullUrl = if(queryStr == null || queryStr.trim().length == 0) url else URL(url.toString() + "?" + queryStr)
        val con = fullUrl.openConnection()
        headers.forEach { con.addRequestProperty(it.first, it.second) }
        credentials.map {
            val encoded = Base64.getEncoder().encodeToString((it.username + ":" + it.password).toByteArray())
            con.addRequestProperty("Authorization", "Basic " + encoded)
        }
        if (con !is HttpURLConnection) throw IllegalArgumentException("URL is not http/https URL ${url}")
        con.requestMethod = method.name.toUpperCase()
        return HttpConnection(this, con as HttpURLConnection, expectations)
    }

    fun perform(): Either<HttpError, Response> = connect().response()

    fun get(): Request = Request(url, HttpMethod.Get, params, headers, credentials, expectations)
//    These are still to be developed
//    fun post(): Request = Request(url, HttpMethod.Post, params, headers, credentials, expectations)
//    fun put(): Request = Request(url, HttpMethod.Put, params, headers, credentials, expectations)
//    fun delete(): Request = Request(url, HttpMethod.Delete, params, headers, credentials, expectations)

    fun asCallable(): Callable<Either<HttpError, Response>> {
        return object: Callable<Either<HttpError, Response>> {
            override fun call():Either<HttpError, Response> =
                connect().response()
        }
    }

}

fun String.http(): Request = Request(URL(this))

fun<T> Request.perform(onError: (HttpError)->T, onSuccess: (Response) -> T): T {
    return this.connect().response().fold(onError, onSuccess)
}

fun<T> Request.perform(executor: ExecutorService, onError: (HttpError) -> T, onSuccess: (Response) -> T) {
    executor.submit {
        connect().response().fold(onError,onSuccess)
    }
}
