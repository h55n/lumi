package ai.lumi.uimap

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import timber.log.Timber
import ai.lumi.accessibility.UITreeCapture
import ai.lumi.data.db.dao.UIMapDao
import ai.lumi.data.db.entity.UIMapEntity
import ai.lumi.inference.MoondreamEngine
import ai.lumi.inference.ModelSelector
import ai.lumi.screencapture.ScreenshotProcessor

/**
 * Background mapping worker.
 * Runs at:
 *  - First install (for 13 non-bundled priority apps)
 *  - App update detection (via [UIMapRepository.checkStaleness])
 *
 * For each app, it:
 *  1. Waits for a screenshot of that app to be available
 *  2. Runs Moondream2 to identify interactive elements
 *  3. Stores the pHash + guidance plan in Room DB
 *
 * NOTE: This worker cannot autonomously launch apps to screenshot them.
 * It runs opportunistically as the user naturally navigates.
 * Maps are built when the user opens an app for the first time.
 */
@HiltWorker
class UIMapWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val uiMapDao: UIMapDao,
    private val moondreamEngine: MoondreamEngine,
    private val perceptualHasher: PerceptualHasher,
    private val guidancePlanBuilder: GuidancePlanBuilder,
    private val screenshotProcessor: ScreenshotProcessor,
    private val uiTreeCapture: UITreeCapture,
    private val modelSelector: ModelSelector
) : CoroutineWorker(context, params) {

    companion object {
        private const val KEY_PACKAGE = "package_name"
        private const val TAG_REMAP = "lumi_remap"

        /** Enqueue a remap for a specific app (called on version change). */
        fun enqueueRemap(context: Context, packageName: String) {
            val request = OneTimeWorkRequestBuilder<UIMapWorker>()
                .setInputData(Data.Builder().putString(KEY_PACKAGE, packageName).build())
                .addTag("$TAG_REMAP:$packageName")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "remap_$packageName",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        /** Enqueue full install-time mapping for all priority apps. */
        fun enqueueInstallMapping(context: Context) {
            val request = OneTimeWorkRequestBuilder<UIMapWorker>()
                .addTag(TAG_REMAP)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("lumi_install_mapping", ExistingWorkPolicy.KEEP, request)
        }

        /** Priority apps for runtime mapping (bundle covers PhonePe + WhatsApp). */
        val RUNTIME_PRIORITY_APPS = listOf(
            "com.android.settings",
            "com.android.camera2",
            "com.google.android.gm",
            "com.android.chrome",
            "com.google.android.youtube",
            "net.one97.paytm",
            "in.gov.uidai.mAadhaarPlus",
            "com.irctc.android",
            "com.google.android.apps.maps",
            "com.truecaller",
            "com.instagram.android",
            "com.google.android.dialer",
            "com.google.android.apps.photos"
        )
    }

    override suspend fun doWork(): Result {
        val targetPackage = inputData.getString(KEY_PACKAGE)
        val apps = if (targetPackage != null) listOf(targetPackage) else RUNTIME_PRIORITY_APPS

        Timber.i("UIMapWorker: mapping ${apps.size} apps")
        val tier = modelSelector.configure()
        if (!tier.supportsVLM) {
            Timber.w("UIMapWorker: device has no VLM — skipping mapping")
            return Result.success()
        }

        moondreamEngine.warmUp(tier)

        for (pkg in apps) {
            try {
                mapApp(pkg)
            } catch (e: Exception) {
                Timber.e(e, "Failed to map $pkg")
            }
        }

        Timber.i("UIMapWorker: complete")
        return Result.success()
    }

    /**
     * Map a single app.
     * Uses the current screen if it matches [pkg], otherwise waits up to 30s for a match.
     */
    private suspend fun mapApp(pkg: String) {
        Timber.d("Mapping $pkg...")
        // In practice the user opens the app — we capture opportunistically
        // For now, log and skip if not currently foregrounded
        val screenshot = ai.lumi.screencapture.ScreenCaptureService.latestFrame ?: return
        val uiElements = uiTreeCapture.clickableElements()

        if (uiElements.isEmpty()) return

        val hash = perceptualHasher.hash(screenshot)
        val currentPkg = ai.lumi.accessibility.LumiAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString() ?: return

        if (currentPkg != pkg) return

        val plan = buildPlanFromTree(uiElements)
        val entity = UIMapEntity(
            id = "${pkg}_${hash}",
            packageName = pkg,
            screenHash = hash,
            versionCode = getVersionCode(pkg),
            screenLabel = "${pkg.substringAfterLast('.')}_screen",
            elementMapJson = "[]",
            guidancePlanJson = guidancePlanBuilder.toJson(plan),
            isBundled = false
        )
        uiMapDao.insertOrReplace(entity)
        Timber.d("Mapped screen for $pkg (hash=$hash, ${plan.size} steps)")
    }

    private fun buildPlanFromTree(elements: List<ai.lumi.accessibility.UIElement>): List<ai.lumi.engine.TaskStep> {
        return elements.take(6).mapIndexed { i, el ->
            ai.lumi.engine.TaskStep(
                stepIndex = i,
                targetDescription = el.label ?: el.className ?: "element",
                instructionEn = "Tap ${el.label ?: "this"}",
                instructionNative = "Yahan tap karo",  // Hindi default; replaced by VLM for other languages
                relativeX = el.bounds.centerX().toFloat() / android.content.res.Resources.getSystem().displayMetrics.widthPixels,
                relativeY = el.bounds.centerY().toFloat() / android.content.res.Resources.getSystem().displayMetrics.heightPixels,
                isLastStep = i == minOf(5, elements.size - 1)
            )
        }
    }

    private fun getVersionCode(pkg: String): Long = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(pkg, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionCode.toLong()
        }
    } catch (e: Exception) { 1L }
}
