package dev.octoshrimpy.quik.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        MmsPartEntity::class,
        ConversationEntity::class,
        ConversationRecipientEntity::class,
        RecipientEntity::class,
        ContactEntity::class,
        PhoneNumberEntity::class,
        ContactGroupEntity::class,
        ContactGroupMemberEntity::class,
        SyncStateEntity::class,
        BlockedNumberEntity::class,
        MessageContentFilterEntity::class,
        ScheduledMessageEntity::class,
        EmojiReactionEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MessagesDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun mmsParts(): MmsPartDao
    abstract fun conversations(): ConversationDao
    abstract fun recipients(): RecipientDao
    abstract fun contacts(): ContactDao
    abstract fun syncState(): SyncStateDao
    abstract fun blocking(): BlockingDao
    abstract fun messageContentFilters(): MessageContentFilterDao
    abstract fun scheduledMessages(): ScheduledMessageDao
    abstract fun emojiReactions(): EmojiReactionDao
}
