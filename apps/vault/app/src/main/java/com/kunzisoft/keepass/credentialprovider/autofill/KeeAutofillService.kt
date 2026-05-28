/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.credentialprovider.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.app.database.CipherDatabaseAction
import com.kunzisoft.keepass.biometric.DeviceUnlockManager
import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.credentialprovider.activity.AutofillLauncherActivity
import com.kunzisoft.keepass.credentialprovider.autofill.StructureParser.Companion.APPLICATION_ID_POPUP_WINDOW
import com.kunzisoft.keepass.credentialprovider.magikeyboard.MagikeyboardService
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.DatabaseTaskProvider
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.ProgressMessage
import com.kunzisoft.keepass.database.element.DateInstant
import com.kunzisoft.keepass.database.element.Database.Companion.DEFAULT_PASSWORD_ENCODING
import com.kunzisoft.keepass.database.helper.SearchHelper
import com.kunzisoft.keepass.model.CipherEncryptDatabase
import com.kunzisoft.keepass.model.CredentialStorage
import com.kunzisoft.keepass.model.CreditCard
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.services.ClipboardEntryNotificationService
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService
import com.kunzisoft.keepass.settings.AutofillSettingsActivity
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.utils.AppUtil.randomRequestCode
import com.kunzisoft.keepass.vault.VaultFile
import org.joda.time.DateTime
import org.joda.time.Instant
import java.nio.ByteBuffer


@RequiresApi(api = Build.VERSION_CODES.O)
class KeeAutofillService : AutofillService() {

    private var mDatabaseTaskProvider: DatabaseTaskProvider? = null
    private var mDatabase: ContextualDatabase? = null
    private var applicationIdBlocklist: Set<String>? = null
    private var webDomainBlocklist: Set<String>? = null
    private var askToSaveData: Boolean = false
    private var autofillInlineSuggestionsEnabled: Boolean = false
    private var autofillSharedToMagikeyboard: Boolean = false
    private var switchToMagikeyboard: Boolean = false
    private var pendingAutofillRequest: PendingAutofillRequest? = null

    override fun onCreate() {
        super.onCreate()

        mDatabaseTaskProvider = DatabaseTaskProvider(this)
        mDatabaseTaskProvider?.actionTaskListener = autofillActionTaskListener
        mDatabaseTaskProvider?.onDatabaseRetrieved = { database ->
            this.mDatabase = database
            completePendingAutofillRequest(database)
        }
        mDatabaseTaskProvider?.registerProgressTask()

        getPreferences()
    }

    override fun onDestroy() {
        mDatabaseTaskProvider?.unregisterProgressTask()

        super.onDestroy()
    }

    private fun getPreferences() {
        applicationIdBlocklist = PreferencesUtil.applicationIdBlocklist(this)
        webDomainBlocklist = PreferencesUtil.webDomainBlocklist(this)
        askToSaveData = PreferencesUtil.askToSaveAutofillData(this)
        autofillInlineSuggestionsEnabled = PreferencesUtil.isAutofillInlineSuggestionsEnable(this)
        autofillSharedToMagikeyboard = PreferencesUtil.isAutofillSharedToMagikeyboardEnable(this)
        switchToMagikeyboard = PreferencesUtil.isAutoSwitchToMagikeyboardEnable(this)
    }

    private val autofillActionTaskListener =
        object : DatabaseTaskNotificationService.ActionTaskListener {
            override fun onActionStarted(
                database: ContextualDatabase,
                progressMessage: ProgressMessage
            ) {}

            override fun onActionUpdated(
                database: ContextualDatabase,
                progressMessage: ProgressMessage
            ) {}

            override fun onActionStopped(database: ContextualDatabase?) {}

