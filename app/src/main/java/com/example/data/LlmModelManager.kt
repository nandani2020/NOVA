package com.example.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

enum class ModelSlot { LLM, EMBEDDING }

data class LlmModelPreset(
    val id: String,
    val slot: ModelSlot,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val approxSizeLabel: String
)

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(
        val slot: ModelSlot,
        val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float
    ) : ModelDownloadState

    data class Completed(val slot: ModelSlot, val fileName: String, val sizeBytes: Long) : ModelDownloadState
    data class Failed(val slot: ModelSlot, val message: String) : ModelDownloadState
}

/**
 * Manages real on-device `.litertlm` model files in `context.filesDir/models/llm`
 * and `context.filesDir/models/embedding`.
 * - Zero fake/bundled models in assets or APK.
 * - Supports Gemma 4 E2B IT (core LLM) and EmbeddingGemma 2 270M (Tier 1.5 embeddings).
 */
class LlmModelManager(private val context: Context) {

    val modelsRootDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val llmDir: File = File(modelsRootDir, "llm").apply { mkdirs() }
    val embeddingDir: File = File(modelsRootDir, "embedding").apply { mkdirs() }

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var cancelRequested = false

    val llmPreset = LlmModelPreset(
        id = "gemma_4_e2b",
        slot = ModelSlot.LLM,
        displayName = "Gemma 4 E2B IT (.litertlm)",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeLabel = "~2.58 GB"
    )

    val embeddingPreset = LlmModelPreset(
        id = "embedding_gemma_2_270m",
        slot = ModelSlot.EMBEDDING,
        displayName = "EmbeddingGemma 2 270M (.litertlm)",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        approxSizeLabel = "~165 MB"
    )

    val presets: List<LlmModelPreset> = listOf(llmPreset, embeddingPreset)

    private fun dirFor(slot: ModelSlot): File = when (slot) {
        ModelSlot.LLM -> llmDir
        ModelSlot.EMBEDDING -> embeddingDir
    }

    fun getActiveModelFile(slot: ModelSlot = ModelSlot.LLM): File? {
        val dir = dirFor(slot)
        val files = dir.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".part") && it.length() > MIN_MODEL_BYTES
        }.orEmpty()
        return files.maxByOrNull { it.lastModified() }
    }

    fun isModelPresent(slot: ModelSlot = ModelSlot.LLM): Boolean = getActiveModelFile(slot) != null

    fun cancelDownload() {
        cancelRequested = true
    }

    fun deleteActiveModel(slot: ModelSlot = ModelSlot.LLM): Boolean {
        var deletedAny = false
        dirFor(slot).listFiles()?.forEach { file ->
            if (file.delete()) deletedAny = true
        }
        _downloadState.value = ModelDownloadState.Idle
        return deletedAny
    }

    suspend fun downloadModel(
        slot: ModelSlot,
        urlString: String,
        fileName: String,
        bearerToken: String = ""
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelRequested = false
        val targetDir = dirFor(slot)
        val safeName = fileName.trim().ifEmpty {
            if (slot == ModelSlot.LLM) "gemma-4-E2B-it.litertlm" else "embeddinggemma-2.litertlm"
        }
        val targetFile = File(targetDir, safeName)
        val tempFile = File(targetDir, "$safeName.part")

        var connection: HttpURLConnection? = null
        try {
            _downloadState.value = ModelDownloadState.Downloading(slot, safeName, 0L, -1L, 0f)
            var currentUrl = urlString.trim()
            var redirects = 0
            while (true) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    if (bearerToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank() || ++redirects > 8) {
                        throw IllegalStateException("Too many redirects ($code)")
                    }
                    currentUrl = URL(url, location).toString()
                } else {
                    connection = conn
                    break
                }
            }

            val conn = checkNotNull(connection)
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = when (code) {
                    401, 403 -> "HTTP $code: Access denied. Add your Hugging Face token or accept the model license."
                    404 -> "HTTP 404: Model file not found at URL."
                    else -> "HTTP $code: ${conn.responseMessage}"
                }
                _downloadState.value = ModelDownloadState.Failed(slot, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val totalBytes = conn.contentLengthLong
            var downloaded = 0L
            val buffer = ByteArray(64 * 1024)

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (isActive && !cancelRequested) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (totalBytes > 0) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                        _downloadState.value = ModelDownloadState.Downloading(
                            slot = slot,
                            fileName = safeName,
                            bytesDownloaded = downloaded,
                            totalBytes = totalBytes,
                            progress = progress
                        )
                    }
                }
            }

            if (cancelRequested || !isActive) {
                tempFile.delete()
                _downloadState.value = ModelDownloadState.Idle
                return@withContext Result.failure(CancellationException("Download cancelled"))
            }

            if (tempFile.length() <= MIN_MODEL_BYTES) {
                tempFile.delete()
                val err = "Downloaded file is too small ($downloaded bytes) to be a valid .litertlm model."
                _downloadState.value = ModelDownloadState.Failed(slot, err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            // Replace any previous model in this slot
            targetDir.listFiles()?.forEach { existing ->
                if (existing.absolutePath != tempFile.absolutePath) existing.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IllegalStateException("Failed to finalize model file on disk")
            }

            _downloadState.value = ModelDownloadState.Completed(slot, safeName, targetFile.length())
            Result.success(targetFile)
        } catch (ex: CancellationException) {
            tempFile.delete()
            _downloadState.value = ModelDownloadState.Idle
            Result.failure(ex)
        } catch (ex: Exception) {
            tempFile.delete()
            val msg = ex.message ?: "Model download failed"
            _downloadState.value = ModelDownloadState.Failed(slot, msg)
            Result.failure(ex)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun importLocalModel(slot: ModelSlot, uri: Uri, displayName: String?): Result<File> =
        withContext(Dispatchers.IO) {
            val targetDir = dirFor(slot)
            val name = (displayName?.takeIf { it.isNotBlank() } ?: "imported_${slot.name.lowercase()}.litertlm")
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val target = File(targetDir, name)
            val temp = File(targetDir, "$name.part")
            return@withContext try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: return@withContext Result.failure(IllegalStateException("Unable to read selected file"))

                if (temp.length() <= MIN_MODEL_BYTES) {
                    temp.delete()
                    return@withContext Result.failure(IllegalStateException("Selected file is empty or invalid."))
                }
                targetDir.listFiles()?.forEach { existing ->
                    if (existing.absolutePath != temp.absolutePath) existing.delete()
                }
                temp.renameTo(target)
                _downloadState.value = ModelDownloadState.Completed(slot, name, target.length())
                Result.success(target)
            } catch (ex: Exception) {
                temp.delete()
                _downloadState.value = ModelDownloadState.Failed(slot, ex.message ?: "Import failed")
                Result.failure(ex)
            }
        }

    companion object {
        // Real .litertlm bundles are > 1 KB (prevents HTML error pages / empty files)
        private const val MIN_MODEL_BYTES = 1024L
    }
}
