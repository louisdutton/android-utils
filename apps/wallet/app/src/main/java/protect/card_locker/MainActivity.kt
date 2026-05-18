package protect.card_locker

import android.content.DialogInterface
import android.content.Intent
import android.database.CursorIndexOutOfBoundsException
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import protect.card_locker.DBHelper.LoyaltyCardOrder
import protect.card_locker.DBHelper.LoyaltyCardOrderDirection
import protect.card_locker.LoyaltyCardCursorAdapter.CardAdapterListener
import protect.card_locker.cardview.LoyaltyCardViewActivity
import protect.card_locker.databinding.SortingOptionBinding
import protect.card_locker.compose.theme.CatimaTheme
import protect.card_locker.preferences.Settings
import protect.card_locker.preferences.SettingsActivity
import java.io.UnsupportedEncodingException
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.content.edit

class MainActivity : CatimaAppCompatActivity(), CardAdapterListener {
    private lateinit var mDatabase: SQLiteDatabase
    private lateinit var mAdapter: LoyaltyCardCursorAdapter
    private var mCurrentActionMode: ActionMode? = null
    private var recyclerView: RecyclerView? = null
    private var mLoyaltyCardCount = 0
    @JvmField
    var mFilter: String = ""
    private var currentQuery = ""
    private var finalQuery = ""
    private var mGroup: Any? = null
    private var mOrder: LoyaltyCardOrder = LoyaltyCardOrder.Alpha
    private var mOrderDirection: LoyaltyCardOrderDirection = LoyaltyCardOrderDirection.Ascending
    private var selectedTab: Int = 0
    private lateinit var mUpdateLoyaltyCardListRunnable: Runnable
    private lateinit var mBarcodeScannerLauncher: ActivityResultLauncher<Intent>
    private lateinit var mSettingsLauncher: ActivityResultLauncher<Intent>
    private var uiState by mutableStateOf(WalletMainUiState())
    private var searchExpanded by mutableStateOf(false)
    private var overflowExpanded by mutableStateOf(false)

