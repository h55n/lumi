package ai.lumi.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ai.lumi.data.db.dao.FormMemoryDao
import ai.lumi.data.db.dao.MemoryVectorDao
import ai.lumi.data.db.dao.TaskMemoryDao
import ai.lumi.data.db.dao.UIMapDao
import ai.lumi.data.db.dao.UserProfileDao
import ai.lumi.data.db.entity.FloatArrayConverter
import ai.lumi.data.db.entity.FormMemoryEntity
import ai.lumi.data.db.entity.MemoryVectorEntity
import ai.lumi.data.db.entity.TaskMemoryEntity
import ai.lumi.data.db.entity.UIMapEntity
import ai.lumi.data.db.entity.UserProfileEntity

@Database(
    entities = [
        UIMapEntity::class,
        UserProfileEntity::class,
        TaskMemoryEntity::class,
        FormMemoryEntity::class,
        MemoryVectorEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(FloatArrayConverter::class)
abstract class LumiDatabase : RoomDatabase() {
    abstract fun uiMapDao(): UIMapDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun taskMemoryDao(): TaskMemoryDao
    abstract fun formMemoryDao(): FormMemoryDao
    abstract fun memoryVectorDao(): MemoryVectorDao
}
