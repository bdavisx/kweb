package com.github.sanity.kweb

import com.github.sanity.kweb.browserConnection.OutboundChannel
import com.github.sanity.kweb.dev.hotswap.KwebHotswapPlugin
import com.github.sanity.kweb.plugins.KWebPlugin
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import mu.KLogging
import org.apache.commons.io.IOUtils
import org.wasabifx.wasabi.app.AppConfiguration
import org.wasabifx.wasabi.app.AppServer
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by ian on 12/31/16.
 */

typealias OneTime = Boolean
typealias LogError = Boolean
typealias JavaScriptError = String

/**
 * The core kwebserver, and the starting point for almost any Kweb app.  This will create a HTTP server and respond
 * with a javascript page which will establish a websocket connection to retrieve and send instructions and data
 * between browser and server.
 *
 * @property port  The TCP port on which the HTTP server should listen
 * @property debug Should be set to true during development as it will provide useful warnings and other feedback,
 *                 but false during production because it is inefficient at scale
 * @property refreshPageOnHotswap Detects code-reloads by [HotSwapAgent](http://hotswapagent.org/) and refreshes
 *                                any connected webpage if this is detected
 * @property plugins A list of Kweb plugins to be loaded by Kweb
 * @property onError A handler for JavaScript errors (only detected if `debug == true`)
 * @property maxPageBuildTimeMS If `debug == true` this is the maximum time permitted to build a page before a
 *                              warning is logged
 * @property buildPage A lambda which will build the webpage to be served to the user, this is where your code should
 *                     go
 *
 * @sample com.github.sanity.kweb.samples.main
 */
