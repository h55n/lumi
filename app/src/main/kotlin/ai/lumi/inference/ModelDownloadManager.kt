package ai.lumi.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val model: ModelSpec, val progressPercent: Int) : DownloadState()
    data class Verifying(val model: ModelSpec) : DownloadState()
    data class Complete(val model: ModelSpec, val file: File) : DownloadState()
    data class Failed(val model: ModelSpec, val reason: String) : DownloadState()
    data object WifiRequired : DownloadState()
    /**
     * Emitted when a model is not directly downloadable (e.g. Qualcomm SDK model) or when
     * a download attempt returns a 4xx status code. The app will use Cloud/Groq mode for
     * that capability instead of showing a hard error.
     */
    data class CloudMode(val model: ModelSpec, val reason: String) : DownloadState()
}


@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    fun modelFile(spec: ModelSpec): File =
        File(modelsDir, "${spec.subDir}/${spec.fileName}")

    fun isDownloaded(spec: ModelSpec): Boolean {
        val file = modelFile(spec)
        if (!file.exists()) return false
        // Validate that at least 90% of expected bytes are present (guards against truncated downloads)
        val minSize = (spec.sizeBytes * 0.9).toLong()
        return file.length() >= minSize
    }

    /**
     * Downloads [spec] and emits progress via [Flow<DownloadState>].
     * Enforces WiFi unless [allowCellular] is true.
     */
    fun download(spec: ModelSpec, allowCellular: Boolean = true): Flow<DownloadState> = flow {
        if (!allowCellular && !isOnWifi()) {
            emit(DownloadState.WifiRequired)
            return@flow
        }

        // Non-downloadable models (e.g. Qualcomm SDK) — emit CloudMode immediately
        if (!spec.isDirectDownloadable) {
            Timber.i("${spec.displayName} is not directly downloadable — Cloud mode activated")
            emit(DownloadState.CloudMode(spec, "Requires vendor SDK — using Cloud mode"))
            return@flow
        }

        val destFile = modelFile(spec)
        destFile.parentFile?.mkdirs()

        Timber.i("Downloading ${spec.displayName} → ${destFile.path}")
        emit(DownloadState.Downloading(spec, 0))

        try {
            val request = Request.Builder().url(spec.downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                // 4xx responses mean the model isn't accessible — fall back to Cloud/Groq mode
                val is4xx = response.code in 400..499
                if (is4xx) {
                    Timber.w("${spec.displayName} returned HTTP ${response.code} — activating Cloud mode")
                    emit(DownloadState.CloudMode(spec, "Model unavailable (HTTP ${response.code}) — Cloud mode active"))
                } else {
                    emit(DownloadState.Failed(spec, "HTTP ${response.code}"))
                }
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Failed(spec, "Empty response body"))
                return@flow
            }

            val totalBytes = body.contentLength().takeIf { it > 0 } ?: spec.sizeBytes
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastEmittedProgress = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        // Emit every 1% to avoid flooding the flow
                        if (progress != lastEmittedProgress) {
                            lastEmittedProgress = progress
                            emit(DownloadState.Downloading(spec, progress))
                        }
                    }
                }
            }

            emit(DownloadState.Verifying(spec))
            val valid = verifySha256(destFile, spec.sha256)
            if (!valid) {
                destFile.delete()
                emit(DownloadState.Failed(spec, "SHA-256 mismatch — file corrupted"))
                return@flow
            }

            Timber.i("${spec.displayName} downloaded and verified.")
            emit(DownloadState.Complete(spec, destFile))

        } catch (e: Exception) {
            Timber.e(e, "Download failed for ${spec.displayName}")
            destFile.delete()
            emit(DownloadState.Failed(spec, e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Accepts a file already on the device (e.g. sideloaded via USB).
     * Copies it to the models directory and verifies SHA-256.
     */
    suspend fun sideloadFromPath(spec: ModelSpec, sourcePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val source = File(sourcePath)
            if (!source.exists()) {
                Timber.e("Sideload source not found: $sourcePath")
                return@withContext false
            }
            val dest = modelFile(spec)
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)

            val valid = spec.sha256 == "REPLACE_WITH_ACTUAL_SHA256" || verifySha256(dest, spec.sha256)
            if (!valid) {
                dest.delete()
                Timber.e("Sideload SHA-256 mismatch for ${spec.displayName}")
            }
            valid
        }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun verifySha256(file: File, expectedHex: String): Boolean {
        if (expectedHex == "REPLACE_WITH_ACTUAL_SHA256") {
            Timber.w("SHA-256 not configured for ${file.name} — skipping verification")
            return true
        }
        return withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHex, ignoreCase = true)
        }
    }
}
