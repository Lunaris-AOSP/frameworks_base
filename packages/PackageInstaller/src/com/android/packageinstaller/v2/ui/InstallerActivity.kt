package com.android.packageinstaller.v2.ui

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.PackageUtil
import com.android.packageinstaller.v2.ui.compose.InstallerDialogFragment

abstract class InstallerActivity : FragmentActivity() {
    protected lateinit var installerStyle: InstallerStyle
        private set

    private var finishRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedStyle = savedInstanceState?.getInt(STATE_STYLE, -1)
        installerStyle = InstallerStyle.entries.firstOrNull { it.value == savedStyle }
            ?: InstallerStyle.get(this)
        if (installerStyle == InstallerStyle.AOSP) {
            setTheme(R.style.Theme_AlertDialogActivity)
            if (PackageUtil.isMaterialDesignEnabled(this)) {
                theme.applyStyle(R.style.Theme_AlertDialogActivity_Material, true)
            }
        }
        super.onCreate(savedInstanceState)
    }

    protected fun showInstallerDialog(fragment: DialogFragment?, tag: String) {
        if (fragment is InstallerDialogFragment &&
            installerStyle == InstallerStyle.COMPOSE_BOTTOM_SHEET) {
            fragment.showsDialog = false
            supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment, tag)
                .commit()
        } else {
            fragment?.show(supportFragmentManager, tag)
        }
    }

    override fun finish() {
        finishAfterInstallerDismissal()
    }

    protected fun finishAfterInstallerDismissal(onFinished: () -> Unit = {}) {
        if (finishRequested) return
        finishRequested = true
        val finish = {
            super.finish()
            onFinished()
        }
        val sheet = supportFragmentManager.fragments.filterIsInstance<InstallerDialogFragment>()
            .firstOrNull { it.isBottomSheet }
        if (sheet != null) {
            sheet.dismissWithAnimation(finish)
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_STYLE, installerStyle.value)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val STATE_STYLE = "installer_ui_style"
    }
}