class Kweb(val port: Int,
           val debug: Boolean = true,
           val refreshPageOnHotswap : Boolean = false,
           val plugins: List<KWebPlugin> = Collections.emptyList(),
           val appServerConfigurator: (AppServer) -> Unit = {},
           val onError : ((List<StackTraceElement>, JavaScriptError) -> LogError) = { _, _ ->  true},
           val maxPageBuildTimeMS : Long = 200,
           val buildPage: RootReceiver.() -> Unit
) {
    companion object: KLogging()

    private val server = AppServer(AppConfiguration(port = port))
    private val clients: MutableMap<String, WSClientData>
    private val mutableAppliedPlugins: MutableSet<KWebPlugin> = HashSet()
    val appliedPlugins: Set<KWebPlugin> get() = mutableAppliedPlugins

    init {
        appServerConfigurator.invoke(server)

        //TODO: Need to do housekeeping to delete old client data
        clients = ConcurrentHashMap<String, WSClientData>()

        val startHeadBuilder = StringBuilder()
        val endHeadBuilder = StringBuilder()

        for (plugin in plugins) {
            applyPlugin(plugin = plugin, appliedPlugins = mutableAppliedPlugins, endHeadBuilder = endHeadBuilder, startHeadBuilder = startHeadBuilder, appServer = server)
        }

        if (refreshPageOnHotswap) {
            KwebHotswapPlugin.addHotswapReloadListener({refreshAllPages()})
        }

        val bootstrapHtmlTemplate = IOUtils.toString(javaClass.getResourceAsStream("kweb_bootstrap.html"), Charsets.UTF_8)
                .replace("<!-- START HEADER PLACEHOLDER -->", startHeadBuilder.toString())
                .replace("<!-- END HEADER PLACEHOLDER -->", endHeadBuilder.toString())

        server.get("/", {
            val newClientId = Math.abs(random.nextLong()).toString(16)
            val outboundBuffer = OutboundChannel.TemporarilyStoringChannel()
            val wsClientData = WSClientData(id = newClientId, outboundChannel = outboundBuffer)
            clients.put(newClientId, wsClientData)
            val httpRequestInfo = HttpRequestInfo(request.uri, request.rawHeaders)

            if (debug) {
                warnIfBlocking(maxTimeMs = maxPageBuildTimeMS, onBlock = { thread ->
                    val logStatementBuilder = StringBuilder()
                    logStatementBuilder.appendln("buildPage lambda must return immediately but has taken > $maxPageBuildTimeMS ms, appears to be blocking here:")
                    thread.stackTrace.pruneAndDumpStackTo(logStatementBuilder)
                    val logStatement = logStatementBuilder.toString()
                    logger.warn { logStatement }
                }) {
                    buildPage(RootReceiver(newClientId, httpRequestInfo, this@Kweb))
                }
            } else {
                buildPage(RootReceiver(newClientId, httpRequestInfo, this@Kweb))
            }
            for (plugin in plugins) {
                execute(newClientId, plugin.executeAfterPageCreation())
            }
            wsClientData.outboundChannel = OutboundChannel.TemporarilyStoringChannel()

            val bootstrapHtml = bootstrapHtmlTemplate
                    .replace("--CLIENT-ID-PLACEHOLDER--", newClientId)
                    .replace("<!-- BUILD PAGE PAYLOAD PLACEHOLDER -->", outboundBuffer.read().map {"handleInboundMessage($it);"} . joinToString(separator = "\n"))
            response.send(bootstrapHtml)
        })

        server.channel("/ws") {
            if (frame is TextWebSocketFrame) {
                val message = gson.fromJson((frame as TextWebSocketFrame).text(), C2SWebsocketMessage::class.java)
                handleInboundMessage(ctx!!, message)
            }
        }
        server.start(wait = false)
        logger.info {"Kweb is listening on port $port"}
    }

    private fun handleInboundMessage(ctx: ChannelHandlerContext, message: C2SWebsocketMessage) {
        val wsClientData = clients[message.id] ?: throw RuntimeException("Message with id ${message.id} received, but id is unknown")
        logger.debug { "Message received from client id ${wsClientData.id}" }
        if (message.error != null) {
            handleError(message.error, wsClientData)
        } else if (message.hello != null) {
            handleHello(ctx, wsClientData)
        } else {
            val clientId = message.id
            val clientData = clients[clientId] ?: throw RuntimeException("No handler found for client $clientId")
            when {
                message.callback != null -> {
                    val (resultId, result) = message.callback
                    val resultHandler = clientData.handlers[resultId] ?: throw RuntimeException("No data handler for $resultId for client $clientId")
                    resultHandler(result ?: "")
                }
            }
        }
    }

    private fun handleHello(ctx: ChannelHandlerContext, wsClientData: WSClientData) {
        val tempQueue = wsClientData.outboundChannel as OutboundChannel.TemporarilyStoringChannel
        wsClientData.outboundChannel = OutboundChannel.WSChannel(ctx.channel())
        tempQueue.read().forEach { wsClientData.outboundChannel.send(it) }
    }

    private fun handleError(error: C2SWebsocketMessage.ErrorMessage, wsClientData: WSClientData) {
        val debugInfo = wsClientData.debugTokens[error.debugToken] ?: throw RuntimeException("DebugInfo error not found")
        val logStatementBuilder = StringBuilder()
        logStatementBuilder.appendln("JavaScript error: '${error.error.message}'")
        logStatementBuilder.appendln("Caused by ${debugInfo.action}: '${debugInfo.js}':")
        // TODO: Filtering the stacktrace like this seems a bit kludgy, although I can't think
        // TODO: of a specific reason why it would be bad.
        debugInfo.throwable.stackTrace.pruneAndDumpStackTo(logStatementBuilder)
        if (onError(debugInfo.throwable.stackTrace.toList(), error.error.message)) {
            logger.error(logStatementBuilder.toString())
        }
    }

    private fun applyPlugin(plugin: KWebPlugin,
                            appliedPlugins: MutableSet<KWebPlugin>,
                            endHeadBuilder: java.lang.StringBuilder,
                            startHeadBuilder: java.lang.StringBuilder,
                            appServer : AppServer) {
        for (dependantPlugin in plugin.dependsOn) {
            if (!appliedPlugins.contains(dependantPlugin)) {
                applyPlugin(dependantPlugin, appliedPlugins, endHeadBuilder, startHeadBuilder, appServer)
                appliedPlugins.add(dependantPlugin)
            }
        }
        if (!appliedPlugins.contains(plugin)) {
            plugin.decorate(startHeadBuilder, endHeadBuilder)
            plugin.appServerConfigurator(appServer)
            appliedPlugins.add(plugin)
        }
    }

    private fun refreshAllPages() {
        for (client in clients.values) {
            val message = S2CWebsocketMessage(
                    yourId = client.id,
                    execute = S2CWebsocketMessage.Execute("window.location.reload(true);"))
            client.outboundChannel.send(message.toJson())
        }
    }

    fun execute(clientId: String, javascript: String) {
        val wsClientData = clients.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(javascript, "executing", Throwable()))
            dt
        }
        wsClientData.send(S2CWebsocketMessage(yourId = clientId, debugToken = debugToken, execute = S2CWebsocketMessage.Execute(javascript)))
    }

    fun executeWithCallback(clientId: String, javascript: String, callbackId: Int, handler: (String) -> Unit) {
        val wsClientData = clients.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(javascript, "executing with callback", Throwable()))
            dt
        }
        wsClientData.handlers.put(callbackId, handler)
        wsClientData.send(S2CWebsocketMessage(yourId = clientId, execute = S2CWebsocketMessage.Execute(javascript)))
    }

    fun evaluate(clientId: String, expression: String, handler: (String) -> Unit) {
        val wsClientData = clients.get(clientId) ?: throw RuntimeException("Client id $clientId not found")
        val debugToken: String? = if (!debug) null else {
            val dt = Math.abs(random.nextLong()).toString(16)
            wsClientData.debugTokens.put(dt, DebugInfo(expression, "evaluating", Throwable()))
            dt
        }
        val callbackId = Math.abs(random.nextInt())
        wsClientData.handlers.put(callbackId, handler)
        wsClientData.send(S2CWebsocketMessage(clientId, evaluate = S2CWebsocketMessage.Evaluate(expression, callbackId)))
    }

}

private data class WSClientData(val id: String, @Volatile var outboundChannel: OutboundChannel, val handlers: MutableMap<Int, (String) -> Unit> = HashMap(), val debugTokens: MutableMap<String, DebugInfo> = HashMap()) {
    fun send(message: S2CWebsocketMessage) {
        outboundChannel.send(gson.toJson(message))
    }
}

data class DebugInfo(val js: String, val action : String, val throwable: Throwable)

data class S2CWebsocketMessage(
        val yourId: String,
        val debugToken: String? = null,
        val execute: Execute? = null,
        val evaluate: Evaluate? = null
) {
    data class Execute(val js: String)
    data class Evaluate(val js: String, val callbackId: Int)
}

data class C2SWebsocketMessage(
        val id: String,
        val hello: Boolean? = true,
        val error: ErrorMessage? = null,
        val callback: C2SCallback?
) {
    data class ErrorMessage(val debugToken: String, val error: Error) {
        data class Error(val name: String, val message: String)
    }

    data class C2SCallback(val callbackId: Int, val data: String?)
}

