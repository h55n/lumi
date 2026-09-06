package ai.lumi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.lumi.data.datastore.LumiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val preferences: LumiPreferences
) : ViewModel() {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _startupEvent = MutableSharedFlow<StartupEvent>(replay = 1)
    val startupEvent: SharedFlow<StartupEvent> = _startupEvent.asSharedFlow()

    init {
        checkStartupState()
    }

    private fun checkStartupState() {
        viewModelScope.launch {
            val onboardingComplete = preferences.onboardingComplete.first()
            Timber.d("Onboarding complete: $onboardingComplete")
            _isReady.value = true
            if (onboardingComplete) {
                _startupEvent.emit(StartupEvent.Ready)
            } else {
                _startupEvent.emit(StartupEvent.NeedsOnboarding)
            }
        }
    }

    /**
     * Checks whether [LumiAccessibilityService] is running.
     *
     * Previously this emitted [StartupEvent.NeedsOnboarding] which sent the user back through
     * the full onboarding flow every time they returned from the Settings app — causing the
     * "permission re-asking loop" bug.
     *
     * Now it emits [StartupEvent.AccessibilityServiceDown] which the UI renders as a lightweight
     * non-blocking banner, prompting the user to re-enable the service without losing their place.
     */
    fun checkAccessibilityServiceHealth(context: Context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val running = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val lumiEnabled = running.any { it.id.contains("LumiAccessibilityService") }
        if (!lumiEnabled) {
            Timber.w("LumiAccessibilityService is not running!")
            viewModelScope.launch {
                // Do NOT emit NeedsOnboarding — that was the source of the re-asking loop.
                // Instead emit a lightweight banner event that the user can dismiss.
                _startupEvent.emit(StartupEvent.AccessibilityServiceDown)
            }
        }
    }
}

sealed class StartupEvent {
    data object NeedsOnboarding : StartupEvent()
    data object Ready : StartupEvent()
    /** Accessibility service stopped — show a non-blocking prompt to re-enable it. */
    data object AccessibilityServiceDown : StartupEvent()
}
