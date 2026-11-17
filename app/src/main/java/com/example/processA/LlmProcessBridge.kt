package com.example.processA

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
import com.example.data.LlmModelManager
import com.example.data.SafetyTier
import com.example.data.ToolCallRequest
import com.example.engine.DeferredToolRegistry
import com.example.processB.LlmEngineService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LlmModelMissingException(message: String) : IllegalStateException(message)

/**
 * Process A -> Process B (:llm) IPC Bridge.
 * Refuses Tier 2/3 LLM execution when no `.litertlm` model is present on disk
 * (zero fake/simulated fallback responses).
 */
class LlmProcessBridge(
    private val context: Context,
    private val modelManager: LlmModelManager = LlmModelManager(context)
) {

    data class BridgeState(
        val isBound: Boolean = false,
        val isLlmReady: Boolean = false,
        val modelPresent: Boolean = false,
        val activeModelName: String? = null,
        val activeModelSizeMb: Long = 0L,
        val activeLlmBackend: String = "UNLOADED",
        val processBStatus: String = "MODEL_MISSING",
        val processBPid: Int = 0,
        val respawnCount: Int = 0
    )

    data class RemoteLlmResult(
        val selectedSkillId: String,
        val thinkingEnabled: Boolean,
        val toolCalls: List<ToolCallRequest>,
        val note: String,
        val inferenceMs: Long,
        val pegRetried: Boolean,
        val processPid: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(refreshModelPresence(BridgeState()))
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private val pendingTurns = ConcurrentHashMap<String, CompletableDeferred<RemoteLlmResult>>()

    private var remoteMessenger: Messenger? = null
    private var remoteBinder: IBinder? = null

    private val incomingReplyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val d = msg.data
            when (msg.what) {
                LlmEngineService.MSG_WARMUP_DONE, LlmEngineService.MSG_STATUS_REPLY -> {
                    val ready = d.getBoolean(LlmEngineService.KEY_IS_READY, false)
                    val pid = d.getInt(LlmEngineService.KEY_PID, 0)
                    val stateName = d.getString(LlmEngineService.KEY_STATE_NAME) ?: if (ready) "READY" else "MODEL_MISSING"
                    val backend = d.getString(LlmEngineService.KEY_ACTIVE_BACKEND) ?: if (ready) "GPU" else "UNLOADED"
                    _state.value = refreshModelPresence(
                        _state.value.copy(
                            isLlmReady = ready,
                            activeLlmBackend = backend,
                            processBStatus = stateName,
                            processBPid = pid
                        )
                    )
                }

                LlmEngineService.MSG_INFER_RESULT -> {
                    val reqId = d.getString(LlmEngineService.KEY_REQUEST_ID).orEmpty()
                    val calls = parseCalls(d.getString(LlmEngineService.KEY_TOOL_CALLS_JSON).orEmpty())
                    val result = RemoteLlmResult(
                        selectedSkillId = d.getString(LlmEngineService.KEY_SELECTED_SKILL).orEmpty(),
                        thinkingEnabled = d.getBoolean(LlmEngineService.KEY_THINKING_ENABLED, false),
                        toolCalls = calls,
                        note = d.getString(LlmEngineService.KEY_NOTE).orEmpty(),
                        inferenceMs = d.getLong(LlmEngineService.KEY_INFERENCE_MS, 0L),
                        pegRetried = d.getBoolean(LlmEngineService.KEY_PEG_RETRIED, false),
                        processPid = d.getInt(LlmEngineService.KEY_PID, 0)
                    )
                    pendingTurns.remove(reqId)?.complete(result)
                }

                LlmEngineService.MSG_INFER_ABORTED -> {
                    val reqId = d.getString(LlmEngineService.KEY_REQUEST_ID).orEmpty()
                    val reason = d.getString(LlmEngineService.KEY_NOTE) ?: "Cancelled"
                    val isMissing = d.getBoolean(LlmEngineService.KEY_MODEL_MISSING, false)
                    val ex: Throwable = if (isMissing) {
                        LlmModelMissingException(reason)
                    } else {
                        kotlinx.coroutines.CancellationException(reason)
                    }
                    pendingTurns.remove(reqId)?.completeExceptionally(ex)
                }
            }
        }
    }

    private val replyMessenger = Messenger(incomingReplyHandler)

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Process B (:llm) died! Respawning isolated engine...")
        remoteMessenger = null
        remoteBinder = null
        pendingTurns.values.forEach {
            it.completeExceptionally(IllegalStateException(":llm process died; respawning"))
        }
        pendingTurns.clear()
        _state.value = refreshModelPresence(
            _state.value.copy(
                isBound = false,
                isLlmReady = false,
                processBStatus = "RESPAWNING",
                processBPid = 0,
                respawnCount = _state.value.respawnCount + 1
            )
        )
        scope.launch {
            delay(400L)
            bindToLlmProcess()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteBinder = service
            remoteMessenger = Messenger(service)
            runCatching { service?.linkToDeath(deathRecipient, 0) }
            _state.value = refreshModelPresence(
                _state.value.copy(
                    isBound = true,
                    processBStatus = if (modelManager.isModelPresent()) "WARMING_UP" else "MODEL_MISSING"
                )
            )
            reloadModelInProcessB()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteMessenger = null
            remoteBinder = null
            _state.value = refreshModelPresence(
                _state.value.copy(
                    isBound = false,
                    isLlmReady = false,
                    processBStatus = "DISCONNECTED"
                )
            )
        }
    }

    fun refreshPresenceOnly() {
        _state.value = refreshModelPresence(_state.value)
    }

    private fun refreshModelPresence(current: BridgeState): BridgeState {
        val file = modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)
        val present = file != null
        return current.copy(
            modelPresent = present,
            activeModelName = file?.name,
            activeModelSizeMb = (file?.length() ?: 0L) / (1024L * 1024L),
            isLlmReady = if (present) current.isLlmReady else false,
            processBStatus = if (!present) "MODEL_MISSING" else current.processBStatus
        )
    }

    fun bindToLlmProcess() {
        refreshPresenceOnly()
        runCatching {
            val intent = Intent(context, LlmEngineService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.w(TAG, "Service binding unavailable: ${it.message}")
        }
    }

    fun reloadModelInProcessB(preferNpu: Boolean = false) {
        refreshPresenceOnly()
        val file = modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)
        val msg = Message.obtain(null, LlmEngineService.MSG_WARMUP).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(LlmEngineService.KEY_MODEL_PATH, file?.absolutePath)
                putBoolean(LlmEngineService.KEY_PREFER_NPU, preferNpu)
            }
        }
        runCatching { remoteMessenger?.send(msg) }
    }

    fun unloadModelInProcessB() {
        sendControlMessage(LlmEngineService.MSG_UNLOAD_MODEL)
        _state.value = refreshModelPresence(
            _state.value.copy(isLlmReady = false, activeLlmBackend = "UNLOADED", processBStatus = "MODEL_MISSING")
        )
    }

    fun unbind() {
        runCatching {
            remoteBinder?.unlinkToDeath(deathRecipient, 0)
            context.unbindService(connection)
        }
    }

    fun sendBargeInAbort() {
        pendingTurns.values.forEach {
            it.cancel(kotlinx.coroutines.CancellationException("Barge-in interrupt"))
        }
        pendingTurns.clear()
        sendControlMessage(LlmEngineService.MSG_BARGE_IN_CANCEL)
    }

    suspend fun inferTurn(
        query: String,
        candidateSkills: List<String>,
        fewShots: List<String>,
        imageBytes: ByteArray? = null,
        uiTreeContext: String? = null,
        installedAppsContext: String? = null,
        recordedAccount: String? = null,
        resetConversation: Boolean = false
    ): RemoteLlmResult {
        val modelFile = modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)
            ?: throw LlmModelMissingException(
                "No local LLM installed. Open Settings to download or import Gemma 4 E2B (.litertlm)."
            )

        val messenger = remoteMessenger
            ?: throw LlmModelMissingException(
                "Process B (:llm) is not bound yet. Model '${modelFile.name}' requires the :llm service."
            )

        val reqId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<RemoteLlmResult>()
        pendingTurns[reqId] = deferred

        val msg = Message.obtain(null, LlmEngineService.MSG_INFER_TURN).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putString(LlmEngineService.KEY_REQUEST_ID, reqId)
                putString(LlmEngineService.KEY_QUERY, query)
                putStringArrayList(LlmEngineService.KEY_SKILLS, ArrayList(candidateSkills))
                putStringArrayList(LlmEngineService.KEY_FEW_SHOTS, ArrayList(fewShots))
                putBoolean(LlmEngineService.KEY_RESET_CONVERSATION, resetConversation)
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    putByteArray(LlmEngineService.KEY_IMAGE_BYTES, imageBytes)
                }
                if (!uiTreeContext.isNullOrBlank()) {
                    putString(LlmEngineService.KEY_UI_TREE_CONTEXT, uiTreeContext)
                }
                if (!installedAppsContext.isNullOrBlank()) {
                    putString(LlmEngineService.KEY_APPS_CONTEXT, installedAppsContext)
                }
                if (!recordedAccount.isNullOrBlank()) {
                    putString(LlmEngineService.KEY_RECORDED_ACCOUNT, recordedAccount)
                }
            }
        }
        runCatching { messenger.send(msg) }.onFailure {
            pendingTurns.remove(reqId)?.completeExceptionally(it)
        }

        return withTimeoutOrNull(30_000L) { deferred.await() }
            ?: run {
                pendingTurns.remove(reqId)
                throw IllegalStateException("Process B (:llm) inference timed out.")
            }
    }

    private fun sendControlMessage(what: Int) {
        val msg = Message.obtain(null, what).apply { replyTo = replyMessenger }
        runCatching { remoteMessenger?.send(msg) }
    }

    private fun parseCalls(jsonStr: String): List<ToolCallRequest> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ToolCallRequest>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val argsObj = obj.getJSONObject("args")
                val argsMap = mutableMapOf<String, String>()
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    argsMap[k] = argsObj.getString(k)
                }
                val tier = runCatching { SafetyTier.valueOf(obj.optString("tier", "GREEN")) }
                    .getOrDefault(SafetyTier.GREEN)
                list.add(
                    DeferredToolRegistry.validateToolCall(
                        ToolCallRequest(
                            toolName = obj.getString("tool"),
                            args = argsMap,
                            shortRefId = obj.optString("ref", "t01"),
                            safetyTier = tier
                        )
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "LlmProcessBridge"
    }
}
