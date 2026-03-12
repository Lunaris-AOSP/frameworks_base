/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.ims.ui.viewmodel

import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ImsStatusBarIconSelectorTest : SysuiTestCase() {
    private val iconSet = ImsIconSet(single = 1, sim1 = 2, sim2 = 3, dual = 4)

    @Test
    fun selectIcon_noActiveSubs_returnsNull() {
        val result =
            ImsStatusBarIconSelector.selectIcon(
                iconSet = iconSet,
                subscriptions = listOf(subscription(subId = 1, slotIndex = 0)),
                activeSubIds = emptySet(),
            )

        assertThat(result).isNull()
    }

    @Test
    fun selectIcon_singleSubscription_usesSingleAsset() {
        val result =
            ImsStatusBarIconSelector.selectIcon(
                iconSet = iconSet,
                subscriptions = listOf(subscription(subId = 2, slotIndex = 1)),
                activeSubIds = setOf(2),
            )

        assertThat(result).isEqualTo(iconSet.single)
    }

    @Test
    fun selectIcon_dualSim_slotOneActive_usesSim1Asset() {
        val result =
            ImsStatusBarIconSelector.selectIcon(
                iconSet = iconSet,
                subscriptions =
                    listOf(
                        subscription(subId = 1, slotIndex = 0),
                        subscription(subId = 2, slotIndex = 1),
                    ),
                activeSubIds = setOf(1),
            )

        assertThat(result).isEqualTo(iconSet.sim1)
    }

    @Test
    fun selectIcon_dualSim_slotTwoActive_usesSim2Asset() {
        val result =
            ImsStatusBarIconSelector.selectIcon(
                iconSet = iconSet,
                subscriptions =
                    listOf(
                        subscription(subId = 1, slotIndex = 0),
                        subscription(subId = 2, slotIndex = 1),
                    ),
                activeSubIds = setOf(2),
            )

        assertThat(result).isEqualTo(iconSet.sim2)
    }

    @Test
    fun selectIcon_dualSim_bothActive_usesDualAsset() {
        val result =
            ImsStatusBarIconSelector.selectIcon(
                iconSet = iconSet,
                subscriptions =
                    listOf(
                        subscription(subId = 1, slotIndex = 0),
                        subscription(subId = 2, slotIndex = 1),
                    ),
                activeSubIds = setOf(1, 2),
            )

        assertThat(result).isEqualTo(iconSet.dual)
    }

    @Test
    fun selectIcon_unknownSlot_fallsBackToSingleAsset() {
        val result =
            ImsStatusBarIconSelector.selectIcon(
                iconSet = iconSet,
                subscriptions =
                    listOf(
                        subscription(subId = 1, slotIndex = 7),
                        subscription(subId = 2, slotIndex = 8),
                    ),
                activeSubIds = setOf(1),
            )

        assertThat(result).isEqualTo(iconSet.single)
    }

    private fun subscription(subId: Int, slotIndex: Int): SubscriptionModel =
        SubscriptionModel(
            subscriptionId = subId,
            carrierName = "Carrier $subId",
            simSlotIndex = slotIndex,
            profileClass = PROFILE_CLASS_UNSET,
        )
}
