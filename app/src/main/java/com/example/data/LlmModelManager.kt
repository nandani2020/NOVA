package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class ModelSlot { LLM, EMBEDDING, ASR, TTS }

data class LlmModelPreset(
    val id: String,
    val slot: ModelSlot,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val approxSizeLabel: String,
    val notes: String = ""
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
 * Manages real on-device model files and extracted `.zip` bundles across four slots:
 * - `context.filesDir/models/llm` (Gemma 4 E2B IT `.litertlm`)
 * - `context.filesDir/models/embedding` (EmbeddingGemma 2 270M `.litertlm`)
 * - `context.filesDir/models/asr` (Whisper / Moonshine / Sherpa-ONNX Zipformer `.onnx` / `.tflite` / `.zip`)
 * - `context.filesDir/models/tts` (Kokoro-82M / Piper VITS `.onnx` / `.zip`)
 *
 * Supports multiple installed models per slot, active model switching, safe Zip-Slip-guarded bundle
 * extraction, HTML error-page rejection, and reliable file finalization across Android storage providers.
 */
class LlmModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val modelsRootDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val llmDir: File = File(modelsRootDir, "llm").apply { mkdirs() }
    val embeddingDir: File = File(modelsRootDir, "embedding").apply { mkdirs() }
    val asrDir: File = File(modelsRootDir, "asr").apply { mkdirs() }
    val ttsDir: File = File(modelsRootDir, "tts").apply { mkdirs() }

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var cancelRequested = false

    val llmPreset = LlmModelPreset(
        id = "gemma_4_e2b",
        slot = ModelSlot.LLM,
        displayName = "Gemma 4 E2B IT",
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxSizeLabel = "~2.58 GB",
        notes = "Multimodal (text + vision) with thinking & tool calling. Best quality; needs ~4+ GB free RAM."
    )

    val llmPresets: List<LlmModelPreset> = listOf(
        llmPreset,
        LlmModelPreset(
            id = "gemma_3n_e2b_int4",
            slot = ModelSlot.LLM,
            displayName = "Gemma 3n E2B (int4)",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            downloadUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            approxSizeLabel = "~3.0 GB",
            notes = "Multimodal MatFormer edge model. Fast per-layer decode; gated on HF (accept license & paste HF token)."
        ),
        LlmModelPreset(
            id = "lfm25_1_2b_int4_gpu",
            slot = ModelSlot.LLM,
            displayName = "LFM2.5 1.2B Instruct (Fast)",
            fileName = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
            approxSizeLabel = "~750 MB",
            notes = "Liquid AI hybrid model — 2-3x faster & lower RAM than 2B models, no HF token required (text-only)."
        ),
        LlmModelPreset(
            id = "qwen25_1_5b_q8",
            slot = ModelSlot.LLM,
            displayName = "Qwen 2.5 1.5B Instruct",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            approxSizeLabel = "~1.5 GB",
            notes = "Clean English/multilingual chat & reliable JSON tool calling, 4K context, no HF token required (text-only)."
        ),
        LlmModelPreset(
            id = "lfm2_700m_int4",
            slot = ModelSlot.LLM,
            displayName = "LFM2 700M (Ultra-Light)",
            fileName = "LFM2-700M_int4.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/LFM2-700M/resolve/main/LFM2-700M_int4.litertlm",
            approxSizeLabel = "~481 MB",
            notes = "Smallest & fastest Liquid AI model for low-RAM devices, no HF token required (text-only)."
        )
    )

    val embeddingPreset = LlmModelPreset(
        id = "embedding_gemma_2_270m",
        slot = ModelSlot.EMBEDDING,
        displayName = "EmbeddingGemma 2 270M (.litertlm)",
        fileName = "embeddinggemma-2-text-270m.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        approxSizeLabel = "~165 MB",
        notes = "Tier 1.5 semantic routing & hybrid FTS5 vector memory"
    )

    val asrPresets: List<LlmModelPreset> = listOf(
        LlmModelPreset(
            id = "whisper_tiny_en_onnx",
            slot = ModelSlot.ASR,
            displayName = "Whisper Tiny EN (ONNX)",
            fileName = "whisper-tiny-en-encoder.int8.onnx",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx",
            approxSizeLabel = "~13 MB",
            notes = "Compact English speech recognition encoder (import full .zip bundle for encoder+decoder+tokens)"
        ),
        LlmModelPreset(
            id = "moonshine_tiny_onnx",
            slot = ModelSlot.ASR,
            displayName = "Moonshine Tiny EN (ONNX)",
            fileName = "moonshine-tiny-encode.int8.onnx",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main/encode.int8.onnx",
            approxSizeLabel = "~19 MB",
            notes = "Ultra-fast low-latency edge ASR optimized for short voice commands"
        ),
        LlmModelPreset(
            id = "zipformer_en_streaming",
            slot = ModelSlot.ASR,
            displayName = "Zipformer Streaming EN (ONNX)",
            fileName = "zipformer-en-streaming.onnx",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            approxSizeLabel = "~64 MB",
            notes = "Streaming real-time Sherpa-ONNX transducer for hands-free listening"
        )
    )

    val ttsPresets: List<LlmModelPreset> = listOf(
        LlmModelPreset(
            id = "kokoro_82m_int8",
            slot = ModelSlot.TTS,
            displayName = "Kokoro-82M v1.0 Int8 (ONNX)",
            fileName = "kokoro-v1.0.int8.onnx",
            downloadUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx",
            approxSizeLabel = "~92 MB",
            notes = "Recommended natural studio-quality 82M voice (also import Kokoro .zip bundles with voices.bin & tokens.txt)"
        ),
        LlmModelPreset(
            id = "piper_en_us_lessac",
            slot = ModelSlot.TTS,
            displayName = "Piper VITS en_US Lessac (ONNX)",
            fileName = "en_US-lessac-medium.onnx",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            approxSizeLabel = "~63 MB",
            notes = "Fast, crisp offline neural voice with very low CPU overhead"
        ),
        LlmModelPreset(
            id = "piper_en_us_amy",
            slot = ModelSlot.TTS,
            displayName = "Piper VITS en_US Amy (ONNX)",
            fileName = "en_US-amy-medium.onnx",
            downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx",
            approxSizeLabel = "~63 MB",
            notes = "Warm conversational offline neural voice"
        )
    )

    val presets: List<LlmModelPreset> = llmPresets + embeddingPreset
    val allPresets: List<LlmModelPreset> = llmPresets + embeddingPreset + asrPresets + ttsPresets

    fun presetsFor(slot: ModelSlot): List<LlmModelPreset> = when (slot) {
        ModelSlot.LLM -> llmPresets
        ModelSlot.EMBEDDING -> listOf(embeddingPreset)
        ModelSlot.ASR -> asrPresets
        ModelSlot.TTS -> ttsPresets
    }

    fun dirFor(slot: ModelSlot): File = when (slot) {
        ModelSlot.LLM -> llmDir
        ModelSlot.EMBEDDING -> embeddingDir
        ModelSlot.ASR -> asrDir
        ModelSlot.TTS -> ttsDir
    }

    private fun activePrefKey(slot: ModelSlot): String = "active_model_${slot.name.lowercase()}"

    private fun computeSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun isValidEntry(entry: File): Boolean {
        if (!entry.exists() || entry.name.endsWith(".part") || entry.name.startsWith(".")) return false
        return computeSizeBytes(entry) > MIN_MODEL_BYTES
    }

    fun listInstalledModels(slot: ModelSlot): List<InstalledModelInfo> {
        val dir = dirFor(slot)
        val entries = dir.listFiles()?.filter { isValidEntry(it) }.orEmpty()
        if (entries.isEmpty()) return emptyList()
        val activeFile = getActiveModelFile(slot)
        return entries
            .sortedByDescending { it.lastModified() }
            .map { file ->
                InstalledModelInfo(
                    slot = slot,
                    fileName = file.name,
                    path = file.absolutePath,
                    sizeBytes = computeSizeBytes(file),
                    isBundleDir = file.isDirectory,
                    modifiedMs = file.lastModified(),
                    isActive = activeFile?.name == file.name
                )
            }
    }

    fun setActiveModel(slot: ModelSlot, fileName: String): Boolean {
        val candidate = File(dirFor(slot), fileName)
        if (!isValidEntry(candidate)) return false
        prefs.edit().putString(activePrefKey(slot), candidate.name).apply()
        return true
    }

    fun getActiveModelFile(slot: ModelSlot = ModelSlot.LLM): File? {
        val dir = dirFor(slot)
        val validEntries = dir.listFiles()?.filter { isValidEntry(it) }.orEmpty()
        if (validEntries.isEmpty()) return null

        val preferredName = prefs.getString(activePrefKey(slot), null)
        if (!preferredName.isNullOrBlank()) {
            val preferred = validEntries.firstOrNull { it.name == preferredName }
            if (preferred != null) return preferred
        }
        return validEntries.maxByOrNull { it.lastModified() }
    }

    fun getActiveModelSizeBytes(slot: ModelSlot): Long {
        val active = getActiveModelFile(slot) ?: return 0L
        return computeSizeBytes(active)
    }

    fun isModelPresent(slot: ModelSlot = ModelSlot.LLM): Boolean = getActiveModelFile(slot) != null

    fun cancelDownload() {
        cancelRequested = true
    }

    fun deleteModel(slot: ModelSlot, fileName: String): Boolean {
        val target = File(dirFor(slot), fileName)
        if (!target.exists()) return false
        val deleted = target.deleteRecursively()
        if (deleted) {
            if (prefs.getString(activePrefKey(slot), null) == fileName) {
                val next = dirFor(slot).listFiles()?.filter { isValidEntry(it) }?.maxByOrNull { it.lastModified() }
                prefs.edit().putString(activePrefKey(slot), next?.name).apply()
            }
            _downloadState.value = ModelDownloadState.Idle
        }
        return deleted
    }

    fun deleteActiveModel(slot: ModelSlot = ModelSlot.LLM): Boolean {
        var deletedAny = false
        dirFor(slot).listFiles()?.forEach { file ->
            if (file.deleteRecursively()) deletedAny = true
        }
        prefs.edit().remove(activePrefKey(slot)).apply()
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
        val targetDir = dirFor(slot).apply { mkdirs() }
        val safeName = sanitizeModelFileName(fileName, slot)
        val targetFile = File(targetDir, safeName)
        val tempFile = File(targetDir, "$safeName.part")

        var connection: HttpURLConnection? = null
        try {
            _downloadState.value = ModelDownloadState.Downloading(slot, safeName, 0L, -1L, 0f)
            var currentUrl = urlString.trim()
            if (currentUrl.isEmpty()) {
                throw IllegalArgumentException("Model URL cannot be empty.")
            }
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

            validateTempModelFile(tempFile, slot)

            val finalized = finalizeStagedModel(
                slot = slot,
                tempFile = tempFile,
                targetDir = targetDir,
                safeName = safeName,
                replaceOtherSlotFiles = slot == ModelSlot.LLM || slot == ModelSlot.EMBEDDING
            )
            prefs.edit().putString(activePrefKey(slot), finalized.name).apply()
            val finalSize = computeSizeBytes(finalized)
            _downloadState.value = ModelDownloadState.Completed(slot, finalized.name, finalSize)
            Result.success(finalized)
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
            cancelRequested = false
            val targetDir = dirFor(slot).apply { mkdirs() }
            val resolvedName = resolveDisplayName(uri, displayName, slot)
            val safeName = sanitizeModelFileName(resolvedName, slot)
            val temp = File(targetDir, "$safeName.part")
            return@withContext try {
                val expectedBytes = queryUriSizeBytes(uri)
                _downloadState.value = ModelDownloadState.Downloading(slot, safeName, 0L, expectedBytes, 0f)

                var copied = 0L
                val buffer = ByteArray(64 * 1024)
                val opened = openModelInputStream(uri)
                    ?: run {
                        val err = "Unable to open selected file. Try choosing from local Downloads or Files."
                        _downloadState.value = ModelDownloadState.Failed(slot, err)
                        return@withContext Result.failure(IllegalStateException(err))
                    }

                opened.use { input ->
                    FileOutputStream(temp).use { output ->
                        while (isActive && !cancelRequested) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                            val progress = if (expectedBytes > 0L) {
                                (copied.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            _downloadState.value = ModelDownloadState.Downloading(
                                slot = slot,
                                fileName = safeName,
                                bytesDownloaded = copied,
                                totalBytes = expectedBytes,
                                progress = progress
                            )
                        }
                    }
                }

                if (cancelRequested || !isActive) {
                    temp.delete()
                    _downloadState.value = ModelDownloadState.Idle
                    return@withContext Result.failure(CancellationException("Import cancelled"))
                }

                validateTempModelFile(temp, slot)

                val finalized = finalizeStagedModel(
                    slot = slot,
                    tempFile = temp,
                    targetDir = targetDir,
                    safeName = safeName,
                    // Preserve multiple models in ASR & TTS slots so users can switch models cleanly
                    replaceOtherSlotFiles = false
                )
                prefs.edit().putString(activePrefKey(slot), finalized.name).apply()
                val finalSize = computeSizeBytes(finalized)
                _downloadState.value = ModelDownloadState.Completed(slot, finalized.name, finalSize)
                Result.success(finalized)
            } catch (ex: CancellationException) {
                temp.delete()
                _downloadState.value = ModelDownloadState.Idle
                Result.failure(ex)
            } catch (ex: Exception) {
                temp.delete()
                val msg = ex.message ?: "Import failed"
                _downloadState.value = ModelDownloadState.Failed(slot, msg)
                Result.failure(ex)
            }
        }

    private fun validateTempModelFile(tempFile: File, slot: ModelSlot) {
        val isZip = tempFile.name.removeSuffix(".part").endsWith(".zip", ignoreCase = true)
        val minBytes = if (isZip) 64L else MIN_MODEL_BYTES
        if (!tempFile.exists() || tempFile.length() <= minBytes) {
            throw IllegalStateException(
                "Selected ${slot.name} model file is too small (${tempFile.length()} bytes) or empty."
            )
        }
        // Reject HTML error / login pages accidentally downloaded or picked
        val header = ByteArray(256)
        val read = FileInputStream(tempFile).use { it.read(header) }.coerceAtLeast(0)
        val snippet = String(header, 0, read, Charsets.UTF_8).trimStart().lowercase()
        if (snippet.startsWith("<!doctype html") || snippet.startsWith("<html")) {
            throw IllegalStateException(
                "File appears to be an HTML web page instead of a valid model binary. Check the download URL or file."
            )
        }
    }

    private fun finalizeStagedModel(
        slot: ModelSlot,
        tempFile: File,
        targetDir: File,
        safeName: String,
        replaceOtherSlotFiles: Boolean
    ): File {
        if (safeName.endsWith(".zip", ignoreCase = true)) {
            val bundleName = safeName.removeSuffix(".zip").removeSuffix(".ZIP")
                .ifBlank { "bundle_${slot.name.lowercase()}" }
            val bundleDir = File(targetDir, bundleName)
            if (bundleDir.exists()) {
                bundleDir.deleteRecursively()
            }
            bundleDir.mkdirs()
            extractZipSafely(tempFile, bundleDir)
            tempFile.delete()
            val extractedBytes = computeSizeBytes(bundleDir)
            if (extractedBytes <= MIN_MODEL_BYTES) {
                bundleDir.deleteRecursively()
                throw IllegalStateException("ZIP archive did not contain valid model files.")
            }
            if (replaceOtherSlotFiles) {
                targetDir.listFiles()?.forEach { existing ->
                    if (existing.absolutePath != bundleDir.absolutePath) {
                        existing.deleteRecursively()
                    }
                }
            }
            bundleDir.setLastModified(System.currentTimeMillis())
            return bundleDir
        }

        val target = File(targetDir, safeName)
        if (replaceOtherSlotFiles) {
            targetDir.listFiles()?.forEach { existing ->
                if (existing.absolutePath != tempFile.absolutePath) {
                    existing.deleteRecursively()
                }
            }
        } else if (target.exists() && target.absolutePath != tempFile.absolutePath) {
            target.deleteRecursively()
        }

        val moved = tempFile.renameTo(target)
        if (!moved) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        if (!target.exists() || target.length() <= MIN_MODEL_BYTES) {
            throw IllegalStateException("Failed to finalize model file '${target.name}' on internal storage.")
        }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    private fun extractZipSafely(zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        var extractedFiles = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(64 * 1024)
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    throw SecurityException("Blocked unsafe ZIP entry path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val count = zis.read(buffer)
                            if (count == -1) break
                            fos.write(buffer, 0, count)
                        }
                    }
                    extractedFiles++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (extractedFiles == 0) {
            throw IllegalStateException("ZIP archive was empty or corrupt.")
        }
    }

    private fun resolveDisplayName(uri: Uri, providedDisplayName: String?, slot: ModelSlot): String {
        val fromArg = providedDisplayName?.trim()?.takeIf { it.isNotBlank() }
        if (fromArg != null) return fromArg

        val fromCursor = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.trim() else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fromCursor != null) return fromCursor

        val fromSegment = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (fromSegment != null) return fromSegment

        return defaultFileNameFor(slot)
    }

    private fun openModelInputStream(uri: Uri): java.io.InputStream? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val filePath = uri.path
            if (!filePath.isNullOrBlank()) {
                val f = File(filePath)
                if (f.exists() && f.isFile && f.canRead()) {
                    return FileInputStream(f)
                }
            }
        }
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private fun queryUriSizeBytes(uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val p = uri.path
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.exists() && f.isFile) return f.length()
            }
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun sanitizeModelFileName(rawName: String, slot: ModelSlot): String {
        val cleaned = rawName.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('_')
        if (cleaned.isEmpty()) return defaultFileNameFor(slot)
        val hasKnownExt = KNOWN_EXTENSIONS.any { cleaned.endsWith(it, ignoreCase = true) }
        return if (hasKnownExt || cleaned.contains('.')) cleaned else "$cleaned${defaultExtensionFor(slot)}"
    }

    private fun defaultFileNameFor(slot: ModelSlot): String = when (slot) {
        ModelSlot.LLM -> "gemma-4-E2B-it.litertlm"
        ModelSlot.EMBEDDING -> "embeddinggemma-2-text-270m.litertlm"
        ModelSlot.ASR -> "imported_asr_model.onnx"
        ModelSlot.TTS -> "imported_tts_model.onnx"
    }

    private fun defaultExtensionFor(slot: ModelSlot): String = when (slot) {
        ModelSlot.LLM, ModelSlot.EMBEDDING -> ".litertlm"
        ModelSlot.ASR, ModelSlot.TTS -> ".onnx"
    }

    companion object {
        private const val PREFS_NAME = "nova_model_manager_prefs"
        // Real model binaries / bundles are > 1 KB (prevents empty files or tiny error stubs)
        private const val MIN_MODEL_BYTES = 1024L
        private val KNOWN_EXTENSIONS = listOf(
            ".litertlm", ".onnx", ".tflite", ".bin", ".gguf", ".zip", ".ort", ".pt", ".task"
        )
    }
}
