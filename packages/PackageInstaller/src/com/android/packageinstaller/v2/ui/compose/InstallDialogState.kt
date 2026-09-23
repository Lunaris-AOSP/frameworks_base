package com.android.packageinstaller.v2.ui.compose

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED
import android.content.pm.PackageInstaller.DeveloperVerificationUserConfirmationInfo.DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE
import android.content.pm.PackageInstaller.DeveloperVerificationUserConfirmationInfo.DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallAborted
import com.android.packageinstaller.v2.model.InstallAppDetails
import com.android.packageinstaller.v2.model.InstallFailed
import com.android.packageinstaller.v2.model.InstallInstalling
import com.android.packageinstaller.v2.model.InstallStage
import com.android.packageinstaller.v2.model.InstallStaging
import com.android.packageinstaller.v2.model.InstallSuccess
import com.android.packageinstaller.v2.model.InstallUserActionRequired
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_ANONYMOUS_SOURCE
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_INSTALL_CONFIRMATION
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_UNKNOWN_SOURCE
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_VERIFICATION_CONFIRMATION
import com.android.packageinstaller.v2.model.InstallVerificationFailure
import com.android.packageinstaller.v2.ui.InstallActionListener

internal fun installDialogState(
    context: Context,
    stage: InstallStage,
    details: InstallAppDetails?,
    progress: Float?,
    listener: InstallActionListener,
): InstallerDialogState {
    fun text(id: Int) = context.getString(id)
    fun action(id: Int, callback: () -> Unit) = InstallerAction(text(id), callback)
    val cancel = action(R.string.button_cancel) { listener.onNegativeResponse(stage.stageCode) }
    val close = action(R.string.button_close) { listener.onNegativeResponse(stage.stageCode) }
    val base = InstallerDialogState(key = stage, title = text(R.string.title_install))
    return when (stage) {
        is InstallStaging -> base.copy(
            title = text(R.string.title_install_staging),
            subtitle = text(R.string.installer_staging_hint),
            busy = true,
            progress = progress,
            secondary = cancel,
        )
        is InstallInstalling -> base.copy(
            title = text(if (stage.isAppUpdating) R.string.title_updating else R.string.title_installing),
            subtitle = text(R.string.installer_installing_hint),
            appLabel = stage.appLabel,
            appIcon = stage.appIcon,
            details = details,
            busy = true,
        )
        is InstallSuccess -> {
            val launchIntent = stage.resultIntent
            val canOpen = launchIntent != null &&
                context.packageManager.queryIntentActivities(launchIntent, 0).isNotEmpty()
            base.copy(
                title = text(if (stage.isAppUpdating) R.string.title_updated else R.string.title_installed),
                subtitle = text(R.string.installer_success_hint),
                appLabel = stage.appLabel,
                appIcon = stage.appIcon,
                details = details,
                tone = InstallerTone.Success,
                primary = if (canOpen) action(R.string.button_open) {
                    listener.openInstalledApp(launchIntent)
                } else null,
                secondary = action(R.string.button_done) { listener.openInstalledApp(null) },
            )
        }
        is InstallAborted -> base.copy(
            title = text(R.string.title_cant_install_app),
            message = text(R.string.message_parse_failed),
            tone = InstallerTone.Error,
            secondary = action(R.string.button_close) {
                listener.onNegativeResponse(stage.activityResultCode, stage.resultIntent)
            },
        )
        is InstallFailed -> {
            val title = when (stage.statusCode) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.title_install_failed_blocked
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.title_install_failed_incompatible
                PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.title_install_failed_less_storage
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INVALID -> R.string.title_cant_install_app
                else -> R.string.title_install_failed_not_installed
            }
            val message = when (stage.statusCode) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.message_install_failed_blocked
                PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.message_install_failed_conflict
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.message_install_failed_incompatible
                PackageInstaller.STATUS_FAILURE_INVALID -> R.string.message_install_failed_invalid
                PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.message_install_failed_less_storage
                else -> null
            }
            base.copy(
                title = text(title),
                appLabel = stage.appLabel,
                appIcon = stage.appIcon,
                details = details,
                message = message?.let(::text),
                tone = InstallerTone.Error,
                primary = if (stage.statusCode == PackageInstaller.STATUS_FAILURE_STORAGE) {
                    action(R.string.button_manage_apps, listener::sendManageAppsIntent)
                } else null,
                secondary = close,
            )
        }
        is InstallUserActionRequired -> when (stage.actionReason) {
            USER_ACTION_REASON_INSTALL_CONFIRMATION -> {
                val existingOwner = stage.getExistingUpdateOwnerLabel(context)
                val requestedOwner = stage.getRequestedUpdateOwnerLabel(context)
                val changingOwner = stage.isAppUpdating &&
                    existingOwner != null && requestedOwner != null
                base.copy(
                    title = if (changingOwner) {
                        context.getString(R.string.title_update_ownership_change, requestedOwner)
                    } else {
                        text(if (stage.isAppUpdating) R.string.title_update else R.string.title_install)
                    },
                    subtitle = text(
                        if (stage.isAppUpdating) R.string.installer_update_hint
                        else R.string.installer_install_hint
                    ),
                    appLabel = stage.appLabel,
                    appIcon = stage.appIcon,
                    details = details,
                    message = if (changingOwner) context.getString(
                        R.string.message_update_owner_change,
                        android.text.TextUtils.htmlEncode(existingOwner),
                    ) else null,
                    primary = action(
                        if (changingOwner) R.string.button_update_anyway
                        else if (stage.isAppUpdating) R.string.button_update else R.string.button_install
                    ) { listener.onPositiveResponse(USER_ACTION_REASON_INSTALL_CONFIRMATION) },
                    secondary = cancel,
                )
            }
            USER_ACTION_REASON_UNKNOWN_SOURCE -> base.copy(
                title = text(R.string.title_unknown_source_blocked),
                appLabel = stage.appLabel,
                appIcon = stage.appIcon,
                message = text(R.string.message_external_source_blocked),
                primary = stage.unknownSourcePackageName?.let { source ->
                    action(R.string.external_sources_settings) { listener.sendUnknownAppsIntent(source) }
                },
                secondary = cancel,
            )
            USER_ACTION_REASON_ANONYMOUS_SOURCE -> base.copy(
                title = text(R.string.title_unknown_source_blocked),
                message = text(R.string.message_anonymous_source_warning),
                primary = action(R.string.button_continue) {
                    listener.onPositiveResponse(USER_ACTION_REASON_ANONYMOUS_SOURCE)
                },
                secondary = cancel,
            )
            USER_ACTION_REASON_VERIFICATION_CONFIRMATION -> {
                val info = stage.verificationInfo
                val reason = info?.userActionNeededReason
                    ?: DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN
                val policy = info?.verificationPolicy ?: DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED
                val dismiss = action(R.string.ok) {
                    listener.onNegativeResponse(InstallStage.STAGE_VERIFICATION_CONFIRMATION_REQUIRED)
                }
                val retry = policy == DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED &&
                    reason == DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE
                base.copy(
                    title = text(verificationTitle(reason)),
                    appLabel = stage.appLabel,
                    appIcon = stage.appIcon,
                    details = details,
                    message = text(verificationMessage(reason, policy, stage.isAppUpdating)),
                    tone = InstallerTone.Error,
                    primary = if (retry) dismiss else null,
                    secondary = if (retry) {
                        action(R.string.button_retry, listener::onRetryResponse)
                    } else dismiss,
                )
            }
            else -> base.copy(secondary = cancel)
        }
        is InstallVerificationFailure -> base.copy(
            title = text(verificationTitle(stage.failureReason)),
            message = text(verificationMessage(
                stage.failureReason,
                DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED,
                stage.isAppUpdating,
            )),
            tone = InstallerTone.Error,
            secondary = action(R.string.ok) { listener.onNegativeResponse(stage.stageCode) },
        )
        else -> base.copy(secondary = close)
    }
}

private fun verificationTitle(reason: Int): Int = when (reason) {
    DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN -> R.string.cannot_install_verification_unavailable_title
    DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE -> R.string.cannot_install_verification_no_internet_title
    else -> R.string.cannot_install_app_blocked_title
}

private fun verificationMessage(reason: Int, policy: Int, updating: Boolean): Int = when (reason) {
    DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_UNKNOWN -> {
        if (policy == DEVELOPER_VERIFICATION_POLICY_BLOCK_FAIL_CLOSED) {
            if (updating) R.string.cannot_update_verification_unavailable_fail_closed_summary
            else R.string.cannot_install_verification_unavailable_fail_closed_summary
        } else {
            if (updating) R.string.cannot_update_verification_unavailable_summary
            else R.string.cannot_install_verification_unavailable_summary
        }
    }
    DEVELOPER_VERIFICATION_USER_ACTION_NEEDED_REASON_NETWORK_UNAVAILABLE -> {
        if (updating) R.string.cannot_update_verification_no_internet_summary
        else R.string.cannot_install_verification_no_internet_summary
    }
    else -> if (updating) R.string.cannot_update_app_blocked_summary
        else R.string.cannot_install_app_blocked_summary
}
