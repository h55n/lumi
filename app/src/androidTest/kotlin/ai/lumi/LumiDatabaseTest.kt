package ai.lumi

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import ai.lumi.data.db.LumiDatabase
import ai.lumi.data.db.dao.UIMapDao
import ai.lumi.data.db.dao.UserProfileDao
import ai.lumi.data.db.entity.FormMemoryEntity
import ai.lumi.data.db.entity.UIMapEntity
import ai.lumi.data.db.entity.UserProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LumiDatabaseTest {

    private lateinit var db: LumiDatabase

    @Before fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LumiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDb() { db.close() }

    // ── UIMapDao ───────────────────────────────────────────────────────────────

    @Test fun insertAndRetrieveUIMap() = runTest {
        val entity = UIMapEntity(
            id = "com.phonepe.app_123456",
            packageName = "com.phonepe.app",
            screenHash = 123456L,
            versionCode = 1L,
            screenLabel = "PhonePe_Home",
            elementMapJson = "[]",
            guidancePlanJson = "{}",
            isBundled = true
        )
        db.uiMapDao().insertOrReplace(entity)
        val result = db.uiMapDao().getScreensForApp("com.phonepe.app")
        assertThat(result).hasSize(1)
        assertThat(result[0].screenHash).isEqualTo(123456L)
        assertThat(result[0].isBundled).isTrue()
    }

    @Test fun insertReplaceOnConflict() = runTest {
        val entity1 = testUIMap("com.phonepe.app_1", "com.phonepe.app", 1L)
        val entity2 = entity1.copy(screenLabel = "Updated")
        db.uiMapDao().insertOrReplace(entity1)
        db.uiMapDao().insertOrReplace(entity2)
        val result = db.uiMapDao().getScreensForApp("com.phonepe.app")
        assertThat(result).hasSize(1)
        assertThat(result[0].screenLabel).isEqualTo("Updated")
    }

    @Test fun bundledCountExcludesRuntimeMaps() = runTest {
        db.uiMapDao().insertOrReplace(testUIMap("a_1", "a", 1L, bundled = true))
        db.uiMapDao().insertOrReplace(testUIMap("a_2", "a", 2L, bundled = false))
        assertThat(db.uiMapDao().bundledCount()).isEqualTo(1)
        assertThat(db.uiMapDao().totalCount()).isEqualTo(2)
    }

    @Test fun deleteRuntimeMapsPreservesBundled() = runTest {
        db.uiMapDao().insertOrReplace(testUIMap("pkg_1", "pkg", 1L, bundled = true))
        db.uiMapDao().insertOrReplace(testUIMap("pkg_2", "pkg", 2L, bundled = false))
        db.uiMapDao().deleteRuntimeMapsForApp("pkg")
        val remaining = db.uiMapDao().getScreensForApp("pkg")
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].isBundled).isTrue()
    }

    @Test fun versionCodeLookup() = runTest {
        db.uiMapDao().insertOrReplace(testUIMap("pkg_1", "pkg", 1L).copy(versionCode = 42L))
        assertThat(db.uiMapDao().getVersionCode("pkg")).isEqualTo(42L)
        assertThat(db.uiMapDao().getVersionCode("unknown")).isNull()
    }

    // ── UserProfileDao ─────────────────────────────────────────────────────────

    @Test fun upsertAndGetProfile() = runTest {
        db.userProfileDao().upsert(UserProfileEntity("name", "Ravi"))
        val result = db.userProfileDao().get("name")
        assertThat(result?.value).isEqualTo("Ravi")
    }

    @Test fun upsertOverwritesExisting() = runTest {
        db.userProfileDao().upsert(UserProfileEntity("name", "Ravi"))
        db.userProfileDao().upsert(UserProfileEntity("name", "Priya"))
        assertThat(db.userProfileDao().get("name")?.value).isEqualTo("Priya")
    }

    @Test fun clearAllRemovesAllEntries() = runTest {
        db.userProfileDao().upsert(UserProfileEntity("name", "Ravi"))
        db.userProfileDao().upsert(UserProfileEntity("lang", "hi"))
        db.userProfileDao().clearAll()
        assertThat(db.userProfileDao().getAll()).isEmpty()
    }

    @Test fun getAllFlowEmitsUpdates() = runTest {
        db.userProfileDao().upsert(UserProfileEntity("name", "Ravi"))
        val all = db.userProfileDao().getAllFlow().first()
        assertThat(all).hasSize(1)
    }

    // ── FormMemoryDao ──────────────────────────────────────────────────────────

    @Test fun formMemoryUpsertAndGet() = runTest {
        db.formMemoryDao().upsert(FormMemoryEntity("name", "Ravi Kumar"))
        val result = db.formMemoryDao().get("name")
        assertThat(result?.value).isEqualTo("Ravi Kumar")
    }

    @Test fun formMemoryIncrementUseCount() = runTest {
        db.formMemoryDao().upsert(FormMemoryEntity("pincode", "411001", useCount = 1))
        db.formMemoryDao().incrementUseCount("pincode")
        val result = db.formMemoryDao().get("pincode")
        assertThat(result?.useCount).isEqualTo(2)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun testUIMap(id: String, pkg: String, hash: Long, bundled: Boolean = false) =
        UIMapEntity(id, pkg, hash, 1L, "label", "[]", "{}", isBundled = bundled)
}
