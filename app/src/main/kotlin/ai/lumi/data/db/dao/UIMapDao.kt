package ai.lumi.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import ai.lumi.data.db.entity.UIMapEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UIMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(map: UIMapEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(maps: List<UIMapEntity>)

    @Query("SELECT * FROM ui_maps WHERE packageName = :pkg")
    suspend fun getScreensForApp(pkg: String): List<UIMapEntity>

    @Query("SELECT * FROM ui_maps WHERE id = :id")
    suspend fun getById(id: String): UIMapEntity?

    @Query("SELECT versionCode FROM ui_maps WHERE packageName = :pkg LIMIT 1")
    suspend fun getVersionCode(pkg: String): Long?

    @Query("SELECT * FROM ui_maps WHERE isBundled = 0")
    suspend fun getAllRuntimeMaps(): List<UIMapEntity>

    @Query("DELETE FROM ui_maps WHERE packageName = :pkg AND isBundled = 0")
    suspend fun deleteRuntimeMapsForApp(pkg: String)

    @Query("SELECT COUNT(*) FROM ui_maps")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM ui_maps WHERE isBundled = 1")
    suspend fun bundledCount(): Int

    @Query("SELECT DISTINCT packageName FROM ui_maps")
    fun allMappedPackages(): Flow<List<String>>
}
