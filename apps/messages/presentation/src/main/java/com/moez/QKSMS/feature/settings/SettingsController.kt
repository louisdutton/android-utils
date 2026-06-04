/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.feature.settings

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.QkDialog
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.extensions.animateLayoutChanges
import dev.octoshrimpy.quik.common.widget.PreferenceView
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.databinding.SettingsControllerBinding
import dev.octoshrimpy.quik.feature.settings.swipe.SwipeActionsController
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class SettingsController : QkController<SettingsControllerBinding, SettingsView, SettingsState, SettingsPresenter>(), SettingsView {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): SettingsControllerBinding =
        SettingsControllerBinding.inflate(inflater, container, false)

    @Inject lateinit var context: Context
    @Inject lateinit var mmsSizeDialog: QkDialog
    @Inject lateinit var messageLinkHandlingDialog: QkDialog

    @Inject override lateinit var presenter: SettingsPresenter

    private val signatureDialog: TextInputDialog by lazy {
        TextInputDialog(activity!!, context.getString(R.string.settings_signature_title), signatureSubject::onNext)
    }

    private val signatureSubject: Subject<String> = PublishSubject.create()

    private val progressAnimator by lazy { ObjectAnimator.ofInt(binding.syncingProgress, "progress", 0, 0) }

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun onViewCreated() {
        binding.preferences.postDelayed({ binding.preferences.animateLayoutChanges = true }, 100)

        mmsSizeDialog.adapter.setData(R.array.mms_sizes, R.array.mms_sizes_ids)
        messageLinkHandlingDialog.adapter.setData(R.array.messageLinkHandlings, R.array.messageLinkHandling_ids)

    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.title_settings)
        showBackButton(true)
    }

    override fun preferenceClicks(): Observable<PreferenceView> = (0 until binding.preferences.childCount)
            .map { index -> binding.preferences.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .let { preferences -> Observable.merge(preferences) }

    override fun signatureChanged(): Observable<String> = signatureSubject

    override fun mmsSizeSelected(): Observable<Int> = mmsSizeDialog.adapter.menuItemClicks

    override fun messageLinkHandlingSelected(): Observable<Int> = messageLinkHandlingDialog.adapter.menuItemClicks

    override fun render(state: SettingsState) {
        binding.autoEmoji.checkbox?.isChecked = state.autoEmojiEnabled

        binding.delivery.checkbox?.isChecked = state.deliveryEnabled

        binding.unreadAtTop.checkbox?.isChecked = state.unreadAtTopEnabled

        binding.signature.summary = state.signature.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.settings_signature_summary)

        binding.unicode.checkbox?.isChecked = state.stripUnicodeEnabled
        binding.mobileOnly.checkbox?.isChecked = state.mobileOnly

        binding.longAsMms.checkbox?.isChecked = state.longAsMms

        binding.mmsSize.summary = state.maxMmsSizeSummary
        mmsSizeDialog.adapter.selectedItem = state.maxMmsSizeId

        binding.messsageLinkHandling.summary = state.messageLinkHandlingSummary
        messageLinkHandlingDialog.adapter.selectedItem = state.messageLinkHandlingId

        binding.disableScreenshots.checkbox?.isChecked = state.disableScreenshotsEnabled

        when (state.syncProgress) {
            is SyncRepository.SyncProgress.Idle -> binding.syncingProgress.isVisible = false

            is SyncRepository.SyncProgress.Running -> {
                binding.syncingProgress.isVisible = true
                binding.syncingProgress.max = state.syncProgress.max
                progressAnimator.apply { setIntValues(binding.syncingProgress.progress, state.syncProgress.progress) }.start()
                binding.syncingProgress.isIndeterminate = state.syncProgress.indeterminate
            }

            is SyncRepository.SyncProgress.ParsingEmojis -> {
                binding.syncingProgress.isVisible = true
                binding.syncingProgress.max = state.syncProgress.max
                progressAnimator.apply { setIntValues(binding.syncingProgress.progress, state.syncProgress.progress) }.start()
                binding.syncingProgress.isIndeterminate = state.syncProgress.indeterminate
            }
        }
    }

    override fun showSignatureDialog(signature: String) = signatureDialog.setText(signature).show()

    override fun showMmsSizePicker() = mmsSizeDialog.show(activity!!)

    override fun showMessageLinkHandlingDialogPicker() = messageLinkHandlingDialog.show(activity!!)

    override fun showSwipeActions() {
        router.pushController(RouterTransaction.with(SwipeActionsController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
