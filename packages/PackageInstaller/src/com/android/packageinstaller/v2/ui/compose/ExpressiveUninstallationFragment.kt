package com.android.packageinstaller.v2.ui.compose

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallAppDetails
import com.android.packageinstaller.v2.model.UninstallAborted
import com.android.packageinstaller.v2.model.UninstallStage
import com.android.packageinstaller.v2.model.UninstallUserActionRequired
import com.android.packageinstaller.v2.ui.UninstallActionListener
import com.android.packageinstaller.v2.viewmodel.UninstallViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExpressiveUninstallationFragment : InstallerDialogFragment() {
    private var stage by mutableStateOf<UninstallStage?>(null)
    private val listener get() = requireActivity() as UninstallActionListener

    override fun onStart() {
        updateUI()
        super.onStart()
    }

    fun updateUI() {
        if (!isAdded) return
        stage = ViewModelProvider(requireActivity())[UninstallViewModel::class.java]
            .currentUninstallStage.value
    }

    override fun onUserDismiss() {
        listener.onNegativeResponse()
    }

    @Composable
    override fun Content() {
        val current = stage ?: return
        val context = requireContext()
        val view = LocalView.current
        val confirmation = current as? UninstallUserActionRequired
        var keepData by rememberSaveable { mutableStateOf(false) }
        var uninstalling by rememberSaveable { mutableStateOf(false) }
        // Reserve the details layout while package metadata loads to avoid expanding on opening.
        val details by produceState<InstallAppDetails?>(
            initialValue = confirmation?.targetAppInfo?.let { app ->
                InstallAppDetails(
                    packageName = app.packageName,
                    targetSdk = app.targetSdkVersion.takeIf { it > 0 },
                )
            },
            key1 = confirmation?.targetAppInfo,
        ) {
            val app = confirmation?.targetAppInfo ?: return@produceState
            value = withContext(Dispatchers.IO) {
                val info = try {
                    context.packageManager.getPackageInfoAsUser(
                        app.packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        UserHandle.getUserId(app.uid),
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
                InstallAppDetails.fromPackageInfo(info ?: PackageInfo().apply {
                    packageName = app.packageName
                    applicationInfo = app
                })
            }
        }
        val state = when (current) {
            is UninstallAborted -> InstallerDialogState(
                key = current,
                title = getString(current.dialogTitleResource),
                appLabel = current.appLabel,
                appIcon = current.appIcon,
                message = TextUtils.htmlEncode(getString(current.dialogTextResource)),
                tone = InstallerTone.Error,
                secondary = InstallerAction(getString(R.string.button_close), listener::onNegativeResponse),
            )
            is UninstallUserActionRequired -> InstallerDialogState(
                key = current,
                title = if (uninstalling) {
                    getString(
                        if (current.positiveButtonResId == R.string.button_archive) {
                            R.string.installer_archiving
                        } else R.string.uninstalling
                    )
                } else current.getTitle(context),
                subtitle = getString(
                    if (uninstalling) R.string.installer_installing_hint
                    else R.string.installer_uninstall_hint
                ),
                appLabel = current.getAppLabel(context),
                appIcon = current.appIcon,
                details = details,
                message = current.getMessage(context)?.let(TextUtils::htmlEncode),
                busy = uninstalling,
                primary = if (uninstalling) null else InstallerAction(
                    getString(current.positiveButtonResId)
                ) {
                    uninstalling = true
                    isCancelable = false
                    listener.onPositiveResponse(keepData && current.appDataSize > 0)
                },
                secondary = if (uninstalling) null else InstallerAction(
                    getString(R.string.button_cancel), listener::onNegativeResponse
                ),
            )
            else -> return
        }
        InstallerDialogContent(state, actionsEnabled, isBottomSheet = isBottomSheet) { enabled ->
            if (confirmation != null && confirmation.appDataSize > 0 && !uninstalling) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    val optionModifier = Modifier.fillMaxWidth().selectable(
                        selected = keepData,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            keepData = !keepData
                        },
                    ).padding(16.dp)
                    val label = getString(R.string.message_uninstall_keep_data)
                    val size = Formatter.formatFileSize(context, confirmation.appDataSize)
                    if (isBottomSheet) {
                        Column(
                            optionModifier,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = keepData, onClick = null, enabled = enabled)
                            KeepDataDescription(label, size, Modifier.fillMaxWidth(), TextAlign.Center)
                        }
                    } else {
                        Row(
                            optionModifier,
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = keepData, onClick = null, enabled = enabled)
                            KeepDataDescription(
                                label, size, Modifier.weight(1f), TextAlign.Start,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeepDataDescription(
    label: String,
    size: String,
    modifier: Modifier,
    textAlign: TextAlign,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleSmall,
            textAlign = textAlign,
        )
        Text(
            size,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
    }
}
