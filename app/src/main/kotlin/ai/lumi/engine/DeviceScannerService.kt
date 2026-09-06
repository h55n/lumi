package ai.lumi.engine

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import ai.lumi.uimap.UIMapWorker

@Singleton
class DeviceScannerService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appInventory: AppInventory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    fun startDeepScan() {
        if (scanJob?.isActive == true) {
            Timber.w("Deep scan already in progress")
            return
        }

        scanJob = scope.launch {
            Timber.i("Starting Deep Device Scan...")
            val apps = appInventory.ensureScanned(force = true)
            val launchableApps = apps.filter { !appInventory.isLauncherOrSearch(it.packageName) }
            
            Timber.i("Found ${launchableApps.size} apps to index")
            val pm = context.packageManager
            
            for ((index, app) in launchableApps.withIndex()) {
                try {
                    val pkgInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(
                            android.content.pm.PackageManager.GET_ACTIVITIES.toLong()
                        ))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
                    }
                    val activityCount = pkgInfo.activities?.size ?: 0
                    Timber.d("Indexed ${index + 1}/${launchableApps.size}: ${app.label} (${app.packageName}) with $activityCount activities")
                } catch (e: Exception) {
                    Timber.w(e, "Metadata scan skipped for ${app.packageName}")
                }
            }
            
            Timber.i("Deep scan complete: ${launchableApps.size} apps indexed with action templates ready.")
        }
    }
}
