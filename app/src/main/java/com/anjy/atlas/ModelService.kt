package com.anjy.atlas

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * The local model runs here, in a process of its own (":brain"). A big model can run the phone out of memory, and
 * llama.cpp can crash on a damaged file; either way only this process dies, and Atlas carries on and says so.
 */
class ModelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: InferenceEngine? = null
    /**
     * The answer being written, and its request id. Stop in the chat cancels it (CANCEL): the runtime then stops between
     * words and is ready again. Without this the old answer ran on to its end, and the next question was refused with
     * "The model isn't ready" (and a load in the meantime with "Cannot unload model in Generating").
     */
    @Volatile private var gen: Job? = null
    @Volatile private var genId = 0

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(Message.obtain(msg)); true })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /**
     * Atlas let go of the model (put away when idle, short of memory, hot, or a new file). Unbinding only ends this
     * Service: the ":brain" process would stay cached with the model's weights still in memory, so the memory isn't
     * freed and the next load can find the old model still there. This process exists only for the model, so it ends.
     */
    override fun onDestroy() {
        // (no cleanUp first: an answer may still be running on another thread; ending the process frees everything)
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun send(to: Messenger?, what: Int, id: Int, text: String? = null) {
        val m = Message.obtain(null, what, id, 0)
        if (text != null) m.data = Bundle().apply { putString("text", text) }
        try { to?.send(m) } catch (e: Exception) { Log.w(TAG, "reply failed", e) }
    }

    private fun engine(): InferenceEngine {
        engine?.let { return it }
        System.loadLibrary("ai-chat")
        return AiChat.getInferenceEngine(applicationContext).also { engine = it }
    }

    private fun handle(msg: Message) {
        val to = msg.replyTo; val id = msg.arg1
        val data = msg.data
        when (msg.what) {
            LOAD -> { val prev = gen; scope.launch {
                try {
                    prev?.cancelAndJoin()
                    val e = engine()
                    val st = e.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error || it.isModelLoaded }
                    if (st.isModelLoaded || st is InferenceEngine.State.Error) e.cleanUp()
                    e.loadModel(data.getString("path")!!)
                    e.setSystemPrompt(data.getString("system") ?: "")
                    send(to, DONE, id)
                } catch (t: Throwable) {
                    try { engine?.cleanUp() } catch (_: Throwable) {}
                    send(to, ERROR, id, t.message ?: t.javaClass.simpleName)
                }
            } }
            GENERATE -> { val prev = gen; genId = id; gen = scope.launch {
                try {
                    prev?.cancelAndJoin()   // a stopped answer finishes stopping first
                    val e = engine ?: throw IllegalStateException("No model loaded.")
                    if (e.state.value !is InferenceEngine.State.ModelReady) throw IllegalStateException("The model isn't ready.")
                    e.sendUserPrompt(data.getString("text") ?: "", data.getInt("max", 320)).collect { tok -> send(to, TOKEN, id, tok) }
                    send(to, DONE, id)
                } catch (t: Throwable) { send(to, ERROR, id, t.message ?: t.javaClass.simpleName) }
            } }
            CANCEL -> if (genId == id) gen?.cancel()
            UNLOAD -> { val prev = gen; scope.launch {
                try { prev?.cancelAndJoin(); engine?.let { if (it.state.value.isModelLoaded || it.state.value is InferenceEngine.State.Error) it.cleanUp() } } catch (_: Throwable) {}
                send(to, DONE, id)
            } }
        }
    }

    companion object {
        private const val TAG = "AtlasBrainProc"
        const val LOAD = 1; const val GENERATE = 2; const val UNLOAD = 3; const val CANCEL = 4
        const val TOKEN = 10; const val DONE = 11; const val ERROR = 12
    }
}

/** Atlas's side of the line to [ModelService]. */
object ModelLink {
    private const val TAG = "AtlasModelLink"
    private val main = Handler(Looper.getMainLooper())
    private var remote: Messenger? = null
    private var connecting: CompletableDeferred<Messenger>? = null
    private var nextId = 1

    private class Pending(val onToken: (String) -> Unit, val done: CompletableDeferred<String>, val sb: StringBuilder = StringBuilder())
    private val pending = mutableMapOf<Int, Pending>()

    /** Set when the model's process died: shown to the user instead of a vanished app. */
    @Volatile var died: String? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { m ->
        val p = pending[m.arg1]
        if (p != null) when (m.what) {
            ModelService.TOKEN -> { p.sb.append(m.data?.getString("text") ?: ""); p.onToken(p.sb.toString()) }
            ModelService.DONE -> { pending.remove(m.arg1); p.done.complete(p.sb.toString()) }
            ModelService.ERROR -> { pending.remove(m.arg1); p.done.completeExceptionally(IllegalStateException(m.data?.getString("text") ?: "The model failed.")) }
        }
        true
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val m = Messenger(service); remote = m
            connecting?.complete(m); connecting = null
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            // the model's process is gone (out of memory, or a crash in llama.cpp); Atlas itself is fine
            Log.w(TAG, "model process died")
            remote = null
            died = "The offline model stopped: the phone likely ran out of memory for it. Atlas is fine; try a smaller model."
            val all = pending.values.toList(); pending.clear()
            all.forEach { it.done.completeExceptionally(IllegalStateException(died)) }
            ModelManager.onProcessDied()
        }
    }

    private suspend fun link(ctx: Context): Messenger {
        remote?.let { return it }
        val d = connecting ?: CompletableDeferred<Messenger>().also {
            connecting = it
            main.post { ctx.applicationContext.bindService(Intent(ctx, ModelService::class.java), conn, Context.BIND_AUTO_CREATE) }
        }
        return withTimeout(15_000) { d.await() }
    }

    /** Sends one request; [onToken] gets the text so far as it streams. Returns the whole text. */
    suspend fun request(ctx: Context, what: Int, data: Bundle, onToken: (String) -> Unit = {}): String {
        val r = link(ctx)
        val done = CompletableDeferred<String>()
        val id: Int
        synchronized(this) { id = nextId++ }
        main.post { pending[id] = Pending(onToken, done) }
        suspendCancellableCoroutine<Unit> { c -> main.post { c.resume(Unit) } }
        val m = Message.obtain(null, what, id, 0).apply { this.data = data; replyTo = replies }
        r.send(m)
        return try { done.await() } catch (e: kotlinx.coroutines.CancellationException) {
            // stopped: its words no longer reach the chat (they kept filling the reply after Stop), and the model stops too
            main.post { pending.remove(id) }
            try { r.send(Message.obtain(null, ModelService.CANCEL, id, 0)) } catch (_: Exception) {}
            throw e
        }
    }

    fun disconnect(ctx: Context) {
        try { ctx.applicationContext.unbindService(conn) } catch (_: Exception) {}
        remote = null
    }
}
