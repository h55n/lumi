package ai.lumi.memory

import androidx.room.Room
import ai.lumi.data.db.LumiDatabase
import ai.lumi.data.db.entity.FormMemoryEntity
import ai.lumi.data.db.entity.MemoryVectorEntity
import ai.lumi.data.db.entity.TaskMemoryEntity
import ai.lumi.data.db.entity.UserProfileEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MemoryErasureTest {
    private lateinit var database: LumiDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LumiDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `clear all memory data removes profile form task and vector records`() = runBlocking {
        database.userProfileDao().upsert(UserProfileEntity("name", "Test User"))
        database.formMemoryDao().upsert(FormMemoryEntity("mobile", "5550100"))
        database.taskMemoryDao().insert(
            TaskMemoryEntity(
                taskType = "contact",
                taskParamsJson = "{\"contact\":\"test\"}",
                stepCount = 1,
                completedSuccessfully = true
            )
        )
        database.memoryVectorDao().insert(
            MemoryVectorEntity(
                content = "test memory",
                embedding = floatArrayOf(0.1f, 0.2f),
                category = "profile"
            )
        )

        database.clearAllMemoryData()

        assertThat(database.userProfileDao().getAll()).isEmpty()
        assertThat(database.formMemoryDao().getAll()).isEmpty()
        assertThat(database.taskMemoryDao().getRecent()).isEmpty()
        assertThat(database.memoryVectorDao().getAll()).isEmpty()
    }
}
