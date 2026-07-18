package li.mofanx.epso.service

import android.app.Service
import android.content.Intent
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import li.mofanx.epso.data.AppInfo
import li.mofanx.epso.data.DeviceInfo
import li.mofanx.epso.data.selfAppInfo
import li.mofanx.epso.expansion.Match
import li.mofanx.epso.expansion.MatchStore
import li.mofanx.epso.notif.StopServiceReceiver
import li.mofanx.epso.notif.httpNotif
import li.mofanx.epso.permission.foregroundServiceSpecialUseState
import li.mofanx.epso.permission.notificationState
import li.mofanx.epso.store.storeFlow
import li.mofanx.epso.util.DefaultSimpleLifeImpl
import li.mofanx.epso.util.LogUtils
import li.mofanx.epso.util.OnSimpleLife
import li.mofanx.epso.util.getIpAddressInLocalNetwork
import li.mofanx.epso.util.isPortAvailable
import li.mofanx.epso.util.keepNullJson
import li.mofanx.epso.util.launchTry
import li.mofanx.epso.util.mapState
import li.mofanx.epso.util.startForegroundServiceByClass
import li.mofanx.epso.util.stopServiceByClass
import li.mofanx.epso.util.toast
import java.io.File
import java.util.UUID


class HttpService : Service(), OnSimpleLife by DefaultSimpleLifeImpl() {
    override fun onBind(intent: Intent?) = null
    override fun onCreate() = onCreated()
    override fun onDestroy() = onDestroyed()

