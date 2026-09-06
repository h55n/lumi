package ai.lumi

import com.google.common.truth.Truth.assertThat
import ai.lumi.data.db.entity.UserProfileEntity
import ai.lumi.inference.MemoryFact
import ai.lumi.memory.FormMemoryManager
import ai.lumi.memory.ProfileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import ai.lumi.data.db.dao.FormMemoryDao
import ai.lumi.data.db.dao.UserProfileDao
import ai.lumi.data.db.entity.FormMemoryEntity

class ProfileManagerTest {

    private val dao = mockk<UserProfileDao>(relaxed = true)
    private lateinit var manager: ProfileManager

    @Before fun setUp() { manager = ProfileManager(dao) }

    @Test fun `setName upserts correct entity`() = runTest {
        manager.setName("Ravi")
        coVerify {
            dao.upsert(match {
                it.key == "name" && it.value == "Ravi" && it.source == "explicit"
            })
        }
    }

    @Test fun `setLanguage stores language code`() = runTest {
        manager.setLanguage("hi")
        coVerify { dao.upsert(match { it.key == "preferredLanguage" && it.value == "hi" }) }
    }

    @Test fun `getName returns stored name`() = runTest {
        coEvery { dao.get("name") } returns UserProfileEntity("name", "Priya")
        assertThat(manager.getName()).isEqualTo("Priya")
    }

    @Test fun `getName returns null when not set`() = runTest {
        coEvery { dao.get("name") } returns null
        assertThat(manager.getName()).isNull()
    }

    @Test fun `upsertFact with inferred does not overwrite explicit`() = runTest {
        coEvery { dao.get("name") } returns UserProfileEntity("name", "Ravi", source = "explicit")
        manager.upsertFact("name", "Overwrite", 0.9f, source = "inferred")
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test fun `upsertFact with explicit overwrites inferred`() = runTest {
        coEvery { dao.get("name") } returns UserProfileEntity("name", "Ravi", source = "inferred")
        manager.upsertFact("name", "Priya", 1.0f, source = "explicit")
        coVerify { dao.upsert(match { it.value == "Priya" }) }
    }

    @Test fun `applyFacts filters low confidence`() = runTest {
        coEvery { dao.get(any()) } returns null
        val facts = listOf(
            MemoryFact("name", "Ravi", 0.9f),         // should save
            MemoryFact("city", "Pune", 0.5f),          // too low — should NOT save
            MemoryFact("language", "hi", 0.8f)         // should save
        )
        manager.applyFacts(facts)
        coVerify(exactly = 2) { dao.upsert(any()) }
    }
}

class FormMemoryManagerTest {

    private val dao = mockk<FormMemoryDao>(relaxed = true)
    private lateinit var manager: FormMemoryManager

    @Before fun setUp() { manager = FormMemoryManager(dao) }

    @Test fun `recall returns stored value`() = runTest {
        coEvery { dao.get("name") } returns FormMemoryEntity("name", "Ravi Kumar")
        assertThat(manager.recall("name")).isEqualTo("Ravi Kumar")
    }

    @Test fun `recall returns null when not stored`() = runTest {
        coEvery { dao.get("mobile") } returns null
        assertThat(manager.recall("mobile")).isNull()
    }

    @Test fun `recall increments use count`() = runTest {
        coEvery { dao.get("pincode") } returns FormMemoryEntity("pincode", "411001")
        manager.recall("pincode")
        coVerify { dao.incrementUseCount("pincode", any()) }
    }

    @Test fun `remember saves new value`() = runTest {
        coEvery { dao.get("city") } returns null
        manager.remember("city", "Pune")
        coVerify { dao.upsert(match { it.fieldType == "city" && it.value == "Pune" }) }
    }

    @Test fun `remember updates existing value`() = runTest {
        coEvery { dao.get("city") } returns FormMemoryEntity("city", "Mumbai")
        manager.remember("city", "Pune")
        coVerify { dao.upsert(match { it.value == "Pune" }) }
    }
}
