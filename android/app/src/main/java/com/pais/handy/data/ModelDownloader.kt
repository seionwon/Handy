package com.pais.handy.data

import android.content.Context
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
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class ModelDownloader(context: Context) {
    private val modelsDir: File = File(context.applicationContext.filesDir, "models").apply {
        mkdirs()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, ModelDownloadProgress>> = _downloads.asStateFlow()

    private val _installedIds = MutableStateFlow(scanInstalled())
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    fun modelFile(model: WhisperModelInfo): File = File(modelsDir, model.filename)

    fun isInstalled(modelId: String): Boolean {
        val model = ModelCatalog.byId(modelId) ?: return false
        val file = modelFile(model)
        return file.isFile && file.length() > 1_000_000L
    }

    fun installedModelFile(modelId: String): File? {
        val model = ModelCatalog.byId(modelId) ?: return null
        val file = modelFile(model)
        return if (file.isFile && file.length() > 1_000_000L) file else null
    }

    fun refreshInstalled() {
        _installedIds.value = scanInstalled()
    }

    private fun scanInstalled(): Set<String> =
        ModelCatalog.models
            .filter { model ->
                val f = modelFile(model)
                f.isFile && f.length() > 1_000_000L
            }
            .map { it.id }
            .toSet()

    fun startDownload(model: WhisperModelInfo, onCompleted: (() -> Unit)? = null) {
        if (jobs.containsKey(model.id)) return
        val job = scope.launch {
            val target = modelFile(model)
            val partial = File(modelsDir, "${model.filename}.partial")
            try {
                _downloads.update {
                    it + (model.id to ModelDownloadProgress(0L, model.sizeBytes))
                }
                var existing = if (partial.isFile) partial.length() else 0L
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
                    if (partial.length() < 1_000_000L) {
                        partial.delete()
                        throw IllegalStateException("Downloaded file is unexpectedly small")
                    }
                    if (target.exists()) target.delete()
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                    _downloads.update { it - model.id }
                    refreshInstalled()
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
            } finally {
                jobs.remove(model.id)
            }
        }
        jobs[model.id] = job
    }

    fun cancelDownload(modelId: String) {
        jobs.remove(modelId)?.cancel()
        _downloads.update { it - modelId }
    }

    fun deleteModel(modelId: String) {
        cancelDownload(modelId)
        val model = ModelCatalog.byId(modelId) ?: return
        modelFile(model).delete()
        File(modelsDir, "${model.filename}.partial").delete()
        refreshInstalled()
    }

    private fun openFollowingRedirects(urlStr: String, rangeStart: Long): HttpURLConnection {
        var currentUrl = urlStr
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Handy-Android/0.10.1")
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
