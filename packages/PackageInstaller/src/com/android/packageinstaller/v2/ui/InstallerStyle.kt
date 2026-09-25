package com.android.packageinstaller.v2.ui

import android.content.Context
import android.provider.Settings
import com.android.packageinstaller.R

enum class InstallerStyle(val value: Int) {
    AOSP(0),
    COMPOSE_DIALOG(1),
    COMPOSE_BOTTOM_SHEET(2);

    companion object {
        private const val SETTING_NAME = "package_installer_style"

        @JvmStatic
        fun get(context: Context): InstallerStyle {
            val default = if (context.resources.getBoolean(R.bool.config_useExpressivePackageInstaller)) {
                COMPOSE_DIALOG
            } else {
                AOSP
            }
            val value = Settings.Secure.getInt(
                context.contentResolver,
                SETTING_NAME,
                default.value,
            )
            return entries.firstOrNull { it.value == value } ?: default
        }
    }
}
