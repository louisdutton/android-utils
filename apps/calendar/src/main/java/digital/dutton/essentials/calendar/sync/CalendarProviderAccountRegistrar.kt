package digital.dutton.essentials.calendar.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract

internal object CalendarProviderAccountRegistrar {
    fun ensureSubscriptionAccount(context: Context) {
        ensureAccount(context, Account(SubscriptionAccountName, SubscriptionAccountType))
    }

    fun ensureCalDavAccount(
        context: Context,
        accountId: String,
    ) {
        ensureAccount(context, Account(accountId, CalDavAccountType))
    }

    private fun ensureAccount(
        context: Context,
        account: Account,
    ) {
        val accountManager = AccountManager.get(context)
        val exists = accountManager
            .getAccountsByType(account.type)
            .any { it.name == account.name }
        if (!exists) {
            accountManager.addAccountExplicitly(account, null, null)
        }

        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, false)
    }
}
