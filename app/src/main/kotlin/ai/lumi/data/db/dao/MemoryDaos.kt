package ai.lumi.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.lumi.data.db.entity.FormMemoryEntity
import ai.lumi.data.db.entity.MemoryVectorEntity
import ai.lumi.data.db.entity.TaskMemoryEntity
import ai.lumi.data.db.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<UserProfileEntity>)

    @Query("SELECT * FROM user_profile WHERE key = :key")
    suspend fun get(key: String): UserProfileEntity?

    @Query("SELECT * FROM user_profile")
    fun getAllFlow(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM user_profile")
    suspend fun getAll(): List<UserProfileEntity>

    @Query("DELETE FROM user_profile WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM user_profile")
    suspend fun clearAll()
}

@Dao
interface TaskMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TaskMemoryEntity): Long

    @Query("SELECT * FROM task_memory WHERE taskType = :taskType ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getByType(taskType: String, limit: Int = 10): List<TaskMemoryEntity>

    @Query("SELECT COUNT(*) FROM task_memory WHERE taskType = :taskType AND completedSuccessfully = 1")
    suspend fun successfulCompletionCount(taskType: String): Int

    @Query("SELECT * FROM task_memory ORDER BY completedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<TaskMemoryEntity>

    @Query("DELETE FROM task_memory")
    suspend fun clearAll()
}

@Dao
interface FormMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FormMemoryEntity)

    @Query("SELECT * FROM form_memory WHERE fieldType = :fieldType")
    suspend fun get(fieldType: String): FormMemoryEntity?

    @Query("SELECT * FROM form_memory ORDER BY useCount DESC")
    suspend fun getAll(): List<FormMemoryEntity>

    @Query("UPDATE form_memory SET useCount = useCount + 1, lastUsedAt = :now WHERE fieldType = :fieldType")
    suspend fun incrementUseCount(fieldType: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM form_memory WHERE fieldType = :fieldType")
    suspend fun delete(fieldType: String)

    @Query("DELETE FROM form_memory")
    suspend fun clearAll()
}

@Dao
interface MemoryVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MemoryVectorEntity): Long

    @Query("SELECT * FROM memory_vectors WHERE category = :category ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByCategory(category: String, limit: Int = 20): List<MemoryVectorEntity>

    @Query("SELECT * FROM memory_vectors ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getAll(limit: Int = 100): List<MemoryVectorEntity>

    @Query("DELETE FROM memory_vectors")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM memory_vectors")
    suspend fun count(): Int
}