            override fun onActionFinished(
                database: ContextualDatabase,
                actionTask: String,
                result: ActionRunnable.Result
            ) {
                if (actionTask == DatabaseTaskNotificationService.ACTION_DATABASE_LOAD_TASK
                    && pendingAutofillRequest != null) {
                    if (result.isSuccess && database.loaded) {
                        completePendingAutofillRequest(database)
                    } else {
                        fallbackPendingAutofillRequest()
                    }
                }
            }
        }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        cancellationSignal.setOnCancelListener { Log.w(TAG, "Cancel autofill.") }

        if (request.flags and FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST != 0) {
            Log.d(TAG, "Autofill requested in compatibility mode")
        } else {
            Log.d(TAG, "Autofill requested in native mode")
        }

        // Check user's settings for authenticating Responses and Datasets.
        val latestStructure = request.fillContexts.last().structure
        StructureParser(latestStructure).parse(saveValue = false)?.let { parseResult ->

            // Build the search info from the parser
            val searchInfo = SearchInfo().apply {
                applicationId = parseResult.applicationId
                webScheme = parseResult.webScheme
                webDomain = parseResult.webDomain
            }
            // Add the search info to the magikeyboard service
            MagikeyboardService.addSearchInfo(
                context = application,
                value = searchInfo,
                from = TypeMode.AUTOFILL
            )

            // Build search info only if applicationId or webDomain are not blocked
            if (autofillAllowedFor(
                    applicationId = parseResult.applicationId,
                    applicationIdBlocklist = applicationIdBlocklist,
                    webDomain = parseResult.webDomain,
                    webDomainBlocklist = webDomainBlocklist)
                ) {

                if (parseResult.isValid()) {
                    val inlineSuggestionsRequest =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && autofillInlineSuggestionsEnabled
                        ) {
                            CompatInlineSuggestionsRequest(request)
                        } else {
                            null
                        }
                    val autofillComponent = AutofillComponent(
                        latestStructure,
                        inlineSuggestionsRequest
                    )
                    resolveAutofillRequest(
                        database = mDatabase,
                        parseResult = parseResult,
                        searchInfo = searchInfo,
                        autofillComponent = autofillComponent,
                        callback = callback,
                        tryQuickUnlock = true
                    )
                }
            }
        }
    }

    private fun resolveAutofillRequest(
        database: ContextualDatabase?,
        parseResult: StructureParser.Result,
        searchInfo: SearchInfo,
        autofillComponent: AutofillComponent,
        callback: FillCallback,
        tryQuickUnlock: Boolean
    ) {
        SearchHelper.checkAutoSearchInfo(
            context = this,
            database = database,
            searchInfo = searchInfo,
            onItemsFound = { openedDatabase, items ->
                sendAutofillResponseForItems(
                    openedDatabase = openedDatabase,
                    items = items,
                    parseResult = parseResult,
                    autofillComponent = autofillComponent,
                    callback = callback
                )
            },
            onItemNotFound = { openedDatabase ->
                showUIForEntrySelection(
                    parseResult, openedDatabase,
                    searchInfo, autofillComponent, callback
                )
            },
            onDatabaseClosed = {
                val quickUnlockStarted = tryQuickUnlock && startQuickUnlockForAutofill(
                    parseResult = parseResult,
                    searchInfo = searchInfo,
                    autofillComponent = autofillComponent,
                    callback = callback
                )
                if (!quickUnlockStarted) {
                    showUIForEntrySelection(
                        parseResult, null,
                        searchInfo, autofillComponent, callback
                    )
                }
            }
        )
    }

    private fun sendAutofillResponseForItems(
        openedDatabase: ContextualDatabase,
        items: List<EntryInfo>,
        parseResult: StructureParser.Result,
        autofillComponent: AutofillComponent,
        callback: FillCallback
    ) {
        // Add Autofill entries to Magic Keyboard #2024 #995
        if (autofillSharedToMagikeyboard) {
            MagikeyboardService.addEntries(
                context = this,
                entryList = items,
                autoSwitchKeyboard = switchToMagikeyboard,
                from = TypeMode.AUTOFILL
            )
        } else {
            // Add OTP to clipboard notification #1347
            ClipboardEntryNotificationService.launchOtpNotificationIfAllowed(
                context = this,
                entries = items
            )
        }
        callback.onSuccess(
            AutofillHelper.buildResponse(
                context = this,
                database = openedDatabase,
                entriesInfo = items,
                parseResult = parseResult,
                autofillComponent = autofillComponent
            )
        )
    }

    private fun startQuickUnlockForAutofill(
        parseResult: StructureParser.Result,
        searchInfo: SearchInfo,
        autofillComponent: AutofillComponent,
        callback: FillCallback
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || !PreferencesUtil.isVaultAvailableWhileDeviceUnlockedEnable(this)
            || !PreferencesUtil.isDeviceUnlockEnable(this)
            || !VaultFile.exists(this)) {
            return false
        }

        val databaseUri = VaultFile.uri(this)
        pendingAutofillRequest = PendingAutofillRequest(
            databaseUri = databaseUri,
            parseResult = parseResult,
            searchInfo = searchInfo,
            autofillComponent = autofillComponent,
            callback = callback
        )

        CipherDatabaseAction.getInstance(this).getCipherDatabase(databaseUri) { cipherDatabase ->
            val pendingRequest = pendingAutofillRequest
            if (pendingRequest == null || pendingRequest.databaseUri != databaseUri) {
                return@getCipherDatabase
            }
            if (cipherDatabase == null) {
                fallbackPendingAutofillRequest()
            } else {
                openVaultForPendingAutofill(databaseUri, cipherDatabase)
            }
        }
        return true
    }

    private fun openVaultForPendingAutofill(
        databaseUri: android.net.Uri,
        cipherDatabase: CipherEncryptDatabase
    ) {
        try {
            var decryptedCredential: ByteArray? = null
            val decrypted = DeviceUnlockManager(this).decryptDataIfDeviceUnlocked(
                encryptedValue = cipherDatabase.encryptedValue,
                ivSpecValue = cipherDatabase.specParameters
            ) { decryptedValue ->
                decryptedCredential = decryptedValue
            }
            val credentialValue = decryptedCredential
            if (!decrypted || credentialValue == null) {
                fallbackPendingAutofillRequest()
                return
            }

            val mainCredential = buildMainCredential(cipherDatabase, credentialValue)
            credentialValue.fill(0)
            if (mainCredential == null) {
                fallbackPendingAutofillRequest()
                return
            }

            mDatabaseTaskProvider?.startDatabaseLoad(
                databaseUri = databaseUri,
                mainCredential = mainCredential,
                readOnly = false,
                allowUserVerification = true,
                cipherEncryptDatabase = null,
                fixDuplicateUuid = false
            ) ?: fallbackPendingAutofillRequest()
        } catch (e: Exception) {
            Log.d(TAG, "Unable to quick-open vault for autofill", e)
            fallbackPendingAutofillRequest()
        }
    }

    private fun buildMainCredential(
        cipherDatabase: CipherEncryptDatabase,
        decryptedValue: ByteArray
    ): MainCredential? {
        return when (cipherDatabase.credentialStorage) {
            CredentialStorage.PASSWORD -> {
                val charBuffer = DEFAULT_PASSWORD_ENCODING.decode(ByteBuffer.wrap(decryptedValue))
                val password = CharArray(charBuffer.remaining())
                charBuffer.get(password)
                MainCredential(password = password)
            }
            CredentialStorage.KEY_FILE,
            CredentialStorage.HARDWARE_KEY -> null
        }
    }

    private fun completePendingAutofillRequest(database: ContextualDatabase?) {
        val pendingRequest = pendingAutofillRequest ?: return
        if (database?.loaded != true) {
            return
        }
        pendingAutofillRequest = null
        resolveAutofillRequest(
            database = database,
            parseResult = pendingRequest.parseResult,
            searchInfo = pendingRequest.searchInfo,
            autofillComponent = pendingRequest.autofillComponent,
            callback = pendingRequest.callback,
            tryQuickUnlock = false
        )
    }

    private fun fallbackPendingAutofillRequest() {
        val pendingRequest = pendingAutofillRequest ?: return
        pendingAutofillRequest = null
        showUIForEntrySelection(
            pendingRequest.parseResult,
            null,
            pendingRequest.searchInfo,
            pendingRequest.autofillComponent,
            pendingRequest.callback
        )
    }

    @SuppressLint("RestrictedApi")
    private fun showUIForEntrySelection(
        parseResult: StructureParser.Result,
        database: ContextualDatabase?,
        searchInfo: SearchInfo,
        autofillComponent: AutofillComponent,
        callback: FillCallback
    ) {
        var success = false
        parseResult.allAutofillIds().let { autofillIds ->
            if (autofillIds.isNotEmpty()) {
                // If the entire Autofill Response is authenticated, AuthActivity is used
                // to generate Response.
                AutofillLauncherActivity.getPendingIntentForSelection(
                    this,
                    searchInfo,
                    autofillComponent
                )?.intentSender?.let { intentSender ->
                    val responseBuilder = FillResponse.Builder()
                    val remoteViewsUnlock: RemoteViews = if (database == null) {
                        if (!parseResult.webDomain.isNullOrEmpty()) {
                            RemoteViews(
                                packageName,
                                R.layout.item_autofill_unlock_web_domain
                            ).apply {
                                setTextViewText(
                                    R.id.autofill_web_domain_text,
                                    parseResult.webDomain
                                )
                            }
                        } else if (!parseResult.applicationId.isNullOrEmpty()) {
                            RemoteViews(packageName, R.layout.item_autofill_unlock_app_id).apply {
                                setTextViewText(
                                    R.id.autofill_app_id_text,
                                    parseResult.applicationId
                                )
                            }
                        } else {
                            RemoteViews(packageName, R.layout.item_autofill_unlock)
                        }
                    } else {
                        if (!parseResult.webDomain.isNullOrEmpty()) {
                            RemoteViews(
                                packageName,
                                R.layout.item_autofill_select_entry_web_domain
                            ).apply {
                                setTextViewText(
                                    R.id.autofill_web_domain_text,
                                    parseResult.webDomain
                                )
                            }
                        } else if (!parseResult.applicationId.isNullOrEmpty()) {
                            RemoteViews(
                                packageName,
                                R.layout.item_autofill_select_entry_app_id
                            ).apply {
                                setTextViewText(
                                    R.id.autofill_app_id_text,
                                    parseResult.applicationId
                                )
                            }
                        } else {
                            RemoteViews(packageName, R.layout.item_autofill_select_entry)
                        }
                    }

                    // Tell the autofill framework the interest to save credentials
                    if (askToSaveData) {
                        var types: Int = SaveInfo.SAVE_DATA_TYPE_GENERIC
                        val requiredIds = mutableListOf<AutofillId>()
                        val optionalIds = mutableListOf<AutofillId>()

                        // Only if at least a password
                        parseResult.passwordId?.let { passwordInfo ->
                            parseResult.usernameId?.let { usernameInfo ->
                                types = types or SaveInfo.SAVE_DATA_TYPE_USERNAME
                                requiredIds.add(usernameInfo)
                            }
                            types = types or SaveInfo.SAVE_DATA_TYPE_PASSWORD
                            requiredIds.add(passwordInfo)
                        }
                        // or a credit card form
                        if (requiredIds.isEmpty()) {
                            parseResult.creditCardNumberId?.let { numberId ->
                                types = types or SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD
                                requiredIds.add(numberId)
                                Log.d(TAG, "Asking to save credit card number")
                            }
                            parseResult.creditCardExpirationDateId?.let { id -> optionalIds.add(id) }
                            parseResult.creditCardExpirationYearId?.let { id -> optionalIds.add(id) }
                            parseResult.creditCardExpirationMonthId?.let { id -> optionalIds.add(id) }
                            parseResult.creditCardHolderId?.let { id -> optionalIds.add(id) }
                            parseResult.cardVerificationValueId?.let { id -> optionalIds.add(id) }
                        }
                        if (requiredIds.isNotEmpty()) {
                            val builder = SaveInfo.Builder(types, requiredIds.toTypedArray())
                            if (optionalIds.isNotEmpty()) {
                                builder.setOptionalIds(optionalIds.toTypedArray())
                            }
                            responseBuilder.setSaveInfo(builder.build())
                        }
                    }

                    // Build inline presentation
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        && autofillInlineSuggestionsEnabled
                    ) {
                        var inlinePresentation: InlinePresentation? = null
                        autofillComponent.compatInlineSuggestionsRequest
                            ?.inlineSuggestionsRequest?.let { inlineSuggestionsRequest ->
                            val inlinePresentationSpecs =
                                inlineSuggestionsRequest.inlinePresentationSpecs
                            if (inlineSuggestionsRequest.maxSuggestionCount > 0
                                && inlinePresentationSpecs.isNotEmpty()
                            ) {
                                val inlinePresentationSpec = inlinePresentationSpecs[0]

                                // Make sure that the IME spec claims support for v1 UI template.
                                val imeStyle = inlinePresentationSpec.style
                                if (UiVersions.getVersions(imeStyle)
                                        .contains(UiVersions.INLINE_UI_VERSION_1)
                                ) {
                                    // Build the content for IME UI
                                    inlinePresentation = InlinePresentation(
                                        InlineSuggestionUi.newContentBuilder(
                                            PendingIntent.getActivity(
                                                this,
                                                randomRequestCode(),
                                                Intent(this, AutofillSettingsActivity::class.java),
                                                PendingIntent.FLAG_IMMUTABLE
                                            )
                                        ).apply {
                                            setContentDescription(getString(R.string.autofill_sign_in_prompt))
                                            setTitle(getString(R.string.autofill_sign_in_prompt))
                                            setStartIcon(
                                                Icon.createWithResource(
                                                    this@KeeAutofillService,
                                                    R.mipmap.ic_launcher_round
                                                ).apply {
                                                    setTintBlendMode(BlendMode.DST)
                                                })
                                        }.build().slice, inlinePresentationSpec, false
                                    )
                                }
                            }
                        }

                        // Build response
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                // Buggy method on some API 33 devices
                                responseBuilder.setAuthentication(
                                    autofillIds,
                                    intentSender,
                                    Presentations.Builder().apply {
                                        inlinePresentation?.let {
                                            setInlinePresentation(it)
                                        }
                                        setDialogPresentation(remoteViewsUnlock)
                                    }.build()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Unable to use the new setAuthentication method.", e)
                                @Suppress("DEPRECATION")
                                responseBuilder.setAuthentication(
                                    autofillIds,
                                    intentSender,
                                    remoteViewsUnlock,
                                    inlinePresentation
                                )
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            responseBuilder.setAuthentication(
                                autofillIds,
                                intentSender,
                                remoteViewsUnlock,
                                inlinePresentation
                            )
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        responseBuilder.setAuthentication(
                            autofillIds,
                            intentSender,
                            remoteViewsUnlock
                        )
                    }
                    success = true
                    callback.onSuccess(responseBuilder.build())
                }
            }
        }
        if (!success)
            callback.onFailure("Unable to get Autofill ids for UI selection")
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        var success = false
        if (askToSaveData && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val latestStructure = request.fillContexts.last().structure
            StructureParser(latestStructure).parse(saveValue = true)?.let { parseResult ->

                if (parseResult.isValid() && autofillAllowedFor(
                        applicationId = parseResult.applicationId,
                        applicationIdBlocklist = applicationIdBlocklist,
                        webDomain = parseResult.webDomain,
                        webDomainBlocklist = webDomainBlocklist)
                    ) {
                    Log.d(TAG, "autofill onSaveRequest password")

                    // Build expiration from date or from year and month
                    var expiration: DateTime? = parseResult.creditCardExpirationValue
                    if (parseResult.creditCardExpirationValue == null
                        && parseResult.creditCardExpirationYearValue != 0
                        && parseResult.creditCardExpirationMonthValue != 0) {
                        expiration = DateTime()
                            .withYear(parseResult.creditCardExpirationYearValue)
                            .withMonthOfYear(parseResult.creditCardExpirationMonthValue)
                        if (parseResult.creditCardExpirationDayValue != 0) {
                            expiration = expiration.withDayOfMonth(parseResult.creditCardExpirationDayValue)
                        }
                    }

                    // Show UI to save data
                    val searchInfo = SearchInfo().apply {
                        applicationId = parseResult.applicationId
                        webDomain = parseResult.webDomain
                        webScheme = parseResult.webScheme
                    }
                    val registerInfo = RegisterInfo(
                        searchInfo = searchInfo,
                        username = parseResult.usernameValue?.textValue?.toString(),
                        password = parseResult.passwordValue?.textValue?.toString()?.toCharArray(),
                        expiration = DateInstant(Instant(expiration)),
                        creditCard = parseResult.creditCardNumber?.textValue?.toString()?.let { cardNumber ->
                            CreditCard(
                                cardholder = parseResult.creditCardHolder?.textValue?.toString(),
                                number = cardNumber.toCharArray(),
                                cvv = parseResult.cardVerificationValue?.textValue?.toString()?.toCharArray()
                            )
                        }
                    )

                    AutofillLauncherActivity.getPendingIntentForRegistration(
                        this,
                        registerInfo
                    )?.intentSender?.let { intentSender ->
                        success = true
                        callback.onSuccess(intentSender)
                    }
                }
            }
        }
        if (!success) {
            callback.onFailure("Saving form values is not allowed")
        }
    }

    override fun onConnected() {
        Log.d(TAG, "onConnected")
        getPreferences()
    }

    override fun onDisconnected() {
        Log.d(TAG, "onDisconnected")
    }

    private data class PendingAutofillRequest(
        val databaseUri: android.net.Uri,
        val parseResult: StructureParser.Result,
        val searchInfo: SearchInfo,
        val autofillComponent: AutofillComponent,
        val callback: FillCallback
    )

    companion object {
        private val TAG = KeeAutofillService::class.java.name

        fun autofillAllowedFor(
            applicationId: String?,
            webDomain: String?,
            context: Context
        ): Boolean {
            return autofillAllowedFor(
                applicationId = applicationId,
                applicationIdBlocklist = PreferencesUtil.applicationIdBlocklist(context),
                webDomain = webDomain,
                webDomainBlocklist = PreferencesUtil.webDomainBlocklist(context))
        }

        fun autofillAllowedFor(
            applicationId: String?,
            applicationIdBlocklist: Set<String>?,
            webDomain: String?,
            webDomainBlocklist: Set<String>?
        ): Boolean {
            return autofillAllowedFor(applicationId, applicationIdBlocklist)
                    // To prevent unrecognized autofill popup id
                    && applicationId?.contains(APPLICATION_ID_POPUP_WINDOW) != true
                    && autofillAllowedFor(webDomain, webDomainBlocklist)
        }

        fun autofillAllowedFor(
            element: String?,
            blockList: Set<String>?
        ): Boolean {
            element?.let { elementNotNull ->
                if (blockList?.any { appIdBlocked ->
                            elementNotNull.contains(appIdBlocked)
                        } == true
                ) {
                    Log.d(TAG, "Autofill not allowed for $elementNotNull")
                    return false
                }
            }
            return true
        }

        fun Context.showAutofillDeviceSettings() {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                        data = "package:${KeeAutofillService::class.java.canonicalName}".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to choose the autofill service", e)
            }
        }
    }
}

fun Context.isKeeAutofillActivated(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.getSystemService(
            this,
            AutofillManager::class.java
        )?.hasEnabledAutofillServices() == true
    } else {
        false
    }
}
