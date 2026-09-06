package ai.lumi.uimap

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import ai.lumi.data.db.dao.UIMapDao
import ai.lumi.engine.TaskStep
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UIMapRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uiMapDao: UIMapDao,
    private val perceptualHasher: PerceptualHasher,
    private val guidancePlanBuilder: GuidancePlanBuilder,
    private val bundleMapLoader: BundleMapLoader
) {

    /**
     * Find a matching guidance plan for [screenshot] in app [packageName].
     *
     * Layer 1: Bundled maps (APK assets, loaded into Room DB at launch) — <100ms
     * Layer 2: Runtime maps (built by UIMapWorker at install time) — <200ms
     * Miss: returns null → TaskEngine falls back to VLM inference
     */
    suspend fun findGuidancePlan(screenshot: Bitmap, packageName: String): List<TaskStep>? {
        val hash = perceptualHasher.hash(screenshot)
        val screens = uiMapDao.getScreensForApp(packageName)
        if (screens.isEmpty()) return null

        val best = screens.minByOrNull { perceptualHasher.hammingDistance(it.screenHash, hash) }
            ?: return null

        val distance = perceptualHasher.hammingDistance(best.screenHash, hash)
        Timber.d("UIMapRepo: best match distance=$distance (threshold=${PerceptualHasher.MATCH_THRESHOLD})")

        if (distance >= PerceptualHasher.MATCH_THRESHOLD) return null

        return guidancePlanBuilder.fromJson(best.guidancePlanJson).also {
            Timber.d("UIMapRepo: HIT — ${best.screenLabel} (bundled=${best.isBundled})")
        }
    }

    /**
     * Check if [packageName] has been updated since maps were built, OR if maps are older
     * than [MAP_TTL_DAYS] days. Enqueues [UIMapWorker] if staleness detected.
     * Only runs for runtime maps — bundled maps are immutable.
     */
    suspend fun checkStaleness(packageName: String) {
        val storedVersion = uiMapDao.getVersionCode(packageName) ?: return
        val currentVersion = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }

        val versionStale = currentVersion != storedVersion
        val ageStale = isAgeStale(packageName)

        if (versionStale || ageStale) {
            val reason = when {
                versionStale -> "version change ($storedVersion → $currentVersion)"
                else -> "map older than $MAP_TTL_DAYS days"
            }
            Timber.i("$packageName map stale ($reason) — triggering remap")
            UIMapWorker.enqueueRemap(context, packageName)
            uiMapDao.deleteRuntimeMapsForApp(packageName)
        }
    }

    /** Returns true if the maps for [packageName] were last built more than [MAP_TTL_DAYS] days ago. */
    private suspend fun isAgeStale(packageName: String): Boolean {
        val screens = uiMapDao.getScreensForApp(packageName).filter { !it.isBundled }
        if (screens.isEmpty()) return false
        val oldestUpdate = screens.minOfOrNull { it.updatedAt } ?: return false
        val ageMs = System.currentTimeMillis() - oldestUpdate
        return ageMs > MAP_TTL_DAYS * 24L * 60 * 60 * 1000
    }

    companion object {
        /** Number of days before a runtime UI map is considered stale and rebuilt. */
        const val MAP_TTL_DAYS = 7
    }

    /**
     * Fallback for bundled demo apps (e.g. PhonePe, WhatsApp) when visual pHash doesn't
     * match within the threshold. Returns the full sequence of bundled steps.
     */
    suspend fun getInitialBundledPlan(packageName: String): List<TaskStep>? {
        val screens = uiMapDao.getScreensForApp(packageName).filter { it.isBundled }
        if (screens.isEmpty()) return null
        val allSteps = screens.flatMap { screen ->
            guidancePlanBuilder.fromJson(screen.guidancePlanJson) ?: emptyList()
        }.sortedBy { it.stepIndex }

        return if (allSteps.isNotEmpty()) {
            Timber.d("UIMapRepo: Bundled fallback used for $packageName (${allSteps.size} steps)")
            allSteps
        } else null
    }

    /**
     * Returns the guidance plan for a specific screen label in a bundled package
     * (e.g. "Settings_WiFi" or "Settings_Display").
     */
    suspend fun getBundledPlanForScreen(packageName: String, screenLabel: String): List<TaskStep>? {
        val screen = uiMapDao.getScreensForApp(packageName)
            .firstOrNull { it.isBundled && it.screenLabel.equals(screenLabel, ignoreCase = true) }
            ?: return null
        return guidancePlanBuilder.fromJson(screen.guidancePlanJson)
    }

    /** Initialize bundle maps (call once at first launch). */
    suspend fun initBundles() = bundleMapLoader.loadAll()
}
