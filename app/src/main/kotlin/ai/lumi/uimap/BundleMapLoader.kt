package ai.lumi.uimap

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import ai.lumi.data.db.dao.UIMapDao
import ai.lumi.data.db.entity.UIMapEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class BundleMapFile(
    val packageName: String,
    val version: Int = 1,
    val screens: List<BundleScreen>
)

@JsonClass(generateAdapter = true)
data class BundleScreen(
    val screenHash: Long,
    val screenLabel: String,
    val versionCode: Long = 1L,
    val guidancePlan: List<GuidanceStepDto>,
    val elements: List<BundleElement> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BundleElement(
    val id: String = "",
    val text: String = "",
    val description: String = "",
    val bounds: List<Int> = emptyList()
)

@Singleton
class BundleMapLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uiMapDao: UIMapDao
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val fileAdapter = moshi.adapter(BundleMapFile::class.java)

    private val bundleFiles = listOf(
        "uimaps/phonepe_maps.json",
        "uimaps/whatsapp_maps.json",
        "uimaps/settings_maps.json"
    )

    /** Load all bundled maps into Room DB. Updates any changed maps. */
    suspend fun loadAll() {
        Timber.i("Loading bundle maps into Room DB...")
        var total = 0
        for (assetPath in bundleFiles) {
            try {
                val json = context.assets.open(assetPath).bufferedReader().readText()
                val mapFile = fileAdapter.fromJson(json) ?: continue
                val entities = mapFile.screens.map { screen ->
                    UIMapEntity(
                        id = "${mapFile.packageName}_${screen.screenLabel}",
                        packageName = mapFile.packageName,
                        screenHash = screen.screenHash,
                        versionCode = screen.versionCode,
                        screenLabel = screen.screenLabel,
                        elementMapJson = "[]",
                        guidancePlanJson = moshi.adapter(List::class.java).toJson(screen.guidancePlan),
                        isBundled = true
                    )
                }
                uiMapDao.insertOrReplaceAll(entities)
                total += entities.size
                Timber.d("Loaded ${entities.size} screens from $assetPath")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load bundle map: $assetPath")
            }
        }
        Timber.i("Bundle maps loaded: $total screens total")
    }
}
