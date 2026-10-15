package com.android.packageinstaller.v2.ui.compose

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallAppDetails
import com.android.packageinstaller.v2.model.InstallInstalling
import com.android.packageinstaller.v2.model.InstallStage
import com.android.packageinstaller.v2.model.InstallStaging
import com.android.packageinstaller.v2.model.InstallSuccess
import com.android.packageinstaller.v2.model.InstallUserActionRequired
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_VERIFICATION_CONFIRMATION
import com.android.packageinstaller.v2.model.InstallVerificationFailure
import com.android.packageinstaller.v2.ui.InstallActionListener
import com.android.packageinstaller.v2.ui.fragments.InstallationFragment
import com.android.packageinstaller.v2.viewmodel.InstallViewModel

class ExpressiveInstallationFragment : InstallerDialogFragment() {
    private var stage by mutableStateOf<InstallStage>(InstallStaging())
    private var details by mutableStateOf<InstallAppDetails?>(null)
    private var progress by mutableStateOf<Float?>(null)
    private val listener get() = requireActivity() as InstallActionListener

    override fun onStart() {
        updateUI()
        super.onStart()
    }

    fun updateUI() {
        if (!isAdded) return
        val model = ViewModelProvider(requireActivity())[InstallViewModel::class.java]
        stage = model.currentInstallStage.value ?: return
        details = model.repository.appDetails
        progress = model.stagingProgress.value?.takeIf { it >= 0 }?.let { it / 100f }
        isCancelable = stage !is InstallInstalling && stage !is InstallVerificationFailure &&
            (stage as? InstallUserActionRequired)?.actionReason != USER_ACTION_REASON_VERIFICATION_CONFIRMATION
    }

    fun setProgress(value: Int) {
        progress = value.takeIf { it >= 0 }?.let { it.coerceAtMost(100) / 100f }
    }

    override fun onUserDismiss() {
        if (stage is InstallSuccess) listener.openInstalledApp(null)
        else listener.onNegativeResponse(stage.stageCode)
    }

    @Composable
    override fun Content() {
        val view = LocalView.current
        val state = installDialogState(requireContext(), stage, details, progress, listener)
        var bypassSubmitted by remember(stage, actionsEnabled) { mutableStateOf(false) }
        InstallerDialogContent(
            state,
            actionsEnabled && !bypassSubmitted,
            isBottomSheet = isBottomSheet,
        ) { enabled ->
            val confirmation = stage as? InstallUserActionRequired
            val info = confirmation?.verificationInfo
            if (confirmation?.actionReason == USER_ACTION_REASON_VERIFICATION_CONFIRMATION &&
                info != null && InstallationFragment.isVerificationBypassAllowed(
                    info.userActionNeededReason, info.verificationPolicy
                )) {
                var expanded by rememberSaveable(info.userActionNeededReason, info.verificationPolicy) {
                    mutableStateOf(false)
                }
                if (!expanded) {
                    TextButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            expanded = true
                        },
                        enabled = enabled,
                    ) {
                        Text(
                            stringResource(R.string.more_details),
                            textAlign = if (isBottomSheet) TextAlign.Center else TextAlign.Start,
                        )
                    }
                }
                AnimatedVisibility(
                    expanded,
                    enter = if (isBottomSheet) fadeIn() else fadeIn() + expandIn(),
                    exit = if (isBottomSheet) fadeOut() else shrinkOut() + fadeOut(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = if (isBottomSheet) Alignment.CenterHorizontally
                            else Alignment.Start,
                    ) {
                        InstallerMessage(
                            stringResource(
                                if (confirmation.isAppUpdating) R.string.more_details_expanded_update_summary
                                else R.string.more_details_expanded_install_summary
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = if (isBottomSheet) TextAlign.Center else TextAlign.Start,
                        )
                        ExpressiveInstallerButton(
                            label = stringResource(if (confirmation.isAppUpdating) R.string.update_without_verifying else R.string.install_without_verifying),
                            primary = false,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (actionsEnabled && !bypassSubmitted) {
                                bypassSubmitted = true
                                listener.onPositiveResponse(USER_ACTION_REASON_VERIFICATION_CONFIRMATION)
                            }
                        }
                    }
                }
            }
        }
    }
}
