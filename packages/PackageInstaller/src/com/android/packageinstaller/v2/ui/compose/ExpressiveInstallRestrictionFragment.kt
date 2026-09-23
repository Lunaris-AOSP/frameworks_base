package com.android.packageinstaller.v2.ui.compose

import android.content.DialogInterface
import android.os.Bundle
import android.text.TextUtils
import androidx.compose.runtime.Composable
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallStage
import com.android.packageinstaller.v2.ui.InstallActionListener

class ExpressiveInstallRestrictionFragment : InstallerDialogFragment() {
    private val listener get() = requireActivity() as InstallActionListener

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        listener.onNegativeResponse(InstallStage.STAGE_ABORTED)
    }

    @Composable
    override fun Content() {
        val messageId = requireArguments().getInt(ARG_MESSAGE)
        InstallerDialogContent(
            InstallerDialogState(
                key = messageId,
                title = getString(R.string.title_install_blocked),
                message = TextUtils.htmlEncode(getString(messageId)),
                tone = InstallerTone.Error,
                secondary = InstallerAction(getString(R.string.button_close)) {
                    listener.onNegativeResponse(InstallStage.STAGE_ABORTED)
                },
            ),
            actionsEnabled = actionsEnabled,
        )
    }

    companion object {
        private const val ARG_MESSAGE = "message"

        fun newInstance(messageId: Int) = ExpressiveInstallRestrictionFragment().apply {
            arguments = Bundle().apply { putInt(ARG_MESSAGE, messageId) }
        }
    }
}