    private val mCurrentActionModeCallback: ActionMode.Callback = object : ActionMode.Callback {
        override fun onCreateActionMode(inputMode: ActionMode, inputMenu: Menu?): Boolean {
            inputMode.menuInflater.inflate(R.menu.card_longclick_menu, inputMenu)
            return true
        }

        override fun onPrepareActionMode(inputMode: ActionMode?, inputMenu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(inputMode: ActionMode, inputItem: MenuItem): Boolean {
            when (inputItem.itemId) {
                R.id.action_share -> {
                    try {
                        ImportURIHelper(this@MainActivity).startShareIntent(mAdapter.getSelectedItems())
                    } catch (e: UnsupportedEncodingException) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.failedGeneratingShareURL,
                            Toast.LENGTH_LONG
                        ).show()
                        e.printStackTrace()
                    }
                    inputMode.finish()
                    return true
                }
                R.id.action_edit -> {
                    require(mAdapter.selectedItemCount == 1) { "Cannot edit more than 1 card at a time" }

                    startActivity(
                        Intent(applicationContext, LoyaltyCardEditActivity::class.java).apply {
                            putExtras(Bundle().apply {
                                putInt(
                                    LoyaltyCardEditActivity.BUNDLE_ID,
                                    mAdapter.getSelectedItems()[0].id
                                )
                                putBoolean(LoyaltyCardEditActivity.BUNDLE_UPDATE, true)
                            })
                        }
                    )

                    inputMode.finish()
                    return true
                }
                R.id.action_duplicate -> {
                    require(mAdapter.selectedItemCount == 1) { "Cannot duplicate more than 1 card at a time" }

                    startActivity(
                        Intent(applicationContext, LoyaltyCardEditActivity::class.java).apply {
                            putExtras(Bundle().apply {
                                putInt(
                                    LoyaltyCardEditActivity.BUNDLE_ID,
                                    mAdapter.getSelectedItems()[0].id
                                )
                                putBoolean(LoyaltyCardEditActivity.BUNDLE_DUPLICATE_ID, true)
                            })
                        }
                    )

                    inputMode.finish()
                    return true
                }
                R.id.action_delete -> {
                    MaterialAlertDialogBuilder(this@MainActivity).apply {
                        // The following may seem weird, but it is necessary to give translators enough flexibility.
                        // For example, in Russian, Android's plural quantity "one" actually refers to "any number ending on 1 but not ending in 11".
                        // So while in English the extra non-plural form seems unnecessary duplication, it is necessary to give translators enough flexibility.
                        // In here, we use the plain string when meaning exactly 1, and otherwise use the plural forms
                        if (mAdapter.selectedItemCount == 1) {
                            setTitle(R.string.deleteTitle)
                            setMessage(R.string.deleteConfirmation)
                        } else {
                            setTitle(
                                getResources().getQuantityString(
                                    R.plurals.deleteCardsTitle,
                                    mAdapter.selectedItemCount,
                                    mAdapter.selectedItemCount
                                )
                            )
                            setMessage(
                                getResources().getQuantityString(
                                    R.plurals.deleteCardsConfirmation,
                                    mAdapter.selectedItemCount,
                                    mAdapter.selectedItemCount
                                )
                            )
                        }

                        setPositiveButton(
                            R.string.confirm
                        ) { dialog, _ ->
                            for (loyaltyCard in mAdapter.getSelectedItems()) {
                                Log.d(TAG, "Deleting card: " + loyaltyCard.id)

                                DBHelper.deleteLoyaltyCard(mDatabase, this@MainActivity, loyaltyCard.id)
                            }
                            mGroup = groupForSelectedTab(selectedTab)

                            updateLoyaltyCardList(true)
                            dialog.dismiss()
                        }

                        setNegativeButton(R.string.cancel) { dialog, _ ->
                            dialog.dismiss()
                        }
                    }.create().show()

                    return true
                }
                R.id.action_archive -> {
                    for (loyaltyCard in mAdapter.getSelectedItems()) {
                        Log.d(TAG, "Archiving card: " + loyaltyCard.id)
                        DBHelper.updateLoyaltyCardArchiveStatus(mDatabase, loyaltyCard.id, 1)
                        updateLoyaltyCardList(false)
                        inputMode.finish()
                        invalidateOptionsMenu()
                    }
                    return true
                }
                R.id.action_unarchive -> {
                    for (loyaltyCard in mAdapter.getSelectedItems()) {
                        Log.d(TAG, "Unarchiving card: " + loyaltyCard.id)
                        DBHelper.updateLoyaltyCardArchiveStatus(mDatabase, loyaltyCard.id, 0)
                        updateLoyaltyCardList(false)
                        inputMode.finish()
                        invalidateOptionsMenu()
                    }
                    return true
                }
                R.id.action_star -> {
                    for (loyaltyCard in mAdapter.getSelectedItems()) {
                        Log.d(TAG, "Starring card: " + loyaltyCard.id)
                        DBHelper.updateLoyaltyCardStarStatus(mDatabase, loyaltyCard.id, 1)
                        updateLoyaltyCardList(false)
                        inputMode.finish()
                    }
                    return true
                }
                R.id.action_unstar -> {
                    for (loyaltyCard in mAdapter.getSelectedItems()) {
                        Log.d(TAG, "Unstarring card: " + loyaltyCard.id)
                        DBHelper.updateLoyaltyCardStarStatus(mDatabase, loyaltyCard.id, 0)
                        updateLoyaltyCardList(false)
                        inputMode.finish()
                    }
                    return true
                }
            }

            return false
        }