    val httpServerPortFlow = storeFlow.mapState(scope) { s -> s.httpServerPort }

    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        useAliveToast("HTTP服务")
        StopServiceReceiver.autoRegister()
        onCreated {
            scope.launchTry(Dispatchers.IO) {
                httpServerPortFlow.collect {
                    localNetworkIpsFlow.value = getIpAddressInLocalNetwork()
                }
            }
        }
        onDestroyed {
            httpServerFlow.value?.stop()
            httpServerFlow.value = null
        }
        onCreated {
            ensureApiToken()
            httpNotif.notifyService()
            scope.launchTry(Dispatchers.IO) {
                httpServerPortFlow.collect { port ->
                    val isReboot = httpServerFlow.value != null
                    httpServerFlow.apply {
                        value?.stop()
                        value = null
                    }
                    if (!isPortAvailable(port)) {
                        toast("端口 $port 被占用，请更换后重试")
                        stopSelf()
                        return@collect
                    }
                    httpServerFlow.value = try {
                        createServer(port).apply { start() }
                    } catch (e: Exception) {
                        toast("HTTP服务启动失败:${e.stackTraceToString()}")
                        LogUtils.d("HTTP服务启动失败", e)
                        null
                    }
                    if (httpServerFlow.value == null) {
                        stopSelf()
                    } else if (isReboot) {
                        toast("HTTP服务重启成功")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "HttpService"
        val httpServerFlow = MutableStateFlow<ServerType?>(null)
        val isRunning = MutableStateFlow(false)
        val localNetworkIpsFlow = MutableStateFlow(emptyList<String>())
        private var lastAutoStart = 0L

        fun stop() {
            httpServerFlow.value?.stop()
            stopServiceByClass(HttpService::class)
        }
        fun start() {
            ensureApiToken()
            startForegroundServiceByClass(HttpService::class)
        }

        fun autoStart() {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            if (!storeFlow.value.httpServerAutoStart) return
            if (isRunning.value) return
            if (!notificationState.updateAndGet() || !foregroundServiceSpecialUseState.updateAndGet()) {
                LogUtils.d(TAG, "HTTP service auto-start skipped: missing notification or foreground service permission")
                return
            }
            start()
            lastAutoStart = System.currentTimeMillis()
        }

        fun ensureApiToken(): String {
            val existing = storeFlow.value.httpApiToken
            if (existing.isNotBlank()) return existing
            return regenerateApiToken()
        }

        fun regenerateApiToken(): String = UUID.randomUUID().toString().replace("-", "").also { token ->
            storeFlow.value = storeFlow.value.copy(httpApiToken = token)
        }
    }
}

typealias ServerType = EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>

@Serializable
data class RpcError(
    val message: String,
    val unknown: Boolean = false,
)

@Serializable
data class ServerInfo(
    val device: DeviceInfo = DeviceInfo(),
    val appInfo: AppInfo = selfAppInfo
)

@Serializable
data class ApiStatus(
    val server: ServerInfo = ServerInfo(),
    val expansionEnabled: Boolean,
    val ruleCount: Int,
    val fileCount: Int,
)

@Serializable
data class ApiRule(
    val file: String,
    val index: Int,
    val triggers: List<String>,
    val replace: String,
    val regex: String,
    val label: String?,
)

@Serializable
data class ExpansionRequest(val enabled: Boolean)

@Serializable
data class ApiResult(val ok: Boolean = true)

@Serializable
data class CreateFileRequest(val path: String)

@Serializable
data class CreateRuleRequest(val file: String, val rule: Match)

@Serializable
data class UpdateRuleRequest(val file: String, val index: Int, val rule: Match)

@Serializable
data class DeleteRuleRequest(val file: String, val index: Int)

@Serializable
data class ApiFile(val path: String, val size: Long = -1L)

private val apiFilePathPattern = Regex("^[a-zA-Z0-9_.-]+(/[a-zA-Z0-9_.-]+)*$")
private fun createServer(port: Int) = embeddedServer(CIO, port) {
    install(getKtorCorsPlugin())
    install(getKtorErrorPlugin())
    install(ContentNegotiation) { json(keepNullJson) }
    routing {
        get("/") { call.respondText(ContentType.Text.Html) { "<h1>Epso LAN API</h1>" } }
        route("/api/v1") {
            get("/docs") {
                call.respondText(API_DOCS, ContentType.Text.Plain.withParameter("charset", "utf-8"))
            }
            get("/status") {
                if (!call.isAuthorized()) return@get
                call.respond(
                    ApiStatus(
                        expansionEnabled = storeFlow.value.enableExpansion,
                        ruleCount = MatchStore.matchCount,
                        fileCount = MatchStore.groups.value.size,
                    )
                )
            }
            get("/rules") {
                if (!call.isAuthorized()) return@get
                val workspace = MatchStore.getWorkspaceDir()
                val rules = MatchStore.groups.value.flatMap { group ->
                    val file = runCatching { java.io.File(group.sourceFile).relativeTo(workspace).path }
                        .getOrDefault(group.sourceFile)
                    group.matches.mapIndexed { index, match ->
                        ApiRule(file, index, match.allTriggers, match.replace, match.regex, match.label)
                    }
                }
                call.respond(rules)
            }
            get("/files") {
                if (!call.isAuthorized()) return@get
                val files = MatchStore.listFilePaths().map { ApiFile(it) }
                call.respond(files)
            }
            post("/files") {
                if (!call.isAuthorized()) return@post
                val request = call.receive<CreateFileRequest>()
                if (!request.path.isSafeApiFilePath()) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = "invalid file path"))
                    return@post
                }
                val file = MatchStore.createFile(request.path)
                call.respond(mapOf("file" to file.name))
            }
            get("/files/{path...}") {
                if (!call.isAuthorized()) return@get
                val file = call.resolveApiFile() ?: return@get
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, RpcError(message = "file not found"))
                    return@get
                }
                val content = MatchStore.readRawFile(file)
                call.respondText(content, ContentType.Text.Plain.withParameter("charset", "utf-8"))
            }
            put("/files/{path...}") {
                if (!call.isAuthorized()) return@put
                val file = call.resolveApiFile() ?: return@put
                val content = call.receiveText()
                try {
                    MatchStore.writeRawFile(file, content)
                    call.respond(ApiResult())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = e.message ?: "invalid yaml"))
                }
            }
            delete("/files/{path...}") {
                if (!call.isAuthorized()) return@delete
                val file = call.resolveApiFile() ?: return@delete
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, RpcError(message = "file not found"))
                    return@delete
                }
                MatchStore.deleteRawFile(file)
                call.respond(ApiResult())
            }
            post("/rules") {
                if (!call.isAuthorized()) return@post
                val request = call.receive<CreateRuleRequest>()
                if (!request.rule.isValid) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = "invalid rule"))
                    return@post
                }
                val base = request.file.removeSuffix(".yml").removeSuffix(".yaml")
                val file = call.resolveApiFile(base) ?: return@post
                if (!file.exists()) MatchStore.createFile(base)
                MatchStore.addMatch(file, request.rule)
                call.respond(ApiResult())
            }
            put("/rules") {
                if (!call.isAuthorized()) return@put
                val request = call.receive<UpdateRuleRequest>()
                if (request.index < 0 || !request.rule.isValid) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = "invalid index or rule"))
                    return@put
                }
                val base = request.file.removeSuffix(".yml").removeSuffix(".yaml")
                val file = call.resolveApiFile(base) ?: return@put
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, RpcError(message = "file not found"))
                    return@put
                }
                try {
                    MatchStore.updateMatchByIndex(file, request.index, request.rule)
                    call.respond(ApiResult())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = e.message ?: "failed to update rule"))
                }
            }
            delete("/rules") {
                if (!call.isAuthorized()) return@delete
                val request = call.receive<DeleteRuleRequest>()
                if (request.index < 0) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = "invalid rule index"))
                    return@delete
                }
                val base = request.file.removeSuffix(".yml").removeSuffix(".yaml")
                val file = call.resolveApiFile(base) ?: return@delete
                if (!file.exists()) {
                    call.respond(HttpStatusCode.NotFound, RpcError(message = "file not found"))
                    return@delete
                }
                try {
                    MatchStore.deleteMatchByIndex(file, request.index)
                    call.respond(ApiResult())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, RpcError(message = e.message ?: "failed to delete rule"))
                }
            }
            post("/reload") {
                if (!call.isAuthorized()) return@post
                MatchStore.reload()
                call.respond(ApiResult())
            }
            post("/expansion") {
                if (!call.isAuthorized()) return@post
                val request = call.receive<ExpansionRequest>()
                storeFlow.value = storeFlow.value.copy(enableExpansion = request.enabled)
                call.respond(ApiResult())
            }
        }
    }
}

