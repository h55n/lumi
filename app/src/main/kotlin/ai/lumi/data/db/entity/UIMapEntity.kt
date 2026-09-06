package ai.lumi.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ui_maps",
    indices = [
        Index(value = ["packageName"]),
        Index(value = ["packageName", "screenHash"], unique = true)
    ]
)
data class UIMapEntity(
    @PrimaryKey val id: String,                  // "{packageName}_{screenHash}"
    val packageName: String,
    val screenHash: Long,                        // 64-bit pHash
    val versionCode: Long,
    val screenLabel: String,                     // "PhonePe_Home", "WhatsApp_Chat"
    val elementMapJson: String,                  // [{id, text, desc, bounds}]
    val guidancePlanJson: String,                // [{step, targetDesc, instruction_en, instruction_hi}]
    val screenshotPath: String = "",
    val isBundled: Boolean = false,              // true = from assets/uimaps/, immutable
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
