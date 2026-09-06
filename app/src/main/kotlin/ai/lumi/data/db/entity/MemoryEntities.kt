package ai.lumi.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Entity(tableName = "task_memory")
data class TaskMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskType: String,
    val taskParamsJson: String,               // {"contact": "Maa", "amount": "500"}
    val stepCount: Int,
    val userCorrectedStep: Int? = null,       // null = no correction needed
    val completedSuccessfully: Boolean,
    val completedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "form_memory")
data class FormMemoryEntity(
    @PrimaryKey val fieldType: String,        // "name", "mobile", "pincode", "city"
    val value: String,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val useCount: Int = 1
)

@Entity(tableName = "memory_vectors")
@TypeConverters(FloatArrayConverter::class)
data class MemoryVectorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val embedding: FloatArray,               // 384-dim MiniLM-L6-v2
    val category: String,                    // "profile" | "task" | "preference"
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryVectorEntity) return false
        return id == other.id && content == other.content
    }

    override fun hashCode(): Int = id.hashCode()
}

class FloatArrayConverter {
    @TypeConverter
    fun fromFloatArray(value: FloatArray): String =
        value.joinToString(",")

    @TypeConverter
    fun toFloatArray(value: String): FloatArray =
        if (value.isEmpty()) FloatArray(0)
        else value.split(",").map { it.toFloat() }.toFloatArray()
}
