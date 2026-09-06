package ai.lumi.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import timber.log.Timber

/**
 * OriginOS / Funtouch OS compatibility helper for vivo and iQOO devices.
 * Handles aggressive OEM power management, auto-start permissions,
 * and service health monitoring to ensure Lumi remains responsive.
 */
object OriginOSCompat {

    /** Returns true if the device manufacturer is vivo or brand is iQOO. */
    fun isVivoOrIqoo(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("vivo") || brand.contains("iqoo")
    }

    /**
     * Intent to open the vivo / iQOO auto-start (background start) management screen.
     * Returns null if not on a vivo/iQOO device or if no specific intent matches.
     */
    fun getAutoStartIntent(context: Context): Intent? {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            )
        )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }
        return null
    }

    /**
     * Intent to open the high background power consumption settings for this app.
     */
    fun getHighBackgroundPowerIntent(context: Context): Intent? {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManager")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
            )
        )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }
        return null
    }

    /**
     * Request standard battery optimization exemption.
     */
    fun requestStandardBatteryExemption(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to request battery exemption")
        }
    }

    /**
     * Checks if LumiAccessibilityService is currently enabled in system settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.id.contains("LumiAccessibilityService", ignoreCase = true) }
    }

    /**
     * Checks if the overlay permission (draw on top of other apps) is granted.
     */
    fun isOverlayPermissionGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Verifies that the required services are active, and revives OverlayService if permissions are intact.
     */
    fun ensureOverlayServiceAlive(context: Context) {
        if (isOverlayPermissionGranted(context)) {
            try {
                OverlayService.start(context)
                Timber.d("OverlayService health verified / restarted")
            } catch (e: Exception) {
                Timber.e(e, "Failed to ensure OverlayService is running")
            }
        }
    }
}