private fun String.isSafeApiFilePath(): Boolean {
    if (!apiFilePathPattern.matches(this)) return false
    return split('/').none { it == ".." || it == "." }
}

private suspend fun io.ktor.server.application.ApplicationCall.resolveApiFile(relative: String): File? {
    val base = relative.removeSuffix(".yml").removeSuffix(".yaml")
    if (!base.isSafeApiFilePath()) {
        respond(HttpStatusCode.BadRequest, RpcError(message = "invalid file path"))
        return null
    }
    val workspace = MatchStore.getWorkspaceDir().canonicalFile
    val file = workspace.resolve("$base.yml").canonicalFile
    if (!file.path.startsWith(workspace.path + File.separator) && file.path != workspace.path) {
        respond(HttpStatusCode.BadRequest, RpcError(message = "file path escapes workspace"))
        return null
    }
    return file
}

private suspend fun io.ktor.server.application.ApplicationCall.resolveApiFile(): File? {
    val segments = parameters.getAll("path") ?: run {
        respond(HttpStatusCode.BadRequest, RpcError(message = "missing file path"))
        return null
    }
    return resolveApiFile(segments.joinToString("/"))
}

private fun io.ktor.server.application.ApplicationCall.isLocalhost(): Boolean {
    val origin = request.origin
    val hosts = setOf(origin.remoteAddress, origin.remoteHost).filterNotNull()
    return hosts.any {
        it == "127.0.0.1" || it == "::1" || it == "0:0:0:0:0:0:0:1" || it.equals("localhost", ignoreCase = true)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.isAuthorized(): Boolean {
    // 本机/ADB reverse 或 adb forward 过来的请求免 token，便于自动化脚本调用
    if (isLocalhost()) return true
    val token = storeFlow.value.httpApiToken
    val authorization = request.headers[HttpHeaders.Authorization]
    val supplied = authorization?.removePrefix("Bearer ") ?: request.headers["X-Epso-Token"]
    if (token.isNotBlank() && supplied == token) return true
    respond(HttpStatusCode.Unauthorized, RpcError(message = "invalid or missing API token"))
    return false
}

private fun getKtorCorsPlugin() = createApplicationPlugin(name = "KtorCorsPlugin") {
    onCall { call ->
        mapOf(
            HttpHeaders.AccessControlAllowOrigin to "*",
            HttpHeaders.AccessControlAllowMethods to "*",
            HttpHeaders.AccessControlAllowHeaders to "*",
            HttpHeaders.AccessControlExposeHeaders to "*",
            "Access-Control-Allow-Private-Network" to "true",
        ).forEach { (k, v) ->
            if (!call.response.headers.contains(k)) {
                call.response.header(k, v)
            }
        }
        if (call.request.httpMethod == HttpMethod.Options) {
            call.respond("all-cors-ok")
        }
    }
}

private fun getKtorErrorPlugin() = createApplicationPlugin(name = "KtorErrorPlugin") {
    onCall { call ->
        if (call.request.uri == "/" || call.request.uri.startsWith("/api/")) {
            Log.d("Ktor", "onCall: ${call.request.origin.remoteAddress} -> ${call.request.uri}")
        }
    }
    on(CallFailed) { call, cause ->
        when (cause) {
            is Exception -> {
                LogUtils.d(call.request.uri, cause.message)
                cause.printStackTrace()
                call.respond(RpcError(message = cause.message ?: "unknown error", unknown = true))
            }

            else -> {
                cause.printStackTrace()
            }
        }
    }
}
