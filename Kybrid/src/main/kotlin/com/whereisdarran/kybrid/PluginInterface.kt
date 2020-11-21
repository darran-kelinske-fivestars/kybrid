package com.whereisdarran.kybrid

import com.whereisdarran.instore.message.JavascriptMessage
import com.whereisdarran.instore.message.NativeMessage
import com.whereisdarran.kybrid.util.messaging.CallbackUtil

import com.whereisdarran.kybrid.util.promise.rejectHelper
import kotlinx.browser.window
import kotlin.js.Promise
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.MessageEvent
import org.w3c.dom.MessagePort
import org.w3c.dom.events.EventListener

abstract class PluginInterface<ActionT>(
    val callbackUtil: CallbackUtil<ActionT>,
    private val config: PluginConfig
) {
    protected val json = Json

    // Plugins must override this to expose their Action type's serializer, used
    // by serialize() and deserialize() below.
    protected abstract val actionSerializer: KSerializer<ActionT>

    private var isInitialized = false

    private var port: MessagePort? = null

    fun sendMessageToNative(javascriptMessage: JavascriptMessage<ActionT>) {
        val json = serialize(javascriptMessage)
        port?.postMessage(json)
    }

    /**
     * - sets up window listener to receive a message port
     * - waits for that to happen
     * - removes window listener for some nice cleanup
     */
    @JsName("initialize")
    fun initialize(): Promise<Unit> = Promise { resolve, _ ->
        var eventListener: EventListener? = null

        val portReceived = Promise<Unit> { receivedResolve, _ ->
            eventListener = EventListener {
                val handled = handlePortMessage(it as MessageEvent)
                if (handled) {
                    receivedResolve(Unit)
                }
            }
            window.addEventListener("message", eventListener, false)
        }

        portReceived.then {
            window.removeEventListener("message", eventListener, false)
            postInitialize().then {
                console.log("${this.config.TAG}: finished postInitialize")
                resolve(Unit)
                isInitialized = true
            }
        }
    }

    @JsName("getIsInitialized")
    fun getIsInitialized(): Boolean = isInitialized

    open fun postInitialize(): Promise<Unit> = Promise.resolve(Unit)

    /**
     * Responds to a particular message from native side, to pass down the MessagePort for us to use
     * Sets messages to be passed into the callback util
     */
    private fun handlePortMessage(event: MessageEvent): Boolean {
        if (event.data != config.PORT) {
            return false
        }

        port = event.ports[0]
        port?.onmessage = {
            val nativeMessage = this.deserialize(it.data.toString())
            callbackUtil.invokeCallback(nativeMessage)
        }
        return true
    }

    /**
     * Automates the process of making a call to a native action and deserializing
     * the return type.
     *
     * @param action The action to call
     * @param data The data to pass with the action
     * @param returnSerializer (optional) the serializer to use when deserializing the response
     *
     * @return Promise<T> that resolves with the value returned by the native layer
     */
    @ExperimentalUnsignedTypes
    protected fun <T> makeCall(
        action: ActionT,
        data: String? = null,
        returnSerializer: KSerializer<T>? = null
    ): Promise<T> = Promise { resolve, reject ->
        val resolver = if (returnSerializer === null) {
            resolve
        } else {
            { resolve(json.decodeFromString(returnSerializer, it as String)) }
        }

        sendMessageToNative(
            JavascriptMessage(
                action,
                data,
                callbackUtil.registerSingleCallback(action, resolver, rejectHelper(reject))
            )
        )
    }

    /**
     * Serialize a JavascriptMessage to json, to send to the native plugin.
     */
    fun serialize(msg: JavascriptMessage<ActionT>): String {
        return json.encodeToString(JavascriptMessage.serializer(actionSerializer), msg)
    }

    /**
     * Deserialize a message received from the native plugin.
     */
    fun deserialize(jsonString: String): NativeMessage<ActionT> {
        return json.decodeFromString(NativeMessage.serializer(actionSerializer), jsonString)
    }
}