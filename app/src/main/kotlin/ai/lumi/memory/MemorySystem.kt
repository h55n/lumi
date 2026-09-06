package ai.lumi.memory

import ai.lumi.data.db.dao.FormMemoryDao
import ai.lumi.data.db.dao.MemoryVectorDao
import ai.lumi.data.db.dao.TaskMemoryDao
import ai.lumi.data.db.dao.UserProfileDao
import ai.lumi.data.db.entity.FormMemoryEntity
import ai.lumi.data.db.entity.MemoryVectorEntity
import ai.lumi.data.db.entity.TaskMemoryEntity
import ai.lumi.data.db.entity.UserProfileEntity
import ai.lumi.inference.MemoryFact
import ai.lumi.inference.TextLLMEngine
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// ── ProfileManager ─────────────────────────────────────────────────────────────

@Singleton
class ProfileManager @Inject constructor(
    private val userProfileDao: UserProfileDao
) {
    companion object {
        const val KEY_NAME = "name"
        const val KEY_LANGUAGE = "preferredLanguage"
        const val KEY_UPI_APP = "preferredUPIApp"
        const val KEY_CITY = "city"
    }

    suspend fun getName(): String? = userProfileDao.get(KEY_NAME)?.value
    suspend fun getLanguage(): String? = userProfileDao.get(KEY_LANGUAGE)?.value

    suspend fun setName(name: String) {
        userProfileDao.upsert(UserProfileEntity(KEY_NAME, name, source = "explicit"))
        Timber.d("Profile: name='$name'")
    }

    suspend fun setLanguage(lang: String) {
        userProfileDao.upsert(UserProfileEntity(KEY_LANGUAGE, lang, source = "explicit"))
    }

    /**
     * Upsert a profile fact from memory extraction.
     * Newer value always replaces older. Contradiction-safe: old value not lost (overwrites in-place).
     */
    suspend fun upsertFact(key: String, value: String, confidence: Float, source: String = "inferred") {
        val existing = userProfileDao.get(key)
        if (existing != null && existing.source == "explicit" && source == "inferred") {
            // Never overwrite explicit user input with inferred data
            return
        }
        userProfileDao.upsert(UserProfileEntity(key, value, confidence, source))
        Timber.d("Profile upsert: $key='$value' (conf=$confidence, src=$source)")
    }

    suspend fun applyFacts(facts: List<MemoryFact>) {
        facts.filter { it.confidence >= 0.75f }.forEach { fact ->
            upsertFact(fact.key, fact.value, fact.confidence)
        }
    }

    fun allProfileFlow(): Flow<List<UserProfileEntity>> = userProfileDao.getAllFlow()

    suspend fun clearAll() = userProfileDao.clearAll()
}

// ── FormMemoryManager ──────────────────────────────────────────────────────────

@Singleton
class FormMemoryManager @Inject constructor(
    private val formMemoryDao: FormMemoryDao
) {
    /**
     * Returns the remembered value for [fieldType], or null if not known.
     * Auto-increments use count for analytics.
     */
    suspend fun recall(fieldType: String): String? {
        val entity = formMemoryDao.get(fieldType) ?: return null
        formMemoryDao.incrementUseCount(fieldType)
        return entity.value
    }

    /** Save a value encountered during a task. */
    suspend fun remember(fieldType: String, value: String) {
        val existing = formMemoryDao.get(fieldType)
        if (existing == null) {
            formMemoryDao.upsert(FormMemoryEntity(fieldType, value))
        } else {
            formMemoryDao.upsert(existing.copy(value = value, lastUsedAt = System.currentTimeMillis()))
        }
        Timber.d("FormMemory: $fieldType='$value'")
    }

    /** Field types Lumi recognises for auto-fill. */
    object FieldTypes {
        const val NAME = "name"
        const val MOBILE = "mobile"
        const val PINCODE = "pincode"
        const val CITY = "city"
        const val EMAIL = "email"
        const val ADDRESS = "address"
        const val DOB = "dob"
        const val AADHAAR = "aadhaar"
    }

    suspend fun clearAll() = formMemoryDao.clearAll()
}

// ── MemoryRepository ───────────────────────────────────────────────────────────

@Singleton
class MemoryRepository @Inject constructor(
    private val profileManager: ProfileManager,
    private val formMemoryManager: FormMemoryManager,
    private val taskMemoryDao: TaskMemoryDao,
    private val textLLMEngine: TextLLMEngine
) {
    /** Extract and persist memories from a completed task transcript. Flagship only. */
    suspend fun extractAndStore(taskTranscript: String) {
        val facts = textLLMEngine.extractMemories(taskTranscript)
        if (facts.isEmpty()) return
        profileManager.applyFacts(facts)
        Timber.i("MemoryRepository: extracted ${facts.size} facts from transcript")
    }

    suspend fun recordTaskCompletion(
        taskType: String,
        params: String,
        steps: Int,
        success: Boolean,
        correctedStep: Int? = null
    ) {
        taskMemoryDao.insert(
            TaskMemoryEntity(
                taskType = taskType,
                taskParamsJson = params,
                stepCount = steps,
                userCorrectedStep = correctedStep,
                completedSuccessfully = success
            )
        )
    }

    suspend fun getSuccessCount(taskType: String): Int =
        taskMemoryDao.successfulCompletionCount(taskType)
}

// ── VectorSearchEngine ─────────────────────────────────────────────────────────

/**
 * Phase 2: Semantic memory search using MiniLM-L6-v2 embeddings + SQLite-vec.
 * Phase 1: Not active — Phase 1 uses profile + form memory only.
 *
 * INTEGRATION:
 *  1. Add SQLite-vec AAR: implementation("io.github.asg017:sqlite-vec-android:0.1.x")
 *  2. Integrate MiniLM-L6-v2 via ONNX Runtime for Android
 *  3. Replace [embed] stub with real inference
 */
@Singleton
class VectorSearchEngine @Inject constructor(
    private val memoryVectorDao: MemoryVectorDao
) {
    private val dimensions = 384  // MiniLM-L6-v2 output dimension

    /**
     * Search for the top-[k] most semantically similar memories to [query].
     * Phase 1: returns empty list (not yet active).
     */
    suspend fun search(query: String, k: Int = 5): List<String> {
        // Phase 2: embed query + cosine similarity against DB
        return emptyList()
    }

    suspend fun store(content: String, category: String) {
        val embedding = embed(content)
        memoryVectorDao.insert(
            MemoryVectorEntity(content = content, embedding = embedding, category = category)
        )
    }

    /** Cosine similarity between two float arrays. */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        return if (normA == 0f || normB == 0f) 0f else dot / (sqrt(normA) * sqrt(normB))
    }

    /** Phase 2: replace with MiniLM-L6-v2 inference. */
    private fun embed(text: String): FloatArray = FloatArray(dimensions) { 0f }
}
