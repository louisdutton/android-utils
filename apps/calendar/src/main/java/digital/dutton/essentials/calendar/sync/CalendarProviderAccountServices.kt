package digital.dutton.essentials.calendar.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

class CalDavAccountAuthenticatorService : Service() {
    private val authenticator by lazy { CalendarAccountAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}

class SubscriptionAccountAuthenticatorService : Service() {
    private val authenticator by lazy { CalendarAccountAuthenticator(this) }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}

class CalDavCalendarSyncAdapterService : Service() {
    private val syncAdapter by lazy { NoOpCalendarSyncAdapter(this) }

    override fun onBind(intent: Intent?): IBinder = syncAdapter.syncAdapterBinder
}

class SubscriptionCalendarSyncAdapterService : Service() {
    private val syncAdapter by lazy { NoOpCalendarSyncAdapter(this) }

    override fun onBind(intent: Intent?): IBinder = syncAdapter.syncAdapterBinder
}

private class CalendarAccountAuthenticator(
    context: Context,
) : AbstractAccountAuthenticator(context) {
    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = Bundle()

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle()

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?,
    ): Bundle = Bundle()

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = Bundle()

    override fun getAuthTokenLabel(authTokenType: String?): String = ""

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = Bundle()

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

private class NoOpCalendarSyncAdapter(
    context: Context,
) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?,
    ) = Unit
}
