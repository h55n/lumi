package ai.lumi.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import ai.lumi.data.datastore.LumiPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inventories launchable installed apps so guidance can open the correct package
 * instead of falling back to launcher search.
 */
@Singleton
class AppInventory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: LumiPreferences
) {
    data class InstalledApp(
        val packageName: String,
        val label: String
    )

    private val mutex = Mutex()
    @Volatile private var cache: List<InstalledApp> = emptyList()
    @Volatile private var scanned = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                restoreFromPreferences()
                if (!scanned || cache.isEmpty()) {
                    ensureScanned(force = true)
                }
            } catch (e: Exception) {
                Timber.w(e, "AppInventory startup warm-up non-fatal")
            }
        }
    }

    /** Known aliases → candidate package names (first installed wins). */
    val knownApps: Map<String, List<String>> = mapOf(
        "phonepe" to listOf("com.phonepe.app"),
        "phone pe" to listOf("com.phonepe.app"),
        "paytm" to listOf("net.one97.paytm"),
        "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "whatsapp settings" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "youtube" to listOf("com.google.android.youtube"),
        "chrome" to listOf("com.android.chrome"),
        "instagram" to listOf("com.instagram.android"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "spotify" to listOf("com.spotify.music"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "fb" to listOf("com.facebook.katana", "com.facebook.lite"),
        "linkedin" to listOf("com.linkedin.android"),
        "play store" to listOf("com.android.vending"),
        "gallery" to listOf("com.vivo.gallery", "com.google.android.apps.photos", "com.android.gallery3d"),
        "photos" to listOf("com.google.android.apps.photos", "com.vivo.gallery"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "email" to listOf("com.google.android.gm"),
        "irctc" to listOf("cris.org.in.prs.ima", "com.irctc.android"),
        "train ticket" to listOf("cris.org.in.prs.ima", "com.irctc.android"),
        "aadhaar" to listOf("in.gov.uidai.mAadhaarPlus"),
        "aadhar" to listOf("in.gov.uidai.mAadhaarPlus"),
        "truecaller" to listOf("com.truecaller"),
        "settings" to listOf("com.android.settings"),
        "camera" to listOf(
            "com.android.camera2",
            "com.android.camera",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera"
        ),
        "contacts" to listOf(
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.contacts"
        ),
        "dialer" to listOf(
            "com.android.contacts",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.vivo.dialer",
            "com.samsung.android.dialer"
        ),
        "phone" to listOf(
            "com.android.contacts",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.vivo.dialer",
            "com.samsung.android.dialer"
        ),
        "call" to listOf(
            "com.android.contacts",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.vivo.dialer",
            "com.samsung.android.dialer"
        ),
        "messages" to listOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.vivo.mms"
        )
    )

    private val launcherOrSearchPackages = setOf(
        "com.android.launcher3",
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.bbk.launcher2",
        "com.vivo.globalsearch",
        "com.vivo.upslide",
        "com.vivo.card",
        "com.android.quicksearchbox",
        "com.google.android.googlequicksearchbox"
    )

    suspend fun ensureScanned(force: Boolean = false): List<InstalledApp> {
        if (scanned && !force && cache.isNotEmpty()) return cache
        return scanAndPersist()
    }

    fun getCached(): List<InstalledApp> = cache

    fun isScanned(): Boolean = scanned

    fun isInstalled(packageName: String): Boolean =
        cache.any { it.packageName == packageName } ||
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            }.getOrDefault(false)

    fun isLauncherOrSearch(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (launcherOrSearchPackages.contains(packageName)) return true
        val lower = packageName.lowercase()
        return lower.contains("launcher") || lower.contains("globalsearch") || lower.contains("quicksearch")
    }

    /**
     * Resolve a target package from spoken text using known aliases + installed labels.
     * Prefer exact / longer keyword matches so "google pay" beats "pay".
     */
    fun resolvePackage(utterance: String): String? {
        val lower = utterance.lowercase().trim()
        if (lower.isEmpty()) return null

        // 1) Known aliases — longest keyword first
        val aliasHit = knownApps.keys
            .sortedByDescending { it.length }
            .firstOrNull { lower.contains(it) }
        if (aliasHit != null) {
            val pkg = firstInstalled(knownApps.getValue(aliasHit))
            if (pkg != null) return pkg
        }

        // 2) Match against installed app labels (exact, then contains)
        val apps = cache.ifEmpty { scanSync() }
        val tokens = lower.split(Regex("\\s+")).filter { it.length >= 3 }
        // Prefer "open X" / "X kholo" style extracted names
        val openName = extractOpenTarget(lower)
        if (!openName.isNullOrBlank()) {
            findByLabel(openName, apps)?.let { return it }
        }
        for (app in apps) {
            val label = app.label.lowercase()
            if (label.length >= 3 && lower.contains(label)) return app.packageName
        }
        for (token in tokens) {
            findByLabel(token, apps)?.let { return it }
        }
        return null
    }

    fun findByLabel(name: String, apps: List<InstalledApp> = cache.ifEmpty { scanSync() }): String? {
        val q = name.lowercase().trim()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.label.equals(q, ignoreCase = true) }?.let { return it.packageName }
        apps.firstOrNull { it.label.lowercase().startsWith(q) }?.let { return it.packageName }
        // Avoid very short fuzzy matches ("pay" matching random apps)
        if (q.length >= 4) {
            apps.firstOrNull { it.label.lowercase().contains(q) }?.let { return it.packageName }
        }
        return null
    }

    fun firstInstalled(candidates: List<String>): String? =
        candidates.firstOrNull { isInstalled(it) }

    private suspend fun scanAndPersist(): List<InstalledApp> = mutex.withLock {
        val apps = withContext(Dispatchers.IO) { scanSync() }
        cache = apps
        scanned = true
        preferences.setInstalledAppsInventory(
            apps.map { "${it.packageName}\t${it.label}" }.toSet()
        )
        preferences.setAppInventoryScanned(true)
        Timber.i("AppInventory: scanned ${apps.size} launchable apps")
        apps
    }

    /** Warm cache from DataStore without a full PackageManager walk when possible. */
    suspend fun restoreFromPreferences() {
        if (scanned && cache.isNotEmpty()) return
        val stored = preferences.installedAppsInventory.first()
        if (stored.isNotEmpty()) {
            cache = stored.mapNotNull { row ->
                val parts = row.split("\t", limit = 2)
                if (parts.size == 2) InstalledApp(parts[0], parts[1]) else null
            }
            scanned = preferences.appInventoryScanned.first()
            Timber.i("AppInventory: restored ${cache.size} apps from preferences")
        }
        if (cache.isEmpty()) {
            ensureScanned(force = true)
        }
    }

    private fun scanSync(): List<InstalledApp> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = try {
                ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (label.isBlank()) return@mapNotNull null
            InstalledApp(pkg, label)
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun extractOpenTarget(lower: String): String? {
        val patterns = listOf(
            Regex("""(?:open|launch|start)\s+(.+)$"""),
            Regex("""^(.+?)\s+(?:kholo|chalu\s+karo)$""")
        )
        for (p in patterns) {
            val m = p.find(lower) ?: continue
            val name = m.groupValues.getOrNull(1)?.trim()?.removeSuffix("app")?.trim()
            if (!name.isNullOrBlank() && name.length >= 2) return name
        }
        return null
    }

    companion object {
        /** Priority apps shown during onboarding scan progress. */
        val PRIORITY_SCAN_LABELS = listOf(
            "Settings", "WhatsApp", "PhonePe", "Paytm", "GPay",
            "Chrome", "YouTube", "Maps", "Camera", "Gmail"
        )
    }
}
