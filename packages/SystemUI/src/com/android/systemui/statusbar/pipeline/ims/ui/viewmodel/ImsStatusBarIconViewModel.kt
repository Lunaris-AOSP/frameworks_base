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

import android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.ims.data.repository.ImsRepository
import com.android.systemui.statusbar.pipeline.ims.data.repository.ImsRepositoryStore
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

interface ImsStatusBarIconViewModel {
    val icon: StateFlow<Icon?>
}

@SysUISingleton
class VoLteStatusBarIconViewModel
@Inject
constructor(
    interactor: MobileIconsInteractor,
    imsRepositoryStore: ImsRepositoryStore,
    @Application scope: CoroutineScope,
) :
    BaseImsStatusBarIconViewModel(
        interactor = interactor,
        imsRepositoryStore = imsRepositoryStore,
        scope = scope,
        iconSet =
            ImsIconSet(
                single = R.drawable.ic_nk_volte,
                sim1 = R.drawable.ic_nk_volte1,
                sim2 = R.drawable.ic_nk_volte2,
                dual = R.drawable.ic_nk_volte12,
            ),
        contentDescriptionRes = R.string.accessibility_status_bar_volte,
        stateSelector = { it.isVoLteAvailable },
    )

@SysUISingleton
class VoWifiStatusBarIconViewModel
@Inject
constructor(
    interactor: MobileIconsInteractor,
    imsRepositoryStore: ImsRepositoryStore,
    @Application scope: CoroutineScope,
) :
    BaseImsStatusBarIconViewModel(
        interactor = interactor,
        imsRepositoryStore = imsRepositoryStore,
        scope = scope,
        iconSet =
            ImsIconSet(
                single = R.drawable.ic_nk_vowifi,
                sim1 = R.drawable.ic_nk_vowifi1,
                sim2 = R.drawable.ic_nk_vowifi2,
                dual = R.drawable.ic_nk_vowifi12,
            ),
        contentDescriptionRes = R.string.accessibility_status_bar_vowifi,
        stateSelector = { it.isVoWifiAvailable },
    )

abstract class BaseImsStatusBarIconViewModel(
    interactor: MobileIconsInteractor,
    imsRepositoryStore: ImsRepositoryStore,
    scope: CoroutineScope,
    private val iconSet: ImsIconSet,
    @StringRes private val contentDescriptionRes: Int,
    private val stateSelector: (ImsRepository) -> StateFlow<Boolean>,
) : ImsStatusBarIconViewModel {
    override val icon: StateFlow<Icon?> =
        interactor.filteredSubscriptions
            .flatMapLatest { subscriptions ->
                if (subscriptions.isEmpty()) {
                    flowOf(null)
                } else {
                    combine(
                        subscriptions.map { subscription ->
                            stateSelector(imsRepositoryStore.getRepoForSubId(subscription.subscriptionId))
                                .map { isActive -> subscription.subscriptionId to isActive }
                        }
                    ) { availability ->
                        val activeSubIds =
                            availability
                                .filter { it.second }
                                .map { it.first }
                                .toSet()
                        ImsStatusBarIconSelector.selectIcon(
                            iconSet = iconSet,
                            subscriptions = subscriptions,
                            activeSubIds = activeSubIds,
                        )?.let { resId ->
                            Icon.Resource(
                                resId = resId,
                                contentDescription = ContentDescription.Resource(contentDescriptionRes),
                            )
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
}

data class ImsIconSet(
    @DrawableRes val single: Int,
    @DrawableRes val sim1: Int,
    @DrawableRes val sim2: Int,
    @DrawableRes val dual: Int,
)

internal object ImsStatusBarIconSelector {
    @DrawableRes
    fun selectIcon(
        iconSet: ImsIconSet,
        subscriptions: List<SubscriptionModel>,
        activeSubIds: Set<Int>,
    ): Int? {
        if (activeSubIds.isEmpty()) {
            return null
        }

        if (subscriptions.size <= 1) {
            return iconSet.single
        }

        val activeSlots =
            subscriptions
                .filter { activeSubIds.contains(it.subscriptionId) }
                .mapNotNull { it.normalizedSimSlotIndex() }
                .toSet()

        return when {
            activeSlots.contains(SIM_SLOT_1) && activeSlots.contains(SIM_SLOT_2) -> iconSet.dual
            activeSlots == setOf(SIM_SLOT_1) -> iconSet.sim1
            activeSlots == setOf(SIM_SLOT_2) -> iconSet.sim2
            else -> iconSet.single
        }
    }

    private fun SubscriptionModel.normalizedSimSlotIndex(): Int? =
        when (simSlotIndex) {
            SIM_SLOT_1,
            SIM_SLOT_2 -> simSlotIndex
            INVALID_SIM_SLOT_INDEX -> null
            else -> null
        }

    private const val SIM_SLOT_1 = 0
    private const val SIM_SLOT_2 = 1
}
