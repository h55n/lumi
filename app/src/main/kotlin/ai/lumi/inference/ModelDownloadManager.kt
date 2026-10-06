package ai.lumi.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val model: ModelSpec, val progressPercent: Int) : DownloadState()
    data class Verifying(val model: ModelSpec) : DownloadState()
    data class Complete(val model: ModelSpec, val file: File) : DownloadState()
    data class Failed(val model: ModelSpec, val reason: String) : DownloadState()
    data object WifiRequired : DownloadState()

    /** Emitted for models requiring a vendor SDK or for unavailable public artifacts. */
    data class CloudMode(val model: ModelSpec, val reason: String) : DownloadState()
}

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private data class FileFingerprint(val length: Long, val lastModified: Long)

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** Fingerprints are cached only for this process; changed files are re-hashed. */
    private val verifiedFiles = ConcurrentHashMap<String, FileFingerprint>()

    fun modelFile(spec: ModelSpec): File =
        File(modelsDir, "${spec.subDir}/${spec.fileName}")

    /**
     * Confirms that an artifact has an integrated runtime and matches its pinned byte count
     * and SHA-256. Hashing is performed on IO and cached by path, length, and modification time.
     */
    suspend fun isDownloaded(spec: ModelSpec): Boolean = withContext(Dispatchers.IO) {
        if (!spec.isDirectDownloadable || !spec.hasRuntimeIntegration ||
            !spec.hasTrustedSha256 || spec.sizeBytes <= 0L) {
            return@withContext false
        }
        isVerifiedModelFile(modelFile(spec), spec)
    }

    private fun isVerifiedModelFile(file: File, spec: ModelSpec): Boolean {
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val fingerprint = FileFingerprint(file.length(), file.lastModified())
        val key = file.absolutePath
        if (verifiedFiles[key] == fingerprint) return true

        val valid = ModelIntegrity.matches(file, spec.sizeBytes, spec.sha256)
        if (valid) {
            verifiedFiles[key] = fingerprint
        } else {
            verifiedFiles.remove(key)
        }
        return valid
    }

    private fun rememberVerifiedFile(file: File) {
        verifiedFiles[file.absolutePath] = FileFingerprint(file.length(), file.lastModified())
    }

    /**
     * Downloads [spec] and emits progress via [Flow<DownloadState>].
     * A model cannot be downloaded unless its exact SHA-256 and byte size are known.
     */
    fun download(spec: ModelSpec, allowCellular: Boolean = true): Flow<DownloadState> = flow {
        if (!spec.isDirectDownloadable) {
            Timber.i("${spec.displayName} requires a vendor SDK; using Cloud mode")
            emit(DownloadState.CloudMode(spec, "Requires vendor SDK — using Cloud mode"))
            return@flow
        }
        if (!spec.hasRuntimeIntegration) {
            Timber.i("${spec.displayName} has no integrated local runtime; using Cloud mode")
            emit(DownloadState.CloudMode(spec, "Local inference runtime is not integrated — using Cloud mode"))
            return@flow
        }
        if (!spec.hasTrustedSha256 || spec.sizeBytes <= 0L) {
            emit(DownloadState.Failed(spec, "Trusted integrity metadata is unavailable; download blocked"))
            return@flow
        }
        if (!allowCellular && !isOnWifi()) {
            emit(DownloadState.WifiRequired)
            return@flow
        }

        val destination = modelFile(spec)
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            emit(DownloadState.Failed(spec, "Unable to create model directory"))
            return@flow
        }

        var stagedFile: File? = null
        try {
            val staged = File.createTempFile("${destination.name}.", ".part", parent)
            stagedFile = staged
            Timber.i("Downloading ${spec.displayName}")
            emit(DownloadState.Downloading(spec, 0))

            val request = Request.Builder().url(spec.downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    if (response.code in 400..499) {
                        Timber.w("${spec.displayName} is unavailable (HTTP ${response.code}); using Cloud mode")
                        emit(DownloadState.CloudMode(spec, "Model unavailable (HTTP ${response.code}) — Cloud mode active"))
                    } else {
                        emit(DownloadState.Failed(spec, "HTTP ${response.code}"))
                    }
                    return@flow
                }

                val body = response.body
                if (body == null) {
                    emit(DownloadState.Failed(spec, "Empty response body"))
                    return@flow
                }
                val contentLength = body.contentLength()
                if (contentLength >= 0L && contentLength != spec.sizeBytes) {
                    emit(DownloadState.Failed(spec, "Downloaded file size does not match trusted metadata"))
                    return@flow
                }

                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    staged.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastProgress = -1
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            downloadedBytes += bytesRead
                            if (downloadedBytes > spec.sizeBytes) {
                                throw IllegalStateException("Downloaded file exceeds expected size")
                            }
                            output.write(buffer, 0, bytesRead)
                            val progress = ((downloadedBytes * 100) / spec.sizeBytes).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                emit(DownloadState.Downloading(spec, progress))
                            }
                        }
                    }
                }

                if (downloadedBytes != spec.sizeBytes) {
                    emit(DownloadState.Failed(spec, "Downloaded file size does not match trusted metadata"))
                    return@flow
                }

                emit(DownloadState.Verifying(spec))
                if (!ModelIntegrity.publishVerifiedFile(staged, destination, spec.sizeBytes, spec.sha256)) {
                    emit(DownloadState.Failed(spec, "SHA-256 mismatch — staged file rejected"))
                    return@flow
                }
                rememberVerifiedFile(destination)
                Timber.i("${spec.displayName} downloaded and verified")
                emit(DownloadState.Complete(spec, destination))
            } finally {
                response.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.e(e, "Download failed for ${spec.displayName}")
            emit(DownloadState.Failed(spec, e.message ?: "Unknown error"))
        } finally {
            stagedFile?.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Accepts a local model only when its runtime and trusted exact length/SHA-256 are configured.
     * The existing destination is preserved unless the staged copy passes verification.
     */
    suspend fun sideloadFromPath(spec: ModelSpec, sourcePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!spec.isDirectDownloadable || !spec.hasRuntimeIntegration ||
                !spec.hasTrustedSha256 || spec.sizeBytes <= 0L) {
                Timber.w("Sideload blocked because runtime or trusted integrity metadata is unavailable for ${spec.displayName}")
                return@withContext false
            }
            val source = File(sourcePath)
            if (!source.isFile || source.length() != spec.sizeBytes) {
                Timber.e("Sideload source is missing or has an unexpected size")
                return@withContext false
            }

            val destination = modelFile(spec)
            val parent = destination.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                return@withContext false
            }
            val staged = File.createTempFile("${destination.name}.", ".sideload.part", parent)
            try {
                source.inputStream().buffered().use { input ->
                    staged.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (!ModelIntegrity.publishVerifiedFile(staged, destination, spec.sizeBytes, spec.sha256)) {
                    Timber.e("Sideload SHA-256 mismatch for ${spec.displayName}")
                    return@withContext false
                }
                rememberVerifiedFile(destination)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Timber.e(e, "Sideload failed for ${spec.displayName}")
                false
            } finally {
                staged.delete()
            }
        }

    private fun isOnWifi(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
