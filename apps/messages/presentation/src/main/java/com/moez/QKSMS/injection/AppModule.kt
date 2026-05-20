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
package dev.octoshrimpy.quik.injection

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.room.Room
import androidx.work.WorkerFactory
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.blocking.BlockingManager
import dev.octoshrimpy.quik.common.util.NotificationManagerImpl
import dev.octoshrimpy.quik.common.util.ShortcutManagerImpl
import dev.octoshrimpy.quik.database.BlockingDao
import dev.octoshrimpy.quik.database.ContactDao
import dev.octoshrimpy.quik.database.ConversationDao
import dev.octoshrimpy.quik.database.EmojiReactionDao
import dev.octoshrimpy.quik.database.MessageDao
import dev.octoshrimpy.quik.database.MessageContentFilterDao
import dev.octoshrimpy.quik.database.MessagesDatabase
import dev.octoshrimpy.quik.database.MmsPartDao
import dev.octoshrimpy.quik.database.RecipientDao
import dev.octoshrimpy.quik.database.ScheduledMessageDao
import dev.octoshrimpy.quik.database.SyncStateDao
import dev.octoshrimpy.quik.feature.conversationinfo.injection.ConversationInfoComponent
import dev.octoshrimpy.quik.feature.themepicker.injection.ThemePickerComponent
import dev.octoshrimpy.quik.listener.ContactAddedListener
import dev.octoshrimpy.quik.listener.ContactAddedListenerImpl
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.ActiveConversationManagerImpl
import dev.octoshrimpy.quik.manager.AlarmManager
import dev.octoshrimpy.quik.manager.AlarmManagerImpl
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.manager.KeyManagerImpl
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.manager.PermissionManagerImpl
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.manager.WidgetManager
import dev.octoshrimpy.quik.manager.WidgetManagerImpl
import dev.octoshrimpy.quik.mapper.CursorToContact
import dev.octoshrimpy.quik.mapper.CursorToContactGroup
import dev.octoshrimpy.quik.mapper.CursorToContactGroupImpl
import dev.octoshrimpy.quik.mapper.CursorToContactGroupMember
import dev.octoshrimpy.quik.mapper.CursorToContactGroupMemberImpl
import dev.octoshrimpy.quik.mapper.CursorToContactImpl
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToConversationImpl
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToMessageImpl
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.mapper.CursorToPartImpl
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.mapper.CursorToRecipientImpl
import dev.octoshrimpy.quik.repository.BackupRepository
import dev.octoshrimpy.quik.repository.BackupRepositoryImpl
import dev.octoshrimpy.quik.repository.BlockingRepository
import dev.octoshrimpy.quik.repository.BlockingRepositoryImpl
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ContactRepositoryImpl
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.ConversationRepositoryImpl
import dev.octoshrimpy.quik.repository.EmojiReactionRepository
import dev.octoshrimpy.quik.repository.EmojiReactionRepositoryImpl
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepositoryImpl
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.MessageRepositoryImpl
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import dev.octoshrimpy.quik.repository.ScheduledMessageRepositoryImpl
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.repository.SyncRepositoryImpl
import dev.octoshrimpy.quik.worker.InjectionWorkerFactory
import javax.inject.Singleton

@Module(subcomponents = [
    ConversationInfoComponent::class,
    ThemePickerComponent::class])
class AppModule(private var application: Application) {

    @Provides
    @Singleton
    fun provideContext(): Context = application

    @Provides
    fun provideContentResolver(context: Context): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun provideSharedPreferences(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Provides
    @Singleton
    fun provideRxPreferences(preferences: SharedPreferences): RxSharedPreferences {
        return RxSharedPreferences.create(preferences)
    }

    @Provides
    @Singleton
    fun provideMessagesDatabase(context: Context): MessagesDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            MessagesDatabase::class.java,
            "messages.db"
        )
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideMessageDao(database: MessagesDatabase): MessageDao = database.messages()

    @Provides
    fun provideMmsPartDao(database: MessagesDatabase): MmsPartDao = database.mmsParts()

