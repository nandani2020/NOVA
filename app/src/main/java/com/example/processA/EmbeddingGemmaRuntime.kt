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

    @Volatile
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
                activeProcessInstance = this@EmbeddingGemmaRuntime
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
                activeProcessInstance = this@EmbeddingGemmaRuntime
                true
            } catch (ex: Exception) {
                Log.w(TAG, "EmbeddingGemma initialize failed: ${ex.message}")
                releaseInternal()
                _lastError.value = ex.message ?: "Failed to load EmbeddingGemma 2"
                false
            }
        }
    }

    suspend fun embedQuery(query: String): FloatArray? {
        if (query.isBlank()) return null
        return embedRaw(formatQueryPrompt(query))
    }

    suspend fun embedDocument(title: String, body: String): FloatArray? {
        if (title.isBlank() && body.isBlank()) return null
        return embedRaw(formatDocumentPrompt(title, body))
    }

    suspend fun embedDocumentsBatch(
        items: List<Pair<String, String>>,
        chunkSize: Int = 16
    ): List<FloatArray?> = withContext(dispatcher) {
        if (items.isEmpty()) return@withContext emptyList()
        if (!isModelFilePresent()) {
            releaseInternal()
            return@withContext List(items.size) { null }
        }
        if (!_isReady.value || engine == null) {
            val ok = warmup()
            if (!ok) return@withContext List(items.size) { null }
        }
        mutex.withLock {
            val active = engine ?: return@withLock List(items.size) { null }
            val results = MutableList<FloatArray?>(items.size) { null }
            val safeChunk = chunkSize.coerceIn(1, 32)
            var offset = 0
            while (offset < items.size) {
                val end = minOf(offset + safeChunk, items.size)
                val slice = items.subList(offset, end)
                val batchInputs = slice.map { (title, body) ->
                    listOf(InputData.Text(formatDocumentPrompt(title, body)))
                }
                val batchResp = runCatching {
                    active.computeEmbeddingBatch(batchInputs, EmbeddingOptions(normalize = true))
                }.onFailure {
                    Log.w(TAG, "computeEmbeddingBatch failed: ${it.message}")
                }.getOrNull()

                if (batchResp != null && batchResp.size == slice.size) {
                    batchResp.forEachIndexed { idx, resp ->
                        results[offset + idx] = normalizeL2(resp.embedding)
                    }
                } else {
                    slice.forEachIndexed { idx, (title, body) ->
                        val single = runCatching {
                            val resp = active.computeEmbedding(
                                listOf(InputData.Text(formatDocumentPrompt(title, body))),
                                EmbeddingOptions(normalize = true)
                            )
                            normalizeL2(resp.embedding)
                        }.getOrNull()
                        results[offset + idx] = single
                    }
                }
                offset = end
            }
            results
        }
    }

    suspend fun embedRaw(formattedText: String): FloatArray? = withContext(dispatcher) {
        if (formattedText.isBlank()) return@withContext null
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
        if (activeProcessInstance === this) {
            activeProcessInstance = null
        }
    }

    fun shutdown() {
        releaseInternal()
        dispatcher.close()
    }

    companion object {
        private const val TAG = "EmbeddingGemmaRuntime"

        @Volatile
        var activeProcessInstance: EmbeddingGemmaRuntime? = null
            private set

        /**
         * Official EmbeddingGemma 2 SearchQuery instruction prefix (`task: search result | query: ...`).
         */
        fun formatQueryPrompt(query: String): String =
            "task: search result | query: ${query.trim()}"

        /**
         * Official EmbeddingGemma 2 Document passage format (`title: {title | none} | text: {content}`).
         */
        fun formatDocumentPrompt(title: String, body: String): String {
            val cleanTitle = title.trim().ifEmpty { "none" }
            val cleanBody = body.trim()
            return "title: $cleanTitle | text: $cleanBody"
        }

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
