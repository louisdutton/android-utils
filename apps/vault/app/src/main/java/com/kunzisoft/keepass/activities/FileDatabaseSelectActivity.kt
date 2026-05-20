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
package com.kunzisoft.keepass.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.SetMainCredentialDialogFragment
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.credentialprovider.EntrySelectionHelper
import com.kunzisoft.keepass.credentialprovider.SpecialMode
import com.kunzisoft.keepass.credentialprovider.TypeMode
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.hardware.HardwareKey
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.DATABASE_URI_KEY
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.utils.DexUtil
import com.kunzisoft.keepass.utils.MagikeyboardUtil
import com.kunzisoft.keepass.utils.MenuUtil
import com.kunzisoft.keepass.utils.UriUtil.getDocumentFile
import com.kunzisoft.keepass.utils.getParcelableCompat
import com.kunzisoft.keepass.view.asError
import com.kunzisoft.keepass.view.showActionErrorIfNeeded
import com.kunzisoft.keepass.vault.VaultFile
import java.io.FileNotFoundException

class FileDatabaseSelectActivity : DatabaseModeActivity(),
        SetMainCredentialDialogFragment.AssignMainCredentialDialogListener {

    // Views
    private lateinit var coordinatorLayout: CoordinatorLayout
    private var createDatabaseButtonView: View? = null

    private var mDatabaseFileUri: Uri? = null

    override fun manageDatabaseInfo(): Boolean  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enabling/disabling MagikeyboardService is normally done by DexModeReceiver, but this
        // additional check will allow the keyboard to be reenabled more easily if the app crashes
        // or is force quit within DeX mode and then the user leaves DeX mode. Without this, the
        // user would need to enter and exit DeX mode once to reenable the service.
        MagikeyboardUtil.setEnabled(this, !DexUtil.isDexMode(resources.configuration))
        PreferencesUtil.enforceNativeVaultUnlockDefaults(this)

        setContentView(R.layout.activity_file_selection)
        coordinatorLayout = findViewById(R.id.activity_file_selection_coordinator_layout)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = ""
        setSupportActionBar(toolbar)

        // Create database button
        createDatabaseButtonView = findViewById(R.id.create_database_button)
        createDatabaseButtonView?.setOnClickListener { createNewFile() }

        // Retrieve the database URI provided by file manager after an orientation change
        if (savedInstanceState != null
                && savedInstanceState.containsKey(EXTRA_DATABASE_URI)) {
            mDatabaseFileUri = savedInstanceState.getParcelableCompat(EXTRA_DATABASE_URI)
        }

        openVaultOrStartCreation()
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        launchGroupActivityIfLoaded(database)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        if (!result.isSuccess) {
            if (actionTask == ACTION_DATABASE_CREATE_TASK) {
                deleteEmptyCreatedDatabase(result)
            }
            coordinatorLayout.showActionErrorIfNeeded(result)
            return
        }

        // Launch activity
        when (actionTask) {
            ACTION_DATABASE_CREATE_TASK -> {
                GroupActivity.launch(
                    this@FileDatabaseSelectActivity,
                    database,
                    false
                )
                finish()
            }
            ACTION_DATABASE_LOAD_TASK -> {
                launchGroupActivityIfLoaded(database)
            }
        }
    }

    private fun deleteEmptyCreatedDatabase(result: ActionRunnable.Result) {
        val databaseUri = result.data?.getParcelableCompat<Uri>(DATABASE_URI_KEY)
            ?: mDatabaseFileUri
            ?: return
        try {
            val documentFile = databaseUri.getDocumentFile(this) ?: return
            if (documentFile.exists() && documentFile.length() == 0L) {
                if (documentFile.delete() && mDatabaseFileUri == databaseUri) {
                    mDatabaseFileUri = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove empty database after failed create", e)
        }
    }

    private fun createNewFile() {
        if (VaultFile.exists(this)) {
            launchMainCredentialActivityWithPath(VaultFile.uri(this))
            finish()
            return
        }

        VaultFile.file(this).parentFile?.mkdirs()
        mDatabaseFileUri = VaultFile.uri(this)
        if (supportFragmentManager.findFragmentByTag(PASSWORD_DIALOG_TAG) != null) return
        SetMainCredentialDialogFragment.getNativeVaultInstance()
            .show(supportFragmentManager, PASSWORD_DIALOG_TAG)
    }

    private fun openVaultOrStartCreation() {
        if (VaultFile.exists(this)) {
            launchMainCredentialActivityWithPath(VaultFile.uri(this))
            finish()
        } else if (mSpecialMode == SpecialMode.DEFAULT) {
            createNewFile()
        }
    }

    private fun fileNoFoundAction(e: FileNotFoundException) {
        val error = getString(R.string.file_not_found_content)
        Log.e(TAG, error, e)
        Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
    }

    private fun launchMainCredentialActivity(databaseUri: Uri, keyFile: Uri?, hardwareKey: HardwareKey?) {
        try {
            EntrySelectionHelper.doSpecialAction(
                intent = this.intent,
                defaultAction = {
                    MainCredentialActivity.launch(
                        activity = this,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey
                    )
                },
                searchAction = { searchInfo ->
                    MainCredentialActivity.launchForSearchResult(
                        activity = this,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey,
                        searchInfo = searchInfo
                    )
                    onLaunchActivitySpecialMode()
                },
                selectionAction = { intentSenderMode, typeMode, searchInfo ->
                    MainCredentialActivity.launchForSelection(
                        activity = this,
                        activityResultLauncher = if (intentSenderMode)
                            mCredentialActivityResultLauncher else null,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey,
                        typeMode = typeMode,
                        searchInfo = searchInfo
                    )
                    onLaunchActivitySpecialMode()
                },
                registrationAction = { intentSenderMode, typeMode, registerInfo ->
                    MainCredentialActivity.launchForRegistration(
                        activity = this,
                        activityResultLauncher = if (intentSenderMode)
                            mCredentialActivityResultLauncher else null,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey,
                        typeMode = typeMode,
                        registerInfo = registerInfo
                    )
                    onLaunchActivitySpecialMode()
                }
            )
        } catch (e: FileNotFoundException) {
            fileNoFoundAction(e)
        }
    }

    private fun launchGroupActivityIfLoaded(database: ContextualDatabase) {
        if (database.loaded) {
            GroupActivity.launch(this,
                database,
                { onValidateSpecialMode() },
                { onCancelSpecialMode() },
                { onLaunchActivitySpecialMode() },
                mCredentialActivityResultLauncher
            )
        }
    }

    private fun launchMainCredentialActivityWithPath(databaseUri: Uri) {
        launchMainCredentialActivity(databaseUri, null, null)
        // Delete flickering for kitkat <=
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()

        openVaultOrStartCreation()
        if (isFinishing) return

        // Show open and create button or special mode
        when (mSpecialMode) {
            SpecialMode.DEFAULT -> {
                createDatabaseButtonView?.visibility = View.VISIBLE
            }
            else -> {
                // Disable create button if in selection mode or request for autofill
                createDatabaseButtonView?.visibility = View.GONE
            }
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // to retrieve the URI of a created database after an orientation change
        outState.putParcelable(EXTRA_DATABASE_URI, mDatabaseFileUri)
    }

    override fun onAssignKeyDialogPositiveClick(mainCredential: MainCredential) {
        try {
            mDatabaseFileUri?.let { databaseUri ->
                // Create the new database
                mDatabaseViewModel.createDatabase(databaseUri, mainCredential)
            }
        } catch (e: Exception) {
            val error = getString(R.string.error_create_database_file)
            Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
            Log.e(TAG, error, e)
        }
    }

    override fun onAssignKeyDialogNegativeClick(mainCredential: MainCredential) {
        if (!VaultFile.exists(this)) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        if (mSpecialMode == SpecialMode.DEFAULT) {
            MenuUtil.defaultMenuInflater(menuInflater, menu)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        MenuUtil.onDefaultMenuOptionsItemSelected(this, item)
        return super.onOptionsItemSelected(item)
    }

    companion object {

        private const val TAG = "FileDbSelectActivity"
        private const val EXTRA_DATABASE_URI = "EXTRA_DATABASE_URI"
        private const val PASSWORD_DIALOG_TAG = "passwordDialog"

        /*
         * -------------------------
         * 		Standard Launch
         * -------------------------
         */

        fun launch(context: Context) {
            context.startActivity(Intent(context, FileDatabaseSelectActivity::class.java))
        }

        /*
         * -------------------------
         * 		Search Launch
         * -------------------------
         */

        fun launchForSearch(
            context: Context,
            searchInfo: SearchInfo
        ) {
            EntrySelectionHelper.startActivityForSearchModeResult(
                context = context,
                intent = Intent(context, FileDatabaseSelectActivity::class.java),
                searchInfo = searchInfo
            )
        }

        /*
         * -------------------------
         * 		Selection Launch
         * -------------------------
         */

        fun launchForSelection(
            context: Context,
            typeMode: TypeMode,
            searchInfo: SearchInfo? = null,
            activityResultLauncher: ActivityResultLauncher<Intent>?,
        ) {
            EntrySelectionHelper.startActivityForSelectionModeResult(
                context = context,
                intent = Intent(context, FileDatabaseSelectActivity::class.java),
                searchInfo = searchInfo,
                typeMode = typeMode,
                activityResultLauncher = activityResultLauncher
            )
        }

        /*
         * -------------------------
         * 		Registration Launch
         * -------------------------
         */
        fun launchForRegistration(
            context: Context,
            typeMode: TypeMode,
            registerInfo: RegisterInfo? = null,
            activityResultLauncher: ActivityResultLauncher<Intent>?,
        ) {
            EntrySelectionHelper.startActivityForRegistrationModeResult(
                context = context,
                intent = Intent(context, FileDatabaseSelectActivity::class.java),
                registerInfo = registerInfo,
                typeMode = typeMode,
                activityResultLauncher = activityResultLauncher
            )
        }
    }
}
