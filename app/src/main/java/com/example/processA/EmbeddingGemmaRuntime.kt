package com.example.processA

import android.content.Context
import android.util.Log
import com.example.data.LlmModelManager
import com.example.data.ModelSlot
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Process A on-device EmbeddingGemma 2 runtime using `com.google.ai.edge.litertlm.EmbeddingEngine`.
 * Confined to CPU backend on a dedicated single-thread executor so Adreno GPU remains free
 * for Gemma 4 E2B in Process B (:llm).
 *
 * Strictly returns null / refuses to synthesize fake vectors when no EmbeddingGemma `.litertlm`
 * model file is installed on disk.
 */
class EmbeddingGemmaRuntime(
    private val context: Context,
    private val modelManager: LlmModelManager
) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutex = Mutex()

    private var engine: EmbeddingEngine? = null
    private var loadedPath: String? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun isModelFilePresent(): Boolean = modelManager.isModelPresent(ModelSlot.EMBEDDING)

    suspend fun warmup(): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val file = modelManager.getActiveModelFile(ModelSlot.EMBEDDING)
            if (file == null || !file.exists() || !file.isFile) {
                releaseInternal()
                _lastError.value = "EmbeddingGemma 2 model file not downloaded."
                return@withLock false
            }
            if (engine != null && loadedPath == file.absolutePath && _isReady.value) {
                return@withLock true
            }
            releaseInternal()
            return@withLock try {
                val cfg = EmbeddingEngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = File(context.cacheDir, "emb_cache").apply { mkdirs() }.absolutePath
                )
                val newEngine = EmbeddingEngine(cfg)
                newEngine.initialize()
                engine = newEngine
                loadedPath = file.absolutePath
                _isReady.value = true
                _lastError.value = null
                true
            } catch (ex: Exception) {
                Log.w(TAG, "EmbeddingGemma initialize failed: ${ex.message}")
                releaseInternal()
                _lastError.value = ex.message ?: "Failed to load EmbeddingGemma 2"
                false
            }
        }
    }

    suspend fun embedQuery(query: String): FloatArray? =
        embedRaw("task: search query | text: $query")

    suspend fun embedDocument(title: String, body: String): FloatArray? =
        embedRaw("title: $title | text: $body")

    suspend fun embedRaw(formattedText: String): FloatArray? = withContext(dispatcher) {
        if (!isModelFilePresent()) {
            releaseInternal()
            return@withContext null
        }
        if (!_isReady.value || engine == null) {
            val ok = warmup()
            if (!ok) return@withContext null
        }
        mutex.withLock {
            val active = engine ?: return@withLock null
            return@withLock runCatching {
                val response = active.computeEmbedding(
                    listOf(InputData.Text(formattedText)),
                    EmbeddingOptions(normalize = true)
                )
                normalizeL2(response.embedding)
            }.onFailure {
                Log.w(TAG, "computeEmbedding failed: ${it.message}")
            }.getOrNull()
        }
    }

    suspend fun unload() = withContext(dispatcher) {
        mutex.withLock {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
        _isReady.value = false
    }

    fun shutdown() {
        releaseInternal()
        dispatcher.close()
    }

    companion object {
        private const val TAG = "EmbeddingGemmaRuntime"

        fun normalizeL2(vec: FloatArray): FloatArray {
            var sum = 0f
            for (v in vec) sum += v * v
            val mag = sqrt(sum)
            if (mag <= 0f) return vec
            val out = FloatArray(vec.size)
            for (i in vec.indices) out[i] = vec[i] / mag
            return out
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot.coerceIn(-1f, 1f)
        }
    }
}
