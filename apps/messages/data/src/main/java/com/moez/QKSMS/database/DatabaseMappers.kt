package dev.octoshrimpy.quik.database

import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.ContactGroup
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.PhoneNumber
import dev.octoshrimpy.quik.model.Recipient

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    threadId = threadId,
    contentId = contentId,
    address = address,
    boxId = boxId,
    type = type,
    date = date,
    dateSent = dateSent,
    seen = seen,
    read = read,
    locked = locked,
    subId = subId,
    body = body,
    errorCode = errorCode,
    deliveryStatus = deliveryStatus,
    attachmentTypeString = attachmentTypeString,
    mmsDeliveryStatusString = mmsDeliveryStatusString,
    readReportString = readReportString,
    errorType = errorType,
    messageSize = messageSize,
    messageType = messageType,
    mmsStatus = mmsStatus,
    subject = subject,
    textContentType = textContentType,
    isEmojiReaction = isEmojiReaction,
    sendAsGroup = sendAsGroup
)

fun MessageEntity.toModel(parts: List<MmsPart> = emptyList()): Message = Message().also { message ->
    message.id = id
    message.threadId = threadId
    message.contentId = contentId
    message.address = address
    message.boxId = boxId
    message.type = type
    message.date = date
    message.dateSent = dateSent
    message.seen = seen
    message.read = read
    message.locked = locked
    message.subId = subId
    message.body = body
    message.errorCode = errorCode
    message.deliveryStatus = deliveryStatus
    message.attachmentTypeString = attachmentTypeString
    message.mmsDeliveryStatusString = mmsDeliveryStatusString
    message.readReportString = readReportString
    message.errorType = errorType
    message.messageSize = messageSize
    message.messageType = messageType
    message.mmsStatus = mmsStatus
    message.subject = subject
    message.textContentType = textContentType
    message.parts = parts.toMutableList()
    message.isEmojiReaction = isEmojiReaction
    message.sendAsGroup = sendAsGroup
}

fun MmsPart.toEntity(): MmsPartEntity = MmsPartEntity(
    id = id,
    messageId = messageId,
    type = type,
    seq = seq,
    name = name,
    text = text
)

fun MmsPartEntity.toModel(): MmsPart = MmsPart().also { part ->
    part.id = id
    part.messageId = messageId
    part.type = type
    part.seq = seq
    part.name = name
    part.text = text
}

fun Contact.toEntity(): ContactEntity = ContactEntity(
    lookupKey = lookupKey,
    name = name,
    photoUri = photoUri,
    starred = starred,
    lastUpdate = lastUpdate
)

fun PhoneNumber.toEntity(contactLookupKey: String): PhoneNumberEntity = PhoneNumberEntity(
    id = id,
    contactLookupKey = contactLookupKey,
    accountType = accountType,
    address = address,
    type = type,
    isDefault = isDefault
)

fun ContactEntity.toModel(numbers: List<PhoneNumber> = emptyList()): Contact = Contact(
    lookupKey = lookupKey,
    numbers = numbers.toMutableList(),
    name = name,
    photoUri = photoUri,
    starred = starred,
    lastUpdate = lastUpdate
)

fun PhoneNumberEntity.toModel(): PhoneNumber = PhoneNumber(
    id = id,
    accountType = accountType,
    address = address,
    type = type,
    isDefault = isDefault
)

fun Recipient.toEntity(): RecipientEntity = RecipientEntity(
    id = id,
    address = address,
    contactLookupKey = contact?.lookupKey,
    lastUpdate = lastUpdate
)

fun RecipientEntity.toModel(contact: Contact? = null): Recipient = Recipient(
    id = id,
    address = address,
    contact = contact,
    lastUpdate = lastUpdate
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    archived = archived,
    blocked = blocked,
    pinned = pinned,
    lastMessageId = lastMessage?.id,
    lastMessageDate = date,
    draft = draft,
    draftDate = draftDate,
    blockingClient = blockingClient,
    blockReason = blockReason,
    name = name,
    sendAsGroup = sendAsGroup
)

fun ConversationEntity.toModel(
    recipients: List<Recipient> = emptyList(),
    lastMessage: Message? = null
): Conversation = Conversation(
    id = id,
    archived = archived,
    blocked = blocked,
    pinned = pinned,
    recipients = recipients.toMutableList(),
    lastMessage = lastMessage,
    draft = draft,
    draftDate = draftDate,
    blockingClient = blockingClient,
    blockReason = blockReason,
    name = name,
    sendAsGroup = sendAsGroup
)

fun ContactGroupEntity.toModel(contacts: List<Contact>): ContactGroup = ContactGroup(
    id = id,
    title = title,
    contacts = contacts.toMutableList()
)

fun BlockedNumber.toEntity(): BlockedNumberEntity = BlockedNumberEntity(
    id = id,
    address = address
)

fun BlockedNumberEntity.toModel(): BlockedNumber = BlockedNumber(
    id = id,
    address = address
)

fun MessageContentFilter.toEntity(): MessageContentFilterEntity = MessageContentFilterEntity(
    id = id,
    value = value,
    caseSensitive = caseSensitive,
    isRegex = isRegex,
    includeContacts = includeContacts
)

fun MessageContentFilterEntity.toModel(): MessageContentFilter = MessageContentFilter(
    id = id,
    value = value,
    caseSensitive = caseSensitive,
    isRegex = isRegex,
    includeContacts = includeContacts
)

fun EmojiReaction.toEntity(targetMessageId: Long = this.targetMessageId): EmojiReactionEntity = EmojiReactionEntity(
    id = id,
    reactionMessageId = reactionMessageId,
    targetMessageId = targetMessageId,
    senderAddress = senderAddress,
    emoji = emoji,
    originalMessageText = originalMessageText,
    threadId = threadId
)

fun EmojiReactionEntity.toModel(): EmojiReaction = EmojiReaction().also { reaction ->
    reaction.id = id
    reaction.reactionMessageId = reactionMessageId
    reaction.targetMessageId = targetMessageId
    reaction.senderAddress = senderAddress
    reaction.emoji = emoji
    reaction.originalMessageText = originalMessageText
    reaction.threadId = threadId
}
