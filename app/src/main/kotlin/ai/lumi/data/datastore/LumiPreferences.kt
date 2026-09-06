package ai.lumi.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ai.lumi.inference.DeviceTier
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lumi_prefs")

@Singleton
class LumiPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val PREFERRED_LANGUAGE = stringPreferencesKey("preferred_language")
        val PREFERRED_LANGUAGES = stringSetPreferencesKey("preferred_languages")
        val BUBBLE_SIZE_DP = intPreferencesKey("bubble_size_dp")
        val DEVICE_TIER = stringPreferencesKey("device_tier")
        val MODELS_DOWNLOADED = booleanPreferencesKey("models_downloaded")
        val FIRST_LAUNCH_AT = longPreferencesKey("first_launch_at")
        val ADAPTIVE_MODE_KEY = stringPreferencesKey("adaptive_mode_key")
        val ADAPTIVE_MODE_ENABLED = booleanPreferencesKey("adaptive_mode_enabled")
        val TOTAL_TASKS_COMPLETED = longPreferencesKey("total_tasks_completed")
        /** Set to true once the user has completed onboarding permissions at least once. */
        val PERMISSIONS_COMPLETED_ONCE = booleanPreferencesKey("permissions_completed_once")
        val AUTO_TAP_ENABLED = booleanPreferencesKey("auto_tap_enabled")
        val APP_INVENTORY_SCANNED = booleanPreferencesKey("app_inventory_scanned")
        val INSTALLED_APPS_INVENTORY = stringSetPreferencesKey("installed_apps_inventory")
    }

    val onboardingComplete: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.ONBOARDING_COMPLETE] ?: false }

    val preferredLanguage: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_LANGUAGE] ?: "en" }

    val preferredLanguages: Flow<Set<String>> = context.dataStore.data
        .map { prefs ->
            val set = prefs[Keys.PREFERRED_LANGUAGES]
            if (!set.isNullOrEmpty()) set
            else setOf(prefs[Keys.PREFERRED_LANGUAGE] ?: "en")
        }

    val bubbleSizeDp: Flow<Int> = context.dataStore.data
        .map { it[Keys.BUBBLE_SIZE_DP] ?: 56 }

    val deviceTier: Flow<DeviceTier?> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.DEVICE_TIER]?.let { runCatching { DeviceTier.valueOf(it) }.getOrNull() }
        }

    val modelsDownloaded: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.MODELS_DOWNLOADED] ?: false }

    val adaptiveModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.ADAPTIVE_MODE_ENABLED] ?: false }

    val autoTapEnabled: Flow<Boolean> = context.dataStore.data
        // Automatic interaction must be an informed opt-in, never a default.
        .map { it[Keys.AUTO_TAP_ENABLED] ?: false }

    val appInventoryScanned: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.APP_INVENTORY_SCANNED] ?: false }

    val installedAppsInventory: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.INSTALLED_APPS_INVENTORY] ?: emptySet() }

    /**
     * True if the user has completed the permission onboarding screens at least once.
     * When true, permission screens that are already granted will be auto-skipped.
     */
    val permissionsCompletedOnce: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.PERMISSIONS_COMPLETED_ONCE] ?: false }

    suspend fun setOnboardingComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = value }
    }

    suspend fun setPreferredLanguage(lang: String) {
        context.dataStore.edit {
            it[Keys.PREFERRED_LANGUAGE] = lang
            it[Keys.PREFERRED_LANGUAGES] = setOf(lang)
        }
    }

    suspend fun setPreferredLanguages(languages: Set<String>) {
        context.dataStore.edit {
            it[Keys.PREFERRED_LANGUAGES] = languages
            if (languages.isNotEmpty()) {
                it[Keys.PREFERRED_LANGUAGE] = languages.first()
            }
        }
    }

    suspend fun setBubbleSizeDp(dp: Int) {
        context.dataStore.edit { it[Keys.BUBBLE_SIZE_DP] = dp }
    }

    suspend fun setDeviceTier(tier: DeviceTier) {
        context.dataStore.edit { it[Keys.DEVICE_TIER] = tier.name }
    }

    suspend fun setModelsDownloaded(value: Boolean) {
        context.dataStore.edit { it[Keys.MODELS_DOWNLOADED] = value }
    }

    suspend fun setAdaptiveModeKey(key: String) {
        context.dataStore.edit { it[Keys.ADAPTIVE_MODE_KEY] = key }
    }

    suspend fun setAdaptiveModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADAPTIVE_MODE_ENABLED] = enabled }
    }

    suspend fun setAutoTapEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_TAP_ENABLED] = enabled }
    }

    suspend fun setAppInventoryScanned(value: Boolean) {
        context.dataStore.edit { it[Keys.APP_INVENTORY_SCANNED] = value }
    }

    suspend fun setInstalledAppsInventory(entries: Set<String>) {
        context.dataStore.edit { it[Keys.INSTALLED_APPS_INVENTORY] = entries }
    }

    suspend fun incrementTasksCompleted() {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOTAL_TASKS_COMPLETED] = (prefs[Keys.TOTAL_TASKS_COMPLETED] ?: 0L) + 1
        }
    }

    suspend fun setPermissionsCompletedOnce(value: Boolean = true) {
        context.dataStore.edit { it[Keys.PERMISSIONS_COMPLETED_ONCE] = value }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
