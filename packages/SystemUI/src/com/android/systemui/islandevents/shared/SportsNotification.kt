/* Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.islandevents.shared

internal fun isSportsNotification(packageName: String, groupKey: String?): Boolean =
    packageName in sportsPackages ||
        (packageName == "com.google.android.googlequicksearchbox" &&
            groupKey?.contains("::sports", ignoreCase = true) == true)

private val sportsPackages =
    setOf(
        "com.espn.score_center",
        "com.fivemobile.thescore",
        "com.yahoo.mobile.client.android.sportacular",
        "com.sofascore.app",
        "com.fotmob",
        "com.foxsports.android",
        "com.cbs.sports.android",
        "com.bleacherreport.android.teamstream",
        "com.nbaimd.gametime.nba2011",
        "com.nfl.fantasy.core",
        "com.mlb.atbat",
        "com.nhl.gc1112.free",
        "livescore.livesportsmedia.com",
        "com.flashscore.en",
        "com.365scores.android",
        "lunosoftware.sportsalerts",
        "com.onefootball.brasil",
    )
