package ai.lumi.inference

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Integrity helpers shared by model downloads, sideloads, startup checks, and unit tests.
 * A model is accepted only when both its exact byte length and SHA-256 match trusted metadata.
 */
internal object ModelIntegrity {
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
    private const val HEX = "0123456789abcdef"

    fun isValidSha256(value: String): Boolean = sha256Pattern.matches(value)

    fun matches(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (expectedBytes <= 0L || !isValidSha256(expectedSha256)) return false
        if (!file.isFile || file.length() != expectedBytes) return false
        return try {
            sha256(file).equals(expectedSha256, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        val bytes = digest.digest()
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    /**
     * Publishes a staged model only after its exact length and digest have been verified.
     * A failed check leaves any previously installed destination untouched.
     */
    fun publishVerifiedFile(
        stagedFile: File,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String
    ): Boolean {
        if (!matches(stagedFile, expectedBytes, expectedSha256)) return false
        val parent = destination.parentFile
            ?: throw IllegalArgumentException("Destination must have a parent directory")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("Unable to create model directory")
        }
        Files.move(
            stagedFile.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        return true
    }
}
