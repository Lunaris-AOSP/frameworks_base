/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.jank.Cuj
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.Classifier
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.remedia.domain.interactor.MediaInteractor
import com.android.systemui.media.remedia.domain.model.MediaActionModel
import com.android.systemui.media.remedia.domain.model.MediaOutputDeviceModel
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaFalsingSystem
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.panels.data.repository.AxMediaHistoryRepository
import com.android.systemui.qs.panels.data.repository.AxQsSettingsRepository
import com.android.systemui.qs.panels.shared.model.AxMediaSurface
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class AxMediaViewModel
@Inject
constructor(
    private val interactor: MediaInteractor,
    private val falsingSystem: MediaFalsingSystem,
    private val mediaCarouselInteractor: MediaCarouselInteractor,
    private val mediaHistoryRepository: AxMediaHistoryRepository,
    private val activityStarter: ActivityStarter,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val shadeInteractor: ShadeInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    mediaSettingsRepository:
        com.android.systemui.media.remedia.data.repository.LockscreenMediaSettingsRepository,
    qsSettingsRepository: AxQsSettingsRepository,
    bluetoothController: BluetoothController,
) {
    private var scrubbingSessionKey: Any? by mutableStateOf(null)
    private var scrubbingTrack: Triple<Any, String, String>? by mutableStateOf(null)
    private var gutsSessionKey: Any? by mutableStateOf(null)
    private var scrubProgress by mutableFloatStateOf(0f)
    private var dismissedSessions by mutableStateOf(emptyMap<AxMediaSurface, Set<Any>>())
    private val sessions by derivedStateOf { interactor.sessions }

    val sessionCount: Int
        get() = sessions.size

    private val activeSessions by derivedStateOf { sessions.filter { it.isDisplayable() } }

    val currentSession by derivedStateOf {
        val selected = sessions.getOrNull(interactor.currentCarouselIndex)
        selected?.takeIf { it.isDisplayable() } ?: activeSessions.firstOrNull()
    }

    val showOnLockscreen = mediaCarouselInteractor.allowMediaOnLockscreen
    val lockscreenStyle = mediaSettingsRepository.style
    val isShadeExpanded: StateFlow<Boolean> = shadeInteractor.isAnyExpanded
    val isShadeInteracting: StateFlow<Boolean> = shadeInteractor.isUserInteracting
    var allowGlowOnPanelShown by mutableStateOf(true)
    val currentKeyguardState: StateFlow<KeyguardState> =
        keyguardTransitionInteractor.currentKeyguardState
    val lockscreenTransition = keyguardTransitionInteractor.startedKeyguardTransitionStep
    val lastMediaPackage = mediaHistoryRepository.lastMediaPackage
    val mediaStyle = qsSettingsRepository.mediaStyle
    val mediaWideStyle = qsSettingsRepository.mediaWideStyle
    val notificationMediaStyle = qsSettingsRepository.notificationMediaStyle
    val mediaMotionArt = qsSettingsRepository.mediaMotionArt
    val notificationMediaStatusGlow = qsSettingsRepository.notificationMediaStatusGlow
    val mediaTileStatusGlow = qsSettingsRepository.mediaTileStatusGlow
    val seekBarStyle = mediaSettingsRepository.seekBarStyle
    val isBluetoothConnected: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : BluetoothController.Callback {
                        override fun onBluetoothStateChange(enabled: Boolean) {
                            trySend(bluetoothController.isBluetoothConnected)
                        }

                        override fun onBluetoothDevicesChanged() {
                            trySend(bluetoothController.isBluetoothConnected)
                        }
                    }
                bluetoothController.addCallback(callback)
                trySend(bluetoothController.isBluetoothConnected)
                awaitClose { bluetoothController.removeCallback(callback) }
            }
            .flowOn(mainDispatcher)
            .distinctUntilChanged()
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                bluetoothController.isBluetoothConnected,
            )

    init {
        mediaHistoryRepository.startListening()
    }

    fun synchronizeSession(sessionKey: Any?) {
        if (scrubbingSessionKey != null && scrubbingSessionKey != sessionKey) {
            cancelScrubbing()
        }
        if (gutsSessionKey != null && gutsSessionKey != sessionKey) {
            gutsSessionKey = null
        }
    }

    fun cancelScrubbing() {
        scrubbingSessionKey = null
        scrubbingTrack = null
    }

    fun visibleSessions(surface: AxMediaSurface): List<MediaSessionModel> {
        return activeSessions.filter { it.isVisibleOn(surface) }
    }

    fun currentSession(surface: AxMediaSurface): MediaSessionModel? {
        val visible = visibleSessions(surface)
        return visible.firstOrNull { it.key == currentSession?.key } ?: visible.firstOrNull()
    }

    fun hasVisibleSessions(surface: AxMediaSurface): Boolean = visibleSessions(surface).isNotEmpty()

    fun sessionForKey(sessionKey: Any): MediaSessionModel? {
        return sessions.firstOrNull { it.key == sessionKey }
    }

    fun isSessionVisible(sessionKey: Any, surface: AxMediaSurface): Boolean {
        return sessionForKey(sessionKey)?.isVisibleOn(surface) == true
    }

    fun hasVisibleGuts(): Boolean {
        val sessionKey = gutsSessionKey ?: return false
        return sessions.any { it.key == sessionKey && it.isDisplayable() }
    }

    fun isGutsVisible(session: MediaSessionModel): Boolean = gutsSessionKey == session.key

    fun showGuts(session: MediaSessionModel) {
        gutsSessionKey = session.key
    }

    fun closeGuts() {
        gutsSessionKey = null
    }

    fun cancelGuts() {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY, ::closeGuts)
    }

    fun isSwipeFalseTouch(): Boolean = falsingSystem.isFalseTouch(Classifier.MEDIA_CAROUSEL_SWIPE)

    fun dismissBySwipe(surface: AxMediaSurface) {
        if (!surface.dismissible) return
        dismissSessions(surface, activeSessions.mapTo(mutableSetOf()) { it.key })
        closeGuts()
    }

    fun dismissFromSurface(session: MediaSessionModel, surface: AxMediaSurface) {
        if (!surface.dismissible) return
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) {
            dismissSessions(surface, setOf(session.key))
            closeGuts()
        }
    }

    fun openSettings() {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) {
            closeGuts()
            interactor.openMediaSettings()
        }
    }

    fun progress(session: MediaSessionModel): Float {
        if (scrubbingTrack == Triple(session.key, session.title, session.subtitle))
            return scrubProgress
        if (session.durationMs <= 0L) return 0f
        return (session.positionMs.toFloat() / session.durationMs).coerceIn(0f, 1f)
    }

    fun onScrubChange(session: MediaSessionModel, progress: Float) {
        val latestSession = sessionForKey(session.key)
        if (
            latestSession?.canBeScrubbed != true ||
                latestSession.title != session.title ||
                latestSession.subtitle != session.subtitle
        ) {
            cancelScrubbing()
            return
        }
        scrubbingSessionKey = session.key
        scrubbingTrack = Triple(session.key, session.title, session.subtitle)
        scrubProgress = progress.coerceIn(0f, 1f)
    }

    fun onScrubFinished(session: MediaSessionModel, dragDelta: Offset) {
        val latestSession = sessionForKey(session.key)
        if (
            latestSession?.canBeScrubbed == true &&
                scrubbingTrack ==
                    Triple(latestSession.key, latestSession.title, latestSession.subtitle) &&
                scrubbingTrack == Triple(session.key, session.title, session.subtitle) &&
                dragDelta.isHorizontal() &&
                !falsingSystem.isFalseTouch(Classifier.MEDIA_SEEKBAR)
        ) {
            interactor.seek(session.key, (scrubProgress * latestSession.durationMs).roundToLong())
        }
        cancelScrubbing()
    }

    fun openSession(session: MediaSessionModel, expandable: Expandable) {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) { session.onClick(expandable) }
    }

    fun openLastMediaApp(expandable: Expandable) {
        falsingSystem.runIfNotFalseTap(FalsingManager.LOW_PENALTY) {
            applicationScope.launch(context = mainDispatcher) {
                val target = mediaHistoryRepository.getLaunchTarget() ?: return@launch
                activityStarter.postStartActivityDismissingKeyguard(
                    target.intent,
                    0,
                    expandable.activityTransitionController(
                        Cuj.CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER
                    ),
                    null,
                    target.userHandle,
                )
            }
        }
    }

    fun openOutput(device: MediaOutputDeviceModel, expandable: Expandable) {
        falsingSystem.runIfNotFalseTap(FalsingManager.MODERATE_PENALTY) {
            device.onClick(expandable)
        }
    }

    fun runAction(action: MediaActionModel.Action) {
        falsingSystem.runIfNotFalseTap(FalsingManager.MODERATE_PENALTY) { action.onClick?.invoke() }
    }

    private fun Offset.isHorizontal(): Boolean = abs(x) >= abs(y)

    private fun MediaSessionModel.isDisplayable(): Boolean = isActive && title.isNotBlank()

    private fun MediaSessionModel.isVisibleOn(surface: AxMediaSurface): Boolean {
        return isDisplayable() &&
            (!surface.dismissible || key !in dismissedSessions[surface].orEmpty())
    }

    private fun dismissSessions(surface: AxMediaSurface, keys: Set<Any>) {
        val currentKeys = sessions.mapTo(mutableSetOf()) { it.key }
        val retained =
            dismissedSessions.mapValues { (_, dismissed) -> dismissed.intersect(currentKeys) }
        dismissedSessions = retained + (surface to (retained[surface].orEmpty() + keys))
    }
}