    @Provides
    fun provideConversationDao(database: MessagesDatabase): ConversationDao = database.conversations()

    @Provides
    fun provideRecipientDao(database: MessagesDatabase): RecipientDao = database.recipients()

    @Provides
    fun provideContactDao(database: MessagesDatabase): ContactDao = database.contacts()

    @Provides
    fun provideSyncStateDao(database: MessagesDatabase): SyncStateDao = database.syncState()

    @Provides
    fun provideBlockingDao(database: MessagesDatabase): BlockingDao = database.blocking()

    @Provides
    fun provideMessageContentFilterDao(database: MessagesDatabase): MessageContentFilterDao = database.messageContentFilters()

    @Provides
    fun provideScheduledMessageDao(database: MessagesDatabase): ScheduledMessageDao = database.scheduledMessages()

    @Provides
    fun provideEmojiReactionDao(database: MessagesDatabase): EmojiReactionDao = database.emojiReactions()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
    }

    // Listener

    @Provides
    fun provideContactAddedListener(listener: ContactAddedListenerImpl): ContactAddedListener = listener

    // Manager

    @Provides
    fun provideActiveConversationManager(manager: ActiveConversationManagerImpl): ActiveConversationManager = manager

    @Provides
    fun provideAlarmManager(manager: AlarmManagerImpl): AlarmManager = manager

    @Provides
    fun blockingClient(manager: BlockingManager): BlockingClient = manager

    @Provides
    fun provideKeyManager(manager: KeyManagerImpl): KeyManager = manager

    @Provides
    fun provideNotificationsManager(manager: NotificationManagerImpl): NotificationManager = manager

    @Provides
    fun providePermissionsManager(manager: PermissionManagerImpl): PermissionManager = manager

    @Provides
    fun provideShortcutManager(manager: ShortcutManagerImpl): ShortcutManager = manager

    @Provides
    fun provideWidgetManager(manager: WidgetManagerImpl): WidgetManager = manager

    // Mapper

    @Provides
    fun provideCursorToContact(mapper: CursorToContactImpl): CursorToContact = mapper

    @Provides
    fun provideCursorToContactGroup(mapper: CursorToContactGroupImpl): CursorToContactGroup = mapper

    @Provides
    fun provideCursorToContactGroupMember(mapper: CursorToContactGroupMemberImpl): CursorToContactGroupMember = mapper

    @Provides
    fun provideCursorToConversation(mapper: CursorToConversationImpl): CursorToConversation = mapper

    @Provides
    fun provideCursorToMessage(mapper: CursorToMessageImpl): CursorToMessage = mapper

    @Provides
    fun provideCursorToPart(mapper: CursorToPartImpl): CursorToPart = mapper

    @Provides
    fun provideCursorToRecipient(mapper: CursorToRecipientImpl): CursorToRecipient = mapper

    // Repository

    @Provides
    fun provideBackupRepository(repository: BackupRepositoryImpl): BackupRepository = repository

    @Provides
    fun provideBlockingRepository(repository: BlockingRepositoryImpl): BlockingRepository = repository

    @Provides
    fun provideMessageContentFilterRepository(repository: MessageContentFilterRepositoryImpl): MessageContentFilterRepository = repository

    @Provides
    fun provideContactRepository(repository: ContactRepositoryImpl): ContactRepository = repository

    @Provides
    fun provideConversationRepository(repository: ConversationRepositoryImpl): ConversationRepository = repository

    @Provides
    fun provideMessageRepository(repository: MessageRepositoryImpl): MessageRepository = repository

    @Provides
    fun provideScheduledMessagesRepository(repository: ScheduledMessageRepositoryImpl): ScheduledMessageRepository = repository

    @Provides
    fun provideSyncRepository(repository: SyncRepositoryImpl): SyncRepository = repository

    @Provides
    fun provideEmojiReactionRepository(repository: EmojiReactionRepositoryImpl): EmojiReactionRepository = repository

    // worker factory
    @Provides
    fun provideWorkerFactory(workerFactory: InjectionWorkerFactory): WorkerFactory = workerFactory
}