        override fun onDestroyActionMode(inputMode: ActionMode?) {
            mAdapter.clearSelections()
            mCurrentActionMode = null
        }
    }

    override fun onCreate(inputSavedInstanceState: Bundle?) {
        super.onCreate(inputSavedInstanceState)

        // Delete old cache files
        // These could be temporary images for the cropper, temporary images in LoyaltyCard toBundle/writeParcel/ etc.
        Thread {
            val twentyFourHoursAgo = System.currentTimeMillis() - (1000 * 60 * 60 * 24)
            val tempFiles = cacheDir.listFiles()

            if (tempFiles == null) {
                Log.e(
                    TAG,
                    "getCacheDir().listFiles() somehow returned null, this should never happen... Skipping cache cleanup..."
                )
                return@Thread
            }
            for (file in tempFiles) {
                if (file.lastModified() < twentyFourHoursAgo) {
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete cache file " + file.path)
                    }
                }
            }
        }.start()

        // We should extract the share intent after we called the super.onCreate as it may need to spawn a dialog window and the app needs to be initialized to not crash
        extractIntentFields(intent)

        mDatabase = DBHelper(this).writableDatabase

        mUpdateLoyaltyCardListRunnable = Runnable {
            updateLoyaltyCardList(false)
        }

        mAdapter = LoyaltyCardCursorAdapter(this, null, this, mUpdateLoyaltyCardListRunnable)

        mBarcodeScannerLauncher = registerForActivityResult(
            StartActivityForResult(),
            ActivityResultCallback registerForActivityResult@{ result: ActivityResult? ->
                // Exit early if the user cancelled the scan (pressed back/home)
                if (result == null || result.resultCode != RESULT_OK) {
                    return@registerForActivityResult
                }

                startActivity(
                    Intent(applicationContext, LoyaltyCardEditActivity::class.java).apply {
                        putExtras(result.data!!.extras!!)
                    }
                )
            })

        mSettingsLauncher = registerForActivityResult(
            StartActivityForResult()
        ) { result: ActivityResult? ->
            if (result?.resultCode == RESULT_OK) {
                val intent = result.data
                if (intent != null && intent.getBooleanExtra(RESTART_ACTIVITY_INTENT, false)) {
                    recreate()
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchExpanded) {
                    searchExpanded = false
                } else {
                    finish()
                }
            }
        })

        setContent {
            CatimaTheme {
                WalletMainScreen(
                    state = uiState,
                    searchExpanded = searchExpanded,
                    searchQuery = currentQuery,
                    overflowExpanded = overflowExpanded,
                    adapter = mAdapter,
                    onRecyclerReady = { recycler ->
                        recyclerView = recycler
                        registerForContextMenu(recycler)
                    },
                    onSearchExpandedChange = { expanded -> searchExpanded = expanded },
                    onSearchQueryChange = ::setSearchQuery,
                    onOverflowExpandedChange = { expanded -> overflowExpanded = expanded },
                    onTabSelected = ::selectGroupTab,
                    onAddCard = ::startAddCard,
                    onDisplayOptions = {
                        mAdapter.showDisplayOptionsDialog()
                        updateLoyaltyCardList(false)
                    },
                    onSort = ::showSortDialog,
                    onManageGroups = { startActivity(Intent(applicationContext, ManageGroupsActivity::class.java)) },
                    onImportExport = { startActivity(Intent(applicationContext, ImportExportActivity::class.java)) },
                    onSettings = {
                        mSettingsLauncher.launch(Intent(applicationContext, SettingsActivity::class.java))
                    },
                    onAbout = { startActivity(Intent(applicationContext, AboutActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (mCurrentActionMode != null) {
            mAdapter.clearSelections()
            mCurrentActionMode!!.finish()
        }

        // Start of active tab logic
        updateGroupTabs()

        // Restore selected tab from Shared Preference
        selectedTab = applicationContext.getSharedPreferences(
            getString(R.string.sharedpreference_active_tab),
            MODE_PRIVATE
        ).getInt(getString(R.string.sharedpreference_active_tab), 0)

        // Restore sort preferences from Shared Preferences
        mOrder = Utils.getLoyaltyCardOrder(this)
        mOrderDirection = Utils.getLoyaltyCardOrderDirection(this)

        mGroup = null

        selectedTab = selectedTab.coerceIn(0, uiState.groups.size)
        mGroup = groupForSelectedTab(selectedTab)

        updateLoyaltyCardList(true)

        // End of active tab logic

        // Apply column count setting to card list
        val layoutManager = recyclerView?.layoutManager as GridLayoutManager?
        if (layoutManager != null) {
            val settings = Settings(this)
            layoutManager.setSpanCount(settings.getPreferredColumnCount())
        }
    }

    private fun updateLoyaltyCardCount() {
        mLoyaltyCardCount = DBHelper.getLoyaltyCardCount(mDatabase)
    }

    private fun updateLoyaltyCardList(updateCount: Boolean) {
        var group: Group? = null
        if (mGroup != null) {
            group = mGroup as Group
        }

        mAdapter.swapCursor(
            DBHelper.getLoyaltyCardCursor(
                mDatabase,
                mFilter,
                group,
                mOrder,
                mOrderDirection,
                if (mAdapter.showingArchivedCards()) DBHelper.LoyaltyCardArchiveFilter.All else DBHelper.LoyaltyCardArchiveFilter.Unarchived
            )
        )

        if (updateCount) {
            updateLoyaltyCardCount()
            // Update menu icons if necessary
            invalidateOptionsMenu()
        }

        val contentState = when {
            mLoyaltyCardCount == 0 -> WalletContentState.Empty
            mAdapter.itemCount > 0 -> WalletContentState.Cards
            mFilter.isNotEmpty() -> WalletContentState.NoSearchResults
            else -> WalletContentState.EmptyGroup
        }
        uiState = uiState.copy(
            selectedTab = selectedTab,
            cardCount = mLoyaltyCardCount,
            itemCount = mAdapter.itemCount,
            contentState = contentState,
            canShowCardActions = mLoyaltyCardCount > 0,
        )

        if (mCurrentActionMode != null) {
            mCurrentActionMode!!.finish()
        }

        ListWidget().updateAll(mAdapter.mContext)
        ShortcutHelper.updateShortcuts(mAdapter.mContext)
    }

    private fun processParseResultList(
        parseResultList: MutableList<ParseResult?>,
        group: String?,
        closeAppOnNoBarcode: Boolean
    ) {
        require(!parseResultList.isEmpty()) { "parseResultList may not be empty" }

        Utils.makeUserChooseParseResultFromList(
            this@MainActivity,
            parseResultList,
            object : ParseResultListDisambiguatorCallback {
                override fun onUserChoseParseResult(parseResult: ParseResult) {
                    val intent =
                        Intent(applicationContext, LoyaltyCardEditActivity::class.java)
                    val bundle = parseResult.toLoyaltyCardBundle(this@MainActivity)
                    if (group != null) {
                        bundle.putString(LoyaltyCardEditActivity.BUNDLE_ADDGROUP, group)
                    }
                    intent.putExtras(bundle)
                    startActivity(intent)
                }

                override fun onUserDismissedSelector() {
                    if (closeAppOnNoBarcode) {
                        finish()
                    }
                }
            })
    }

    private fun onSharedIntent(intent: Intent) {
        val receivedAction = intent.action
        val receivedType = intent.type

        if (receivedAction == null || receivedType == null) {
            return
        }

        val parseResultList: MutableList<ParseResult?>?

        // Check for shared text
        if (receivedAction == Intent.ACTION_SEND && receivedType == "text/plain") {
            val loyaltyCard = LoyaltyCard()
            loyaltyCard.setCardId(intent.getStringExtra(Intent.EXTRA_TEXT)!!)
            parseResultList = mutableListOf(ParseResult(ParseResultType.BARCODE_ONLY, loyaltyCard))
        } else {
            // Parse whatever file was sent, regardless of opening or sharing
            val data: Uri? = when (receivedAction) {
                Intent.ACTION_VIEW -> {
                    intent.data
                }
                Intent.ACTION_SEND -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                }
                else -> {
                    Log.e(TAG, "Wrong action type to parse intent")
                    return
                }
            }

            if (receivedType.startsWith("image/")) {
                parseResultList = Utils.retrieveBarcodesFromImage(this, data)
            } else if (receivedType == "application/pdf") {
                parseResultList = Utils.retrieveBarcodesFromPdf(this, data)
            } else if (mutableListOf<String?>(
                    "application/vnd.apple.pkpass",
                    "application/vnd-com.apple.pkpass"
                ).contains(receivedType)
            ) {
                parseResultList = Utils.retrieveBarcodesFromPkPass(this, data)
            } else if (receivedType == "application/vnd.espass-espass") {
                // FIXME: espass is not pkpass
                // However, several users stated in https://github.com/CatimaLoyalty/Android/issues/2197 that the formats are extremely similar to the point they could rename an .espass file to .pkpass and have it imported
                // So it makes sense to "unofficially" treat it as a PKPASS for now, even though not completely correct
                parseResultList = Utils.retrieveBarcodesFromPkPass(this, data)
            } else if (receivedType == "application/vnd.apple.pkpasses") {
                parseResultList = Utils.retrieveBarcodesFromPkPasses(this, data)
            } else {
                Log.e(TAG, "Wrong mime-type")
                return
            }
        }

        // Give up if we should parse but there is nothing to parse
        if (parseResultList == null || parseResultList.isEmpty()) {
            finish()
            return
        }

        processParseResultList(parseResultList, null, true)
    }

    private fun extractIntentFields(intent: Intent) {
        onSharedIntent(intent)
    }

    private fun updateGroupTabs() {
        val newGroups = DBHelper.getGroups(mDatabase)
        selectedTab = selectedTab.coerceIn(0, newGroups.size)
        uiState = uiState.copy(groups = newGroups, selectedTab = selectedTab)
    }

    private fun selectGroupTab(tab: Int) {
        selectedTab = tab.coerceIn(0, uiState.groups.size)
        mGroup = groupForSelectedTab(selectedTab)
        updateLoyaltyCardList(false)
        applicationContext.getSharedPreferences(
            getString(R.string.sharedpreference_active_tab),
            MODE_PRIVATE
        ).edit {
            putInt(
                getString(R.string.sharedpreference_active_tab),
                selectedTab
            )
        }
    }

    private fun groupForSelectedTab(tab: Int): Group? {
        return if (tab == 0) null else uiState.groups.getOrNull(tab - 1)
    }

    private fun selectedGroupName(): String? {
        return groupForSelectedTab(selectedTab)?._id
    }

    private fun setSearchQuery(query: String) {
        currentQuery = query
        mFilter = query
        mGroup = groupForSelectedTab(selectedTab)
        updateLoyaltyCardList(false)
    }

    private fun startAddCard() {
        mBarcodeScannerLauncher.launch(
            Intent(applicationContext, ScanActivity::class.java).apply {
                putExtras(Bundle().apply {
                    selectedGroupName()?.let { groupName ->
                        putString(LoyaltyCardEditActivity.BUNDLE_ADDGROUP, groupName)
                    }
                })
            }
        )
    }

    // Saving currentQuery to finalQuery for user, this will be used to restore search history, happens when user clicks a card from list
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        finalQuery = currentQuery
        outState.putString(STATE_SEARCH_QUERY, finalQuery)
        outState.putBoolean(STATE_SEARCH_EXPANDED, searchExpanded)
    }

    // Restoring instance state when rotation of screen happens with the goal to restore search query for user
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "")
        finalQuery = currentQuery
        mFilter = currentQuery
        searchExpanded = savedInstanceState.getBoolean(STATE_SEARCH_EXPANDED, currentQuery.isNotEmpty())
    }

    override fun onOptionsItemSelected(inputItem: MenuItem): Boolean {
        when (inputItem.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }

        return super.onOptionsItemSelected(inputItem)
    }

    private fun showSortDialog() {
        val currentIndex = AtomicInteger()
        val loyaltyCardOrders = listOf<LoyaltyCardOrder?>(*LoyaltyCardOrder.entries.toTypedArray())
        for (i in loyaltyCardOrders.indices) {
            if (mOrder == loyaltyCardOrders[i]) {
                currentIndex.set(i)
                break
            }
        }

        MaterialAlertDialogBuilder(this@MainActivity).apply {
            setTitle(R.string.sort_by)

            val sortingOptionBinding = SortingOptionBinding.inflate(LayoutInflater.from(this@MainActivity), null, false)
            setView(sortingOptionBinding.getRoot())

            val showReversed = sortingOptionBinding.checkBoxReverse

            showReversed.isChecked = mOrderDirection == LoyaltyCardOrderDirection.Descending

            setSingleChoiceItems(
                R.array.sort_types_array,
                currentIndex.get()
            ) { _: DialogInterface?, which: Int ->
                currentIndex.set(which)
            }

            setPositiveButton(
                R.string.sort
            ) { dialog, _ ->
                setSort(
                    loyaltyCardOrders[currentIndex.get()]!!,
                    if (showReversed.isChecked) LoyaltyCardOrderDirection.Descending else LoyaltyCardOrderDirection.Ascending
                )
                ListWidget().updateAll(this@MainActivity)
                dialog?.dismiss()
            }

            setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
        }.create().show()
    }

    private fun setSort(order: LoyaltyCardOrder, direction: LoyaltyCardOrderDirection) {
        // Update values
        mOrder = order
        mOrderDirection = direction

        // Store in Shared Preference to restore next app launch
        applicationContext.getSharedPreferences(
            getString(R.string.sharedpreference_sort),
            MODE_PRIVATE
        ).edit {
            putString(
                getString(R.string.sharedpreference_sort_order),
                order.name
            )
            putString(
                getString(R.string.sharedpreference_sort_direction),
                direction.name
            )
        }

        // Update card list
        updateLoyaltyCardList(false)
    }

    override fun onRowLongClicked(inputPosition: Int) {
        enableActionMode(inputPosition)
    }

    private fun enableActionMode(inputPosition: Int) {
        if (mCurrentActionMode == null) {
            mCurrentActionMode = startSupportActionMode(mCurrentActionModeCallback)
        }
        toggleSelection(inputPosition)
    }

    private fun toggleSelection(inputPosition: Int) {
        mAdapter.toggleSelection(inputPosition)
        val count = mAdapter.selectedItemCount

        if (count == 0) {
            mCurrentActionMode!!.finish()
        } else {
            mCurrentActionMode!!.title = getResources().getQuantityString(
                R.plurals.selectedCardCount,
                count,
                count
            )

            val editItem = mCurrentActionMode!!.menu.findItem(R.id.action_edit)
            val duplicateItem = mCurrentActionMode!!.menu.findItem(R.id.action_duplicate)
            val archiveItem = mCurrentActionMode!!.menu.findItem(R.id.action_archive)
            val unarchiveItem = mCurrentActionMode!!.menu.findItem(R.id.action_unarchive)
            val starItem = mCurrentActionMode!!.menu.findItem(R.id.action_star)
            val unstarItem = mCurrentActionMode!!.menu.findItem(R.id.action_unstar)

            var hasStarred = false
            var hasUnstarred = false
            var hasArchived = false
            var hasUnarchived = false

            for (loyaltyCard in mAdapter.getSelectedItems()) {
                if (loyaltyCard.starStatus == 1) {
                    hasStarred = true
                } else {
                    hasUnstarred = true
                }

                if (loyaltyCard.archiveStatus == 1) {
                    hasArchived = true
                } else {
                    hasUnarchived = true
                }

                // We have all types, no need to keep checking
                if (hasStarred && hasUnstarred && hasArchived && hasUnarchived) {
                    break
                }
            }

            unarchiveItem.isVisible = hasArchived
            archiveItem.isVisible = hasUnarchived

            if (count == 1) {
                starItem.isVisible = !hasStarred
                unstarItem.isVisible = !hasUnstarred
                editItem.isVisible = true
                editItem.isEnabled = true
                duplicateItem.isVisible = true
                duplicateItem.isEnabled = true
            } else {
                starItem.isVisible = hasUnstarred
                unstarItem.isVisible = hasStarred

                editItem.isVisible = false
                editItem.isEnabled = false
                duplicateItem.isVisible = false
                duplicateItem.isEnabled = false
            }

            mCurrentActionMode!!.invalidate()
        }
    }


    override fun onRowClicked(inputPosition: Int) {
        if (mAdapter.selectedItemCount > 0) {
            enableActionMode(inputPosition)
        } else {
            // FIXME
            //
            // There is a really nasty edge case that can happen when someone taps a card but right
            // after it swipes (very small window, hard to reproduce). The cursor gets replaced and
            // may not have a card at the ID number that is returned from onRowClicked.
            //
            // The proper fix, obviously, would involve makes sure an onFling can't happen while a
            // click is being processed. Sadly, I have not yet found a way to make that possible.
            val loyaltyCard: LoyaltyCard
            try {
                loyaltyCard = mAdapter.getCard(inputPosition)
            } catch (e: CursorIndexOutOfBoundsException) {
                Log.w(TAG, "Prevented crash from tap + swipe on ID $inputPosition: $e")
                return
            }

            startActivity(
                Intent(this, LoyaltyCardViewActivity::class.java).apply {
                    action = ""
                    putExtras(Bundle().apply {
                        putInt(LoyaltyCardViewActivity.BUNDLE_ID, loyaltyCard.id)

                        val cardList = ArrayList<Int?>()
                        for (i in 0..<mAdapter.itemCount) {
                            cardList.add(mAdapter.getCard(i).id)
                        }

                        putIntegerArrayList(LoyaltyCardViewActivity.BUNDLE_CARDLIST, cardList)
                    })
                }
            )
        }
    }

    companion object {
        private const val TAG = "Catima"
        const val RESTART_ACTIVITY_INTENT: String = "restart_activity_intent"

        const val STATE_SEARCH_QUERY: String = "SEARCH_QUERY"
        const val STATE_SEARCH_EXPANDED: String = "SEARCH_EXPANDED"
    }
}

