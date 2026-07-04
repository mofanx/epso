package li.mofanx.epso.service

import android.app.Service
import android.content.Intent
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import li.mofanx.epso.data.AppInfo
import li.mofanx.epso.data.DeviceInfo
import li.mofanx.epso.data.selfAppInfo
import li.mofanx.epso.notif.StopServiceReceiver
import li.mofanx.epso.notif.httpNotif
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
            httpServerFlow.value = null
        }
        onCreated {
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
        val httpServerFlow = MutableStateFlow<ServerType?>(null)
        val isRunning = MutableStateFlow(false)
        val localNetworkIpsFlow = MutableStateFlow(emptyList<String>())
        fun stop() = stopServiceByClass(HttpService::class)
        fun start() = startForegroundServiceByClass(HttpService::class)
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

private fun createServer(port: Int) = embeddedServer(CIO, port) {
    install(getKtorCorsPlugin())
    install(getKtorErrorPlugin())
    install(ContentNegotiation) { json(keepNullJson) }
    routing {
        get("/") { call.respondText(ContentType.Text.Html) { "<h1>HTTP Server Running</h1>" } }
        route("/api") {
            get("/getServerInfo") { call.respond(ServerInfo()) }
        }
    }
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
