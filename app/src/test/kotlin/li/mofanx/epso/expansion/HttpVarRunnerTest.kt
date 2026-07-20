package li.mofanx.epso.expansion

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpVarRunnerTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/", TestHandler())
        server.executor = null
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `http GET returns response body`() = runTest {
        val result = HttpVarRunner.run(url = baseUrl("/text"))
        assertEquals("plain text", result)
    }

    @Test
    fun `http POST sends body and headers`() = runTest {
        val result = HttpVarRunner.run(
            url = baseUrl("/echo"),
            method = "POST",
            headers = mapOf("X-Custom" to "value"),
            body = "hello",
        )
        assertEquals("POST|X-Custom=value|hello", result)
    }

    @Test
    fun `json path extracts nested value`() = runTest {
        val result = HttpVarRunner.run(
            url = baseUrl("/json"),
            jsonPath = "data.0.name",
        )
        assertEquals("Alice", result)
    }

    @Test
    fun `json path on non-json returns null`() = runTest {
        val result = HttpVarRunner.run(
            url = baseUrl("/text"),
            jsonPath = "foo",
        )
        assertNull(result)
    }

    @Test
    fun `404 without ignore error returns null`() = runTest {
        val result = HttpVarRunner.run(url = baseUrl("/notfound"))
        assertNull(result)
    }

    @Test
    fun `404 with ignore error returns response body`() = runTest {
        val result = HttpVarRunner.run(url = baseUrl("/notfound"), ignoreError = true)
        assertEquals("not found", result)
    }

    @Test
    fun `url and body placeholders are resolved from values`() = runTest {
        val ev = VarEvaluator(
            globalVars = emptyList(),
            formLauncher = { null },
            choiceLauncher = { null },
        )
        val localVars = listOf(
            Var(name = "path", type = "echo", params = VarParams(echo = "greet")),
            Var(name = "bodyVar", type = "echo", params = VarParams(echo = "world")),
        )
        // The http var itself is evaluated; its url/body contain placeholders that reference earlier vars.
        // Because topological sort respects depends_on, we use explicit depends_on to guarantee order.
        val httpVar = Var(
            name = "resp",
            type = "http",
            params = VarParams(
                url = baseUrl("/{{path}}"),
                method = "POST",
                body = "hello {{bodyVar}}",
            ),
            dependsOn = listOf("path", "bodyVar"),
        )
        val result = ev.evaluate("{{resp}}", localVars = localVars + httpVar)
        assertEquals("hello world", result)
    }

    private fun baseUrl(path: String): String = "http://127.0.0.1:$port$path"

    private class TestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            when (exchange.requestURI.path) {
                "/text" -> send(exchange, 200, "plain text")
                "/json" -> send(exchange, 200, """{"data":[{"name":"Alice"},{"name":"Bob"}],"ok":true}""")
                "/echo" -> {
                    val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    val customHeader = exchange.requestHeaders["X-Custom"]?.firstOrNull() ?: ""
                    val response = "${exchange.requestMethod}|X-Custom=$customHeader|$body"
                    send(exchange, 200, response)
                }
                "/greet" -> {
                    val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    send(exchange, 200, body)
                }
                else -> send(exchange, 404, "not found")
            }
        }

        private fun send(exchange: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