private enum class WalletContentState {
    Cards,
    Empty,
    NoSearchResults,
    EmptyGroup,
}

private data class WalletMainUiState(
    val groups: List<Group> = emptyList(),
    val selectedTab: Int = 0,
    val cardCount: Int = 0,
    val itemCount: Int = 0,
    val contentState: WalletContentState = WalletContentState.Empty,
    val canShowCardActions: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletMainScreen(
    state: WalletMainUiState,
    searchExpanded: Boolean,
    searchQuery: String,
    overflowExpanded: Boolean,
    adapter: LoyaltyCardCursorAdapter,
    onRecyclerReady: (RecyclerView) -> Unit,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOverflowExpandedChange: (Boolean) -> Unit,
    onTabSelected: (Int) -> Unit,
    onAddCard: () -> Unit,
    onDisplayOptions: () -> Unit,
    onSort: () -> Unit,
    onManageGroups: () -> Unit,
    onImportExport: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            topBar = {
                WalletTopAppBar(
                    searchExpanded = searchExpanded,
                    searchQuery = searchQuery,
                    canShowCardActions = state.canShowCardActions,
                    overflowExpanded = overflowExpanded,
                    onSearchExpandedChange = onSearchExpandedChange,
                    onSearchQueryChange = onSearchQueryChange,
                    onOverflowExpandedChange = onOverflowExpandedChange,
                    onDisplayOptions = onDisplayOptions,
                    onSort = onSort,
                    onManageGroups = onManageGroups,
                    onImportExport = onImportExport,
                    onSettings = onSettings,
                    onAbout = onAbout,
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onAddCard) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.action_add),
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
                if (state.groups.isNotEmpty()) {
                    WalletGroupTabs(
                        groups = state.groups,
                        selectedTab = state.selectedTab,
                        onTabSelected = onTabSelected,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (state.contentState == WalletContentState.Cards) {
                        WalletCardList(
                            adapter = adapter,
                            onRecyclerReady = onRecyclerReady,
                        )
                    } else {
                        WalletEmptyState(state.contentState)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletTopAppBar(
    searchExpanded: Boolean,
    searchQuery: String,
    canShowCardActions: Boolean,
    overflowExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOverflowExpandedChange: (Boolean) -> Unit,
    onDisplayOptions: () -> Unit,
    onSort: () -> Unit,
    onManageGroups: () -> Unit,
    onImportExport: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        title = {
            if (searchExpanded) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.action_search)) },
                )
            } else {
                Text(stringResource(R.string.app_name))
            }
        },
        actions = {
            if (searchExpanded) {
                IconButton(
                    onClick = {
                        onSearchQueryChange("")
                        onSearchExpandedChange(false)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cancel),
                    )
                }
            } else {
                if (canShowCardActions) {
                    WalletIconButton(
                        icon = Icons.Rounded.Search,
                        label = stringResource(R.string.action_search),
                        onClick = { onSearchExpandedChange(true) },
                    )
                    WalletIconButton(
                        icon = Icons.Rounded.Visibility,
                        label = stringResource(R.string.action_display_options),
                        onClick = onDisplayOptions,
                    )
                    WalletIconButton(
                        icon = Icons.AutoMirrored.Rounded.Sort,
                        label = stringResource(R.string.sort),
                        onClick = onSort,
                    )
                }

                Box {
                    IconButton(onClick = { onOverflowExpandedChange(true) }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.action_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { onOverflowExpandedChange(false) },
                    ) {
                        WalletDropdownItem(
                            text = stringResource(R.string.groups),
                            icon = Icons.Rounded.AccountBalanceWallet,
                            onClick = {
                                onOverflowExpandedChange(false)
                                onManageGroups()
                            },
                        )
                        WalletDropdownItem(
                            text = stringResource(R.string.importExport),
                            icon = Icons.Rounded.ImportExport,
                            onClick = {
                                onOverflowExpandedChange(false)
                                onImportExport()
                            },
                        )
                        WalletDropdownItem(
                            text = stringResource(R.string.settings),
                            icon = Icons.Rounded.Settings,
                            onClick = {
                                onOverflowExpandedChange(false)
                                onSettings()
                            },
                        )
                        WalletDropdownItem(
                            text = stringResource(R.string.about),
                            icon = Icons.Rounded.MoreVert,
                            onClick = {
                                onOverflowExpandedChange(false)
                                onAbout()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun WalletIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
        )
    }
}

@Composable
private fun WalletDropdownItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun WalletGroupTabs(
    groups: List<Group>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedTab.coerceIn(0, groups.size),
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            text = { Text(stringResource(R.string.all)) },
        )
        groups.forEachIndexed { index, group ->
            val tabIndex = index + 1
            Tab(
                selected = selectedTab == tabIndex,
                onClick = { onTabSelected(tabIndex) },
                text = { Text(group._id) },
            )
        }
    }
}

@Composable
private fun WalletCardList(
    adapter: LoyaltyCardCursorAdapter,
    onRecyclerReady: (RecyclerView) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            RecyclerView(context).apply {
                id = R.id.list
                clipToPadding = false
                val bottomPadding = (96 * resources.displayMetrics.density).toInt()
                setPadding(0, 0, 0, bottomPadding)
                scrollBarStyle = RecyclerView.SCROLLBARS_INSIDE_INSET
                layoutManager = GridLayoutManager(context, Settings(context).getPreferredColumnCount())
                this.adapter = adapter
                onRecyclerReady(this)
            }
        },
        update = { recycler ->
            if (recycler.adapter !== adapter) {
                recycler.adapter = adapter
            }
            val layoutManager = recycler.layoutManager as? GridLayoutManager
            layoutManager?.spanCount = Settings(recycler.context).getPreferredColumnCount()
        },
    )
}

@Composable
private fun WalletEmptyState(contentState: WalletContentState) {
    val message = when (contentState) {
        WalletContentState.NoSearchResults -> stringResource(R.string.noMatchingGiftCards)
        WalletContentState.EmptyGroup -> stringResource(R.string.noGroupCards)
        else -> stringResource(R.string.noGiftCards)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(horizontal = 32.dp, vertical = 48.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            modifier = Modifier
                .size(64.dp)
                .padding(bottom = 16.dp),
            imageVector = Icons.Rounded.AccountBalanceWallet,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
