package com.pais.handy.data

import android.content.Context
import com.pais.handy.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.ConcurrentHashMap

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class ModelDownloader private constructor(context: Context) {
    companion object {
        private var instance: ModelDownloader? = null
        @Synchronized fun get(context: Context): ModelDownloader =
            instance ?: ModelDownloader(context.applicationContext).also { instance = it }
    }
    private val modelsDir: File = File(context.applicationContext.filesDir, "models").apply {
        mkdirs()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownloadProgress>> = _downloads.asStateFlow()

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    private val initialScan = scope.launch {
        for (model in ModelCatalog.models) {
            val file = modelFile(model)
            // Older installations have no trusted verification record.
            val valid = runCatching { model.matches(file) }.getOrDefault(false)
            synchronized(this@ModelDownloader) {
                if (valid && file.isFile && file.length() == model.sizeBytes) {
                    _installedIds.update { it + model.id }
                }
            }
        }
    }

    suspend fun awaitInitialScan() = initialScan.join()

    private fun modelFile(model: WhisperModelInfo): File = File(modelsDir, model.filename)

    fun isInstalled(modelId: String): Boolean = installedModelFile(modelId) != null

    fun installedModelFile(modelId: String): File? {
        if (modelId !in installedIds.value) return null
        val model = ModelCatalog.byId(modelId) ?: return null
        return modelFile(model).takeIf { it.isFile && it.length() == model.sizeBytes }
    }

    @Synchronized
    fun startDownload(model: WhisperModelInfo, onCompleted: (() -> Unit)? = null) {
        if (jobs.containsKey(model.id)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val target = modelFile(model)
            val partial = File(modelsDir, "${model.filename}.partial")
            try {
                _downloads.update {
                    it + (model.id to ModelDownloadProgress(0L, model.sizeBytes))
                }
                var existing = if (partial.isFile) partial.length() else 0L
                if (existing >= model.sizeBytes) {
                    check(partial.delete()) { "Could not reset partial download" }
                    existing = 0L
                }
                val connection = openFollowingRedirects(model.url, existing)
                try {
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                        throw IllegalStateException("HTTP $code")
                    }
                    val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
                    if (!append) existing = 0L

                    val contentLength = connection.contentLengthLong
                    val total = when {
                        contentLength > 0L && append -> existing + contentLength
                        contentLength > 0L -> contentLength
                        else -> model.sizeBytes
                    }

                    var written = existing
                    connection.inputStream.use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var lastEmit = 0L
                            while (isActive) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0) continue
                                if (written + n > model.sizeBytes) throw java.io.IOException("Model exceeds its expected size")
                                output.write(buffer, 0, n)
                                written += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 120L || written >= total) {
                                    lastEmit = now
                                    _downloads.update {
                                        it + (model.id to ModelDownloadProgress(written, total))
                                    }
                                }
                            }
                        }
                    }

                    if (!isActive) throw CancellationException("Cancelled")
                    if (!model.matches(partial)) {
                        partial.delete()
                        throw IllegalStateException("Model verification failed. Download the model again.")
                    }
                    synchronized(this@ModelDownloader) {
                        if (!isActive) throw CancellationException("Cancelled")
                        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        _installedIds.update { it + model.id }
                    }
                    _downloads.update { it - model.id }
                    onCompleted?.invoke()
                } finally {
                    connection.disconnect()
                }
            } catch (_: CancellationException) {
                _downloads.update { it - model.id }
            } catch (e: Exception) {
                _downloads.update {
                    it + (model.id to ModelDownloadProgress(0L, model.sizeBytes, error = e.message ?: "Download failed"))
                }
            }
        }
        jobs[model.id] = job
        job.invokeOnCompletion { jobs.remove(model.id, job) }
        job.start()
    }

    fun cancelDownload(modelId: String) {
        jobs[modelId]?.cancel()
        _downloads.update { it - modelId }
    }

    @Synchronized
    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        val model = ModelCatalog.byId(modelId) ?: return
        modelFile(model).delete()
        File(modelsDir, "${model.filename}.partial").delete()
        _installedIds.update { it - modelId }
    }

    private fun openFollowingRedirects(urlStr: String, rangeStart: Long): HttpURLConnection {
        var currentUrl = urlStr
        repeat(6) {
            val url = URL(currentUrl)
            require(url.protocol == "https" && url.userInfo == null) { "Model downloads require HTTPS" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Handy-Android/${BuildConfig.VERSION_NAME}")
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect without Location")
                currentUrl = URL(URL(currentUrl), loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects while downloading model")
    }
}
