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
package dev.octoshrimpy.quik.feature.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProviders
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDispose
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.ViewModelFactory
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.feature.blocking.BlockingDialog
import dev.octoshrimpy.quik.feature.changelog.ChangelogDialog
import dev.octoshrimpy.quik.manager.ChangelogManager
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import io.realm.RealmResults
import javax.inject.Inject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : QkThemedActivity(), MainView {

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var viewModelFactory: ViewModelFactory
    @Inject lateinit var dateFormatter: DateFormatter

    private val onNewIntentSubject: Subject<Intent> = PublishSubject.create()
    private val activityResumedSubject: Subject<Boolean> = PublishSubject.create()
    private val queryChangedSubject: Subject<CharSequence> = PublishSubject.create()
    private val composeSubject: Subject<Unit> = PublishSubject.create()
    private val drawerToggledSubject: Subject<Boolean> = PublishSubject.create()
    private val homeSubject: Subject<Unit> = PublishSubject.create()
    private val navigationSubject: Subject<NavItem> = PublishSubject.create()
    private val optionsItemSubject: Subject<Int> = PublishSubject.create()
    private val dismissRatingSubject: Subject<Unit> = PublishSubject.create()
    private val rateSubject: Subject<Unit> = PublishSubject.create()
    private val conversationsSelectedSubject: BehaviorSubject<List<Long>> =
        BehaviorSubject.createDefault(emptyList())
    private val confirmDeleteSubject: Subject<List<Long>> = PublishSubject.create()
    private val renameConversationSubject: Subject<String> = PublishSubject.create()
    private val swipeConversationSubject: Subject<Pair<Long, Int>> = PublishSubject.create()
    private val changelogMoreSubject: Subject<Unit> = PublishSubject.create()
    private val undoArchiveSubject: Subject<Unit> = PublishSubject.create()
    private val snackbarButtonSubject: Subject<Unit> = PublishSubject.create()

    private val selectedConversationIds = mutableStateListOf<Long>()
    private var visibleConversationIds: List<Long> = emptyList()
    private var observedConversationData: RealmResults<Conversation>? = null
    private var observedConversationDisposable: Disposable? = null
    private var uiState by mutableStateOf(MainState())
    private var listVersion by mutableIntStateOf(0)
    private var searchQuery by mutableStateOf("")

    override val onNewIntentIntent: Observable<Intent> = onNewIntentSubject
    override val activityResumedIntent: Observable<Boolean> = activityResumedSubject
    override val queryChangedIntent: Observable<CharSequence> = queryChangedSubject
    override val composeIntent: Observable<Unit> = composeSubject
    override val drawerToggledIntent: Observable<Boolean> = drawerToggledSubject
    override val homeIntent: Observable<*> = homeSubject
    override val navigationIntent: Observable<NavItem> = navigationSubject
    override val optionsItemIntent: Observable<Int> = optionsItemSubject
    override val dismissRatingIntent: Observable<*> = dismissRatingSubject
    override val rateIntent: Observable<*> = rateSubject
    override val conversationsSelectedIntent: Observable<List<Long>> = conversationsSelectedSubject
    override val confirmDeleteIntent: Observable<List<Long>> = confirmDeleteSubject
    override val renameConversationIntent: Observable<String> = renameConversationSubject
    override val swipeConversationIntent: Observable<Pair<Long, Int>> = swipeConversationSubject
    override val changelogMoreIntent: Observable<*> = changelogMoreSubject
    override val undoArchiveIntent: Observable<Unit> = undoArchiveSubject
    override val snackbarButtonIntent: Observable<Unit> = snackbarButtonSubject

    private val viewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java]
    }
    private val changelogDialog by lazy { ChangelogDialog(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MessagesMainScreen(
                state = uiState,
                listVersion = listVersion,
                query = searchQuery,
                selectedConversationIds = selectedConversationIds.toSet(),
                dateFormatter = dateFormatter,
                onQueryChanged = { query ->
                    searchQuery = query
                    queryChangedSubject.onNext(query)
                },
                onCompose = { composeSubject.onNext(Unit) },
                onHome = { homeSubject.onNext(Unit) },
                onBack = { navigationSubject.onNext(NavItem.BACK) },
                onDrawerChanged = { drawerToggledSubject.onNext(it) },
                onNavigate = { navigationSubject.onNext(it) },
                onSnackbarAction = { snackbarButtonSubject.onNext(Unit) },
                onConversationClick = ::openOrToggleConversation,
                onConversationLongClick = ::toggleConversationSelection,
                onSearchResultClick = { result ->
                    navigator.showConversation(result.conversation.id, result.query)
                },
                onSelectAll = { optionsItemSubject.onNext(R.id.select_all) },
                onArchive = { optionsItemSubject.onNext(R.id.archive) },
                onUnarchive = { optionsItemSubject.onNext(R.id.unarchive) },
                onDelete = { optionsItemSubject.onNext(R.id.delete) },
                onAddContact = { optionsItemSubject.onNext(R.id.add) },
                onPin = { optionsItemSubject.onNext(R.id.pin) },
                onUnpin = { optionsItemSubject.onNext(R.id.unpin) },
                onRead = { optionsItemSubject.onNext(R.id.read) },
                onUnread = { optionsItemSubject.onNext(R.id.unread) },
                onBlock = { optionsItemSubject.onNext(R.id.block) },
                onRename = { optionsItemSubject.onNext(R.id.rename) },
            )
        }

        viewModel.bindView(this)
        onNewIntentSubject.onNext(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onNewIntentSubject.onNext(intent)
    }

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        uiState = state
        visibleConversationIds = when (val page = state.page) {
            is Inbox -> page.data?.map { it.id }.orEmpty()
            is Archived -> page.data?.map { it.id }.orEmpty()
            else -> emptyList()
        }
        observeConversationData(state.page)
    }

    override fun onResume() =
        super.onResume().also { activityResumedSubject.onNext(true) }

    override fun onPause() =
        super.onPause().also { activityResumedSubject.onNext(false) }

    override fun onDestroy() {
        observedConversationDisposable?.dispose()
        super.onDestroy()
        disposables.dispose()
    }

    override fun requestDefaultSms() =
        navigator.showDefaultSmsDialog(this)

    override fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
    }

    override fun clearSearch() {
        searchQuery = ""
        queryChangedSubject.onNext("")
    }

    override fun clearSelection() = updateSelection(emptyList())

    override fun toggleSelectAll() {
        val nextSelection = when (selectedConversationIds.size == visibleConversationIds.size) {
            true -> emptyList()
            false -> visibleConversationIds
        }
        updateSelection(nextSelection)
    }

    override fun themeChanged() {
        listVersion++
    }

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dialog_delete_message,
                    conversations.size,
                    conversations.size
                )
            )
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteSubject.onNext(conversations) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showRenameDialog(conversationName: String) =
        TextInputDialog(
            this,
            getString(R.string.info_name),
            renameConversationSubject::onNext
        )
            .setText(conversationName)
            .show()

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) =
        changelogDialog.show(changelog)

    override fun showArchivedSnackbar(countConversationsArchived: Int, isArchiving: Boolean) {
        val text = if (isArchiving) {
            resources.getQuantityString(
                R.plurals.toast_archived,
                countConversationsArchived,
                countConversationsArchived
            )
        } else {
            resources.getQuantityString(
                R.plurals.toast_unarchived,
                countConversationsArchived,
                countConversationsArchived
            )
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun drawerToggled(opened: Boolean) = Unit

    @Deprecated("The current app shell forwards system back into the MainViewModel.")
    override fun onBackPressed() {
        navigationSubject.onNext(NavItem.BACK)
    }

    private fun openOrToggleConversation(conversation: Conversation) {
        if (selectedConversationIds.isEmpty()) {
            navigator.showConversation(conversation.id)
        } else {
            toggleConversationSelection(conversation)
        }
    }

    private fun toggleConversationSelection(conversation: Conversation) {
        val nextSelection = selectedConversationIds.toMutableList()
        if (nextSelection.contains(conversation.id)) {
            nextSelection -= conversation.id
        } else {
            nextSelection += conversation.id
        }
        updateSelection(nextSelection)
    }

    private fun updateSelection(selection: List<Long>) {
        selectedConversationIds.clear()
        selectedConversationIds.addAll(selection)
        conversationsSelectedSubject.onNext(selection)
    }

    private fun observeConversationData(page: MainPage) {
        val data = when (page) {
            is Inbox -> page.data
            is Archived -> page.data
            else -> null
        }

        if (observedConversationData === data) return

        observedConversationDisposable?.dispose()
        observedConversationData = data
        observedConversationDisposable = data
            ?.asFlowable()
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.autoDispose(scope())
            ?.subscribe { listVersion++ }
    }
}
