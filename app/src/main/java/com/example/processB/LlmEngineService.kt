package com.example.processB

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.example.data.LlmModelManager
import com.example.data.ToolCallRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process B (:llm) Isolated Service (`android:process=":llm"`).
 * Loads the `.litertlm` model strictly from app-private disk storage (`filesDir/models`).
 * Reports MODEL_MISSING and refuses Tier 2/3 inference when no model file is installed.
 */
class LlmEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtime = LiteRtLmRuntimeWrapper()
    private val modelManager by lazy { LlmModelManager(applicationContext) }
    private var activeTurnJob: Job? = null

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            when (msg.what) {
                MSG_WARMUP -> {
                    val requestedPath = msg.data.getString(KEY_MODEL_PATH)
                        ?: modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)?.absolutePath
                    val preferNpu = msg.data.getBoolean(KEY_PREFER_NPU, false)
                    scope.launch {
                        runtime.warmup(
                            modelPath = requestedPath,
                            cacheDir = cacheDir.absolutePath,
                            nativeLibDir = applicationInfo.nativeLibraryDir,
                            preferNpu = preferNpu
                        )
                        sendStatusReply(replyTo, MSG_WARMUP_DONE)
                    }
                }

                MSG_UNLOAD_MODEL -> {
                    activeTurnJob?.cancel()
                    scope.launch {
                        runtime.unload()
                        sendStatusReply(replyTo, MSG_STATUS_REPLY)
                    }
                }

                MSG_PING_STATUS -> {
                    sendStatusReply(replyTo, MSG_STATUS_REPLY)
                }

                MSG_BARGE_IN_CANCEL -> {
                    runtime.preemptBargeIn()
                    activeTurnJob?.cancel()
                    sendStatusReply(replyTo, MSG_BARGE_IN_ACK)
                }

                MSG_INFER_TURN -> {
                    val reqId = msg.data.getString(KEY_REQUEST_ID).orEmpty()
                    val query = msg.data.getString(KEY_QUERY).orEmpty()
                    val skills = msg.data.getStringArrayList(KEY_SKILLS) ?: arrayListOf()
                    val fewShots = msg.data.getStringArrayList(KEY_FEW_SHOTS) ?: arrayListOf()
                    val imageBytes = msg.data.getByteArray(KEY_IMAGE_BYTES)
                    val uiTreeContext = msg.data.getString(KEY_UI_TREE_CONTEXT)
                    val appsContext = msg.data.getString(KEY_APPS_CONTEXT)
                    val recordedAccount = msg.data.getString(KEY_RECORDED_ACCOUNT)
                    val memoryContext = msg.data.getString(KEY_MEMORY_CONTEXT)
                    val personalityProfile = msg.data.getString(KEY_PERSONALITY_PROFILE)
                    val resetConversation = msg.data.getBoolean(KEY_RESET_CONVERSATION, false)
                    val allowThinking = msg.data.getBoolean(KEY_ALLOW_THINKING, true)

                    if (activeTurnJob?.isActive == true) {
                        runtime.preemptBargeIn()
                    }
                    activeTurnJob?.cancel()
                    activeTurnJob = scope.launch {
                        if (runtime.state.get() != LiteRtLmRuntimeWrapper.EngineState.READY) {
                            val activePath = modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)?.absolutePath
                            val loaded = runtime.warmup(
                                modelPath = activePath,
                                cacheDir = cacheDir.absolutePath,
                                nativeLibDir = applicationInfo.nativeLibraryDir,
                                preferNpu = false
                            )
                            if (!loaded) {
                                sendStatusReply(replyTo, MSG_STATUS_REPLY)
                                val err = Message.obtain(null, MSG_INFER_ABORTED).apply {
                                    data = Bundle().apply {
                                        putString(KEY_REQUEST_ID, reqId)
                                        putString(
                                            KEY_NOTE,
                                            runtime.lastError.get()
                                                ?: "LLM model not downloaded. Open Settings to download Gemma 4 E2B."
                                        )
                                        putBoolean(KEY_MODEL_MISSING, true)
                                    }
                                }
                                runCatching { replyTo?.send(err) }
                                return@launch
                            }
                        }

                        try {
                            val output = runtime.executeTurn(
                                query = query,
                                candidateSkillIds = skills,
                                fewShotHints = fewShots,
                                imageBytes = imageBytes,
                                uiTreeContext = uiTreeContext,
                                installedAppsContext = appsContext,
                                recordedAccount = recordedAccount,
                                memoryContext = memoryContext,
                                personalityProfile = personalityProfile,
                                resetConversation = resetConversation,
                                allowThinking = allowThinking
                            )
                            val reply = Message.obtain(null, MSG_INFER_RESULT).apply {
                                data = Bundle().apply {
                                    putString(KEY_REQUEST_ID, reqId)
                                    putString(KEY_SELECTED_SKILL, output.selectedSkillId)
                                    putBoolean(KEY_THINKING_ENABLED, output.thinkingEnabled)
                                    putLong(KEY_INFERENCE_MS, output.inferenceMs)
                                    putBoolean(KEY_PEG_RETRIED, output.pegRetried)
                                    putInt(KEY_PID, output.processPid)
                                    putString(KEY_NOTE, output.assistantReply)
                                    putString(KEY_TOOL_CALLS_JSON, serializeCalls(output.toolCalls))
                                }
                            }
                            runCatching { replyTo?.send(reply) }
                        } catch (ex: kotlinx.coroutines.CancellationException) {
                            Log.i(TAG, "Inference cancelled: ${ex.message}")
                            val err = Message.obtain(null, MSG_INFER_ABORTED).apply {
                                data = Bundle().apply {
                                    putString(KEY_REQUEST_ID, reqId)
                                    putString(KEY_NOTE, ex.message ?: "Cancelled")
                                    putBoolean(KEY_MODEL_MISSING, false)
                                    putBoolean(KEY_IS_ERROR, false)
                                }
                            }
                            runCatching { replyTo?.send(err) }
                        } catch (ex: Exception) {
                            Log.w(TAG, "Inference failed: ${ex.message}")
                            val err = Message.obtain(null, MSG_INFER_ABORTED).apply {
                                data = Bundle().apply {
                                    putString(KEY_REQUEST_ID, reqId)
                                    putString(KEY_NOTE, ex.message ?: "Local AI inference error")
                                    putBoolean(
                                        KEY_MODEL_MISSING,
                                        runtime.state.get() == LiteRtLmRuntimeWrapper.EngineState.MODEL_MISSING
                                    )
                                    putBoolean(KEY_IS_ERROR, true)
                                }
                            }
                            runCatching { replyTo?.send(err) }
                        }
                    }
                }

                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(incomingHandler)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val activePath = modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)?.absolutePath
            runtime.warmup(
                modelPath = activePath,
                cacheDir = cacheDir.absolutePath,
                nativeLibDir = applicationInfo.nativeLibraryDir,
                preferNpu = false
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        activeTurnJob?.cancel()
        runtime.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun sendStatusReply(replyTo: Messenger?, what: Int) {
        val activeFile = modelManager.getActiveModelFile(com.example.data.ModelSlot.LLM)
        val msg = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putBoolean(KEY_IS_READY, runtime.state.get() == LiteRtLmRuntimeWrapper.EngineState.READY)
                putString(KEY_STATE_NAME, runtime.state.get().name)
                putString(KEY_ACTIVE_BACKEND, runtime.activeBackend.get())
                putInt(KEY_PID, Process.myPid())
                putBoolean(KEY_MODEL_MISSING, activeFile == null)
                putLong(KEY_NATIVE_HEAP_MB, Debug.getNativeHeapAllocatedSize() / (1024L * 1024L))
            }
        }
        runCatching { replyTo?.send(msg) }
    }

    private fun serializeCalls(calls: List<ToolCallRequest>): String {
        val arr = JSONArray()
        calls.forEach { c ->
            val obj = JSONObject().apply {
                put("tool", c.toolName)
                put("ref", c.shortRefId)
                put("tier", c.safetyTier.name)
                put("args", JSONObject(c.args))
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "LlmEngineService"
        const val MSG_WARMUP = 1
        const val MSG_WARMUP_DONE = 2
        const val MSG_INFER_TURN = 3
        const val MSG_INFER_RESULT = 4
        const val MSG_INFER_ABORTED = 5
        const val MSG_BARGE_IN_CANCEL = 6
        const val MSG_BARGE_IN_ACK = 7
        const val MSG_PING_STATUS = 8
        const val MSG_STATUS_REPLY = 9
        const val MSG_UNLOAD_MODEL = 11

        const val KEY_REQUEST_ID = "req_id"
        const val KEY_QUERY = "query"
        const val KEY_SKILLS = "skills"
        const val KEY_FEW_SHOTS = "few_shots"
        const val KEY_IMAGE_BYTES = "image_bytes"
        const val KEY_UI_TREE_CONTEXT = "ui_tree_context"
        const val KEY_APPS_CONTEXT = "apps_context"
        const val KEY_RECORDED_ACCOUNT = "recorded_account"
        const val KEY_MEMORY_CONTEXT = "memory_context"
        const val KEY_PERSONALITY_PROFILE = "personality_profile"
        const val KEY_RESET_CONVERSATION = "reset_conversation"
        const val KEY_ALLOW_THINKING = "allow_thinking"
        const val KEY_PREFER_NPU = "prefer_npu"
        const val KEY_ACTIVE_BACKEND = "active_backend"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_IS_READY = "is_ready"
        const val KEY_STATE_NAME = "state_name"
        const val KEY_PID = "pid"
        const val KEY_MODEL_MISSING = "model_missing"
        const val KEY_IS_ERROR = "is_error"
        const val KEY_NATIVE_HEAP_MB = "native_heap_mb"
        const val KEY_SELECTED_SKILL = "selected_skill"
        const val KEY_THINKING_ENABLED = "thinking_enabled"
        const val KEY_INFERENCE_MS = "inference_ms"
        const val KEY_PEG_RETRIED = "peg_retried"
        const val KEY_NOTE = "note"
        const val KEY_TOOL_CALLS_JSON = "tool_calls_json"
    }
}
