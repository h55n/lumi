package ai.lumi.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val key: String,              // "name", "preferredLanguage", "city"
    val value: String,
    val confidence: Float = 1.0f,             // 0.0–1.0
    val source: String = "explicit",          // "explicit" | "inferred" | "form_fill"
    val updatedAt: Long = System.currentTimeMillis()
)
