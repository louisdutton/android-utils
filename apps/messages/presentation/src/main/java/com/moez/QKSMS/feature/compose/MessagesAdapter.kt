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
package dev.octoshrimpy.quik.feature.compose

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.moez.QKSMS.common.QkMediaPlayer
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkListAdapter
import dev.octoshrimpy.quik.common.base.QkViewHolder
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.util.TextViewStyler
import dev.octoshrimpy.quik.common.util.extensions.dpToPx
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setPadding
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import dev.octoshrimpy.quik.common.util.extensions.withAlpha
import dev.octoshrimpy.quik.common.widget.AvatarView
import dev.octoshrimpy.quik.compat.SubscriptionManagerCompat
import dev.octoshrimpy.quik.extensions.isSmil
import dev.octoshrimpy.quik.extensions.isText
import dev.octoshrimpy.quik.extensions.joinTo
import dev.octoshrimpy.quik.extensions.millisecondsToMinutes
import dev.octoshrimpy.quik.extensions.truncateWithEllipses
import dev.octoshrimpy.quik.feature.compose.BubbleUtils.canGroup
import dev.octoshrimpy.quik.feature.compose.BubbleUtils.getBubble
import dev.octoshrimpy.quik.feature.compose.part.PartsAdapter
import dev.octoshrimpy.quik.feature.extensions.isEmojiOnly
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.databinding.MessageListItemInBinding
import dev.octoshrimpy.quik.databinding.MessageListItemOutBinding
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import com.google.android.material.R as MaterialR

class MessagesAdapter @Inject constructor(
    subscriptionManager: SubscriptionManagerCompat,
    private val context: Context,
    private val colors: Colors,
    private val dateFormatter: DateFormatter,
    private val partsAdapterProvider: Provider<PartsAdapter>,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val prefs: Preferences,
    private val textViewStyler: TextViewStyler,
) : QkListAdapter<Message, QkViewHolder>() {
    class AudioState(
        var partId: Long = -1,
        var state: QkMediaPlayer.PlayingState = QkMediaPlayer.PlayingState.Stopped,
        var seekBarUpdater: Disposable? = null,
        var viewHolder: QkViewHolder? = null
    )

    companion object {
        private const val VIEW_TYPE_MESSAGE_IN = 0
        private const val VIEW_TYPE_MESSAGE_OUT = 1

        private const val MAX_MESSAGE_DISPLAY_LENGTH = 5000
        private const val MESSAGE_LINK_MASK = Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS or Linkify.WEB_URLS
    }

    private data class RowColors(
        val primary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val surfaceContainerHigh: Int,
        val surfaceContainerHighest: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val outline: Int,
    )

    private data class MessageText(
        val text: CharSequence,
        val isTruncated: Boolean,
        val emojiOnly: Boolean,
    )

    private class MessageViewHolder(
        view: View,
        val outgoing: Boolean,
        val timestamp: TextView,
        val simIndex: TextView,
        val sim: ImageView,
        val body: TextView,
        val parts: RecyclerView,
        val reactions: View,
        val reactionText: TextView,
        val status: TextView,
        val partsAdapter: PartsAdapter,
        val avatar: AvatarView? = null,
        val resendIcon: ImageView? = null,
    ) : QkViewHolder(view)

    // click events passed back to compose view model
    val partClicks: Subject<Long> = PublishSubject.create()
    val messageLinkClicks: Subject<Uri> = PublishSubject.create()
    val resendClicks: Subject<Long> = PublishSubject.create()
    val partContextMenuRegistrar: Subject<View> = PublishSubject.create()
    val reactionClicks: Subject<Long> = PublishSubject.create()

    var data: Pair<Conversation, List<Message>>? = null
        set(value) {
            if (field === value) return

            field = value
            contactCache.clear()

            updateData(value?.second)
        }

    /**
     * Safely return the conversation, if available
     */
    private val conversation: Conversation?
        get() = data?.first?.takeIf { it.isValid }

    private val contactCache = ContactCache()
    private val expanded = HashMap<Long, Boolean>()
    private val messageTextCache = LruCache<String, MessageText>(256)
    private var rowColors: RowColors? = null
    private val subs = subscriptionManager.activeSubscriptionInfoList

    var theme: Colors.Theme = colors.theme()
        set(value) {
            field = value
            rowColors = null
        }

    init {
        setHasStableIds(true)
    }

    private val audioState = AudioState()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkViewHolder {
        // Use the parent's context to inflate the layout, otherwise link clicks will crash the app
        val inflater = LayoutInflater.from(parent.context)

        val palette = rowColors(parent.context)

        if (viewType == VIEW_TYPE_MESSAGE_OUT) {
            val binding = MessageListItemOutBinding.inflate(inflater, parent, false)
            val partsAdapter = partsAdapterProvider.get().apply {
                clicks.subscribe(partClicks)
            }
            binding.parts.configurePartsList(partsAdapter)
            binding.body.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            binding.resendIcon.setTint(palette.primary)
            binding.resendIcon.setBackgroundTint(palette.surfaceContainerHigh)
            partContextMenuRegistrar.onNext(binding.parts)
            return MessageViewHolder(
                view = binding.root,
                outgoing = true,
                timestamp = binding.timestamp,
                simIndex = binding.simIndex,
                sim = binding.sim,
                body = binding.body,
                parts = binding.parts,
                reactions = binding.reactions,
                reactionText = binding.reactionText,
                status = binding.status,
                partsAdapter = partsAdapter,
                resendIcon = binding.resendIcon,
            ).attachRowClicks()
        } else {
            val binding = MessageListItemInBinding.inflate(inflater, parent, false)
            val partsAdapter = partsAdapterProvider.get().apply {
                clicks.subscribe(partClicks)
            }
            binding.parts.configurePartsList(partsAdapter)
            binding.body.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            partContextMenuRegistrar.onNext(binding.parts)
            return MessageViewHolder(
                view = binding.root,
                outgoing = false,
                timestamp = binding.timestamp,
                simIndex = binding.simIndex,
                sim = binding.sim,
                body = binding.body,
                parts = binding.parts,
                reactions = binding.reactions,
                reactionText = binding.reactionText,
                status = binding.status,
                partsAdapter = partsAdapter,
                avatar = binding.avatar,
            ).attachRowClicks()
        }
    }

    override fun onBindViewHolder(holder: QkViewHolder, position: Int) {
        holder as MessageViewHolder
        val message = getItem(position) ?: return
        val previous = if (position == 0) null else getItem(position - 1)
        val next = if (position == itemCount - 1) null else getItem(position + 1)

        val theme = colors.theme()
        val palette = rowColors(holder.itemView.context)

        // Update the selected state
        holder.itemView.isActivated = isSelected(message.id) || highlight == message.id

        // Get views based on message type
        val isOutgoing = message.isMe()
        val timestamp = holder.timestamp
        val simIndex = holder.simIndex
        val sim = holder.sim
        val body = holder.body
        val parts = holder.parts
        val reactions = holder.reactions
        val reactionText = holder.reactionText
        val status = holder.status

        if (isOutgoing) {
            holder.resendIcon?.setBackgroundTint(palette.surfaceContainerHigh)
            holder.resendIcon?.setTint(palette.primary)

            // bind the resend icon view
            if (message.isFailedMessage()) {
                holder.resendIcon?.visibility = View.VISIBLE
                holder.resendIcon?.setOnClickListener {
                    resendClicks.onNext(message.id)
                    holder.resendIcon.visibility = View.GONE
                }
            } else {
                holder.resendIcon?.visibility = View.GONE
                holder.resendIcon?.setOnClickListener(null)
            }

            body.apply {
                setTextColor(palette.onPrimaryContainer)
                setBackgroundTint(palette.primaryContainer)
                highlightColor = palette.primary.withAlpha(0x5d)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textSelectHandle?.setTint(palette.primary.withAlpha(0xad))
                    textSelectHandleLeft?.setTint(palette.primary.withAlpha(0xad))
                    textSelectHandleRight?.setTint(palette.primary.withAlpha(0xad))
                }
            }
        } else {
            // Bind the avatar and bubble colour
            holder.avatar?.apply {
                setRecipient(contactCache[message.address])
                setVisible(!canGroup(message, next), View.INVISIBLE)
            }

            body.apply {
                setTextColor(palette.onSurface)
                setBackgroundTint(palette.surfaceContainerHigh)
                highlightColor = palette.outline.withAlpha(0x5d)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    textSelectHandle?.setTint(palette.outline.withAlpha(0x7d))
                    textSelectHandleLeft?.setTint(palette.outline.withAlpha(0x7d))
                    textSelectHandleRight?.setTint(palette.outline.withAlpha(0x7d))
                }
            }
        }

        timestamp.setTextColor(palette.onSurfaceVariant)
        sim.setTint(palette.onSurfaceVariant)
        simIndex.setTextColor(palette.onSurfaceVariant)
        status.setTextColor(palette.onSurfaceVariant)
        reactionText.setTextColor(palette.onSurface)
        reactionText.setBackgroundTint(palette.surfaceContainerHighest)

        val messageText = getMessageText(message)

        // Bind the message status
        bindStatus(status, messageText.isTruncated, message, next)

        // Bind the timestamp
        val subscription = subs.find { it.subscriptionId == message.subId }

        timestamp.apply {
            text = dateFormatter.getMessageTimestamp(message.date)
            setVisible(
                    ((message.date - (previous?.date ?: 0))
                        .millisecondsToMinutes() >= BubbleUtils.TIMESTAMP_THRESHOLD) ||
                            (message.subId != previous?.subId) &&
                            (subscription != null)
            )
        }

        simIndex.text = subscription?.simSlotIndex?.plus(1)?.toString()

        ((message.subId != previous?.subId) && (subscription != null) && (subs.size > 1)).also {
            sim.setVisible(it)
            simIndex.setVisible(it)
        }

        // Bind the grouping
        holder.itemView.setPadding(
            bottom = if (canGroup(message, next)) 0 else 16.dpToPx(context)
        )

        // Bind the body text
        textViewStyler.setTextSize(
            body,
            when (messageText.emojiOnly) {
                true -> TextViewStyler.SIZE_EMOJI
                false -> TextViewStyler.SIZE_PRIMARY
            }
        )

        when (prefs.messageLinkHandling.get()) {
            Preferences.MESSAGE_LINK_HANDLING_BLOCK -> {
                body.autoLinkMask = 0
                body.movementMethod = null
            }
            Preferences.MESSAGE_LINK_HANDLING_ASK -> {
                body.autoLinkMask = 0
                body.linksClickable = false
                body.movementMethod = LinkMovementMethod.getInstance()
            }
            else -> {
                body.autoLinkMask = MESSAGE_LINK_MASK
                body.linksClickable = true
                body.movementMethod = LinkMovementMethod.getInstance()
            }
        }

        body.apply {
            text = messageText.text
            setVisible(message.isSms() || messageText.text.isNotBlank())

            setBackgroundResource(
                getBubble(
                    emojiOnly = messageText.emojiOnly,
                    canGroupWithPrevious = canGroup(message, previous) ||
                            message.parts.any { !it.isSmil() && !it.isText() },
                    canGroupWithNext = canGroup(message, next),
                    isMe = message.isMe()
                )
            )
            setBackgroundTint(if (message.isMe()) palette.primaryContainer else palette.surfaceContainerHigh)
        }

        // Bind the parts
        holder.partsAdapter.apply {
            this.theme = theme
            setData(message, previous, next, body.visibility == View.VISIBLE, audioState)
            contextMenuValue = message.id
        }
        parts.setVisible(holder.partsAdapter.itemCount > 0)

        showEmojiReactions(reactions, reactionText, message)
    }

    private fun MessageViewHolder.attachRowClicks(): MessageViewHolder {
        itemView.setOnClickListener {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            getItem(position)?.let {
                when (toggleSelection(it.id, false)) {
                    true -> itemView.isActivated = isSelected(it.id)
                    false -> {
                        expanded[it.id] = status.visibility != View.VISIBLE
                        notifyItemChanged(position)
                    }
                }
            }
        }
        itemView.setOnLongClickListener {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener true
            getItem(position)?.let {
                toggleSelection(it.id)
                itemView.isActivated = isSelected(it.id)
            }
            true
        }
        return this
    }

    private fun RecyclerView.configurePartsList(adapter: PartsAdapter) {
        itemAnimator = null
        setHasFixedSize(false)
        this.adapter = adapter
    }

    private fun rowColors(context: Context): RowColors {
        rowColors?.let { return it }
        return RowColors(
            primary = context.resolveThemeColor(androidx.appcompat.R.attr.colorPrimary),
            primaryContainer = context.resolveThemeColor(MaterialR.attr.colorPrimaryContainer),
            onPrimaryContainer = context.resolveThemeColor(MaterialR.attr.colorOnPrimaryContainer),
            surfaceContainerHigh = context.resolveThemeColor(MaterialR.attr.colorSurfaceContainerHigh),
            surfaceContainerHighest = context.resolveThemeColor(MaterialR.attr.colorSurfaceContainerHighest),
            onSurface = context.resolveThemeColor(MaterialR.attr.colorOnSurface),
            onSurfaceVariant = context.resolveThemeColor(MaterialR.attr.colorOnSurfaceVariant),
            outline = context.resolveThemeColor(MaterialR.attr.colorOutline),
        ).also { rowColors = it }
    }

    private fun getMessageText(message: Message): MessageText {
        val linkHandling = prefs.messageLinkHandling.get()
        val key = "${message.id}:$linkHandling:${message.emojiReactions.size}"
        messageTextCache.get(key)?.let { return it }

        val subject = message.getCleansedSubject()
        val rawText = subject.joinTo(message.getText(false), "\n")
        val isTruncated = rawText.length > MAX_MESSAGE_DISPLAY_LENGTH
        val displayText = rawText.truncateWithEllipses(MAX_MESSAGE_DISPLAY_LENGTH)
        val text = when (linkHandling) {
            Preferences.MESSAGE_LINK_HANDLING_ASK -> SpannableStringBuilder(displayText).apply {
                if (subject.isNotBlank()) {
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        minOf(subject.length, displayText.length),
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                }
                Linkify.addLinks(this, MESSAGE_LINK_MASK)
                for (span in getSpans(0, length, URLSpan::class.java)) {
                    setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                messageLinkClicks.onNext(span.url.toUri())
                            }
                        },
                        getSpanStart(span),
                        getSpanEnd(span),
                        getSpanFlags(span)
                    )
                    removeSpan(span)
                }
            }
            else -> if (subject.isNotBlank()) SpannableString(displayText).apply {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    minOf(subject.length, displayText.length),
                    Spannable.SPAN_INCLUSIVE_EXCLUSIVE
                )
            } else displayText
        }

        return MessageText(
            text = text,
            isTruncated = isTruncated,
            emojiOnly = displayText.isEmojiOnly(),
        ).also { messageTextCache.put(key, it) }
    }

    private fun showEmojiReactions(reactionsContainer: View, reactionTextView: TextView, message: Message) {
        val reactions = message.emojiReactions
        val hasReactions = reactions.isNotEmpty()

        if (hasReactions) {
            val uniqueEmojis = reactions.map { it.emoji }.distinct()
            val totalCount = reactions.size

            // Show unique emojis followed by total count
            val reactionText = if (totalCount == 1) {
                uniqueEmojis.first()
            } else {
                "${uniqueEmojis.joinToString("")}\u00A0$totalCount"
            }

            reactionTextView.text = reactionText
            reactionTextView.setOnClickListener { reactionClicks.onNext(message.id) }
            reactionsContainer.setVisible(true)
        } else {
            reactionsContainer.setVisible(false)
            reactionTextView.setOnClickListener(null)
        }
    }


    private fun bindStatus(
        statusView: TextView,
        bodyTextTruncated: Boolean,
        message: Message,
        next: Message?
    ) {
        statusView.apply {
            text = when {
                message.isSending() -> context.getString(R.string.message_status_sending)
                message.isDelivered() -> context.getString(
                    R.string.message_status_delivered,
                    dateFormatter.getTimestamp(message.dateSent)
                )
                message.isFailedMessage() -> context.getString(R.string.message_status_failed)
                bodyTextTruncated -> context.getString(R.string.message_body_too_long_to_display)
                (!message.isMe() && (conversation?.recipients?.size ?: 0) > 1) ->
                    // incoming group message
                    "${contactCache[message.address]?.getDisplayName()} • ${
                        dateFormatter.getTimestamp(message.date)}"
                else -> dateFormatter.getTimestamp(message.date)
            }

            val age = TimeUnit.MILLISECONDS.toMinutes(
                System.currentTimeMillis() - message.date
            )

            setVisible(
                when {
                    expanded[message.id] == true -> true
                    message.isSending() -> true
                    message.isFailedMessage() -> true
                    bodyTextTruncated -> true
                    expanded[message.id] == false -> false
                    ((conversation?.recipients?.size ?: 0) > 1) &&
                            !message.isMe() && next?.compareSender(message) != true -> true
                    (message.isDelivered() &&
                            (next?.isDelivered() != true) &&
                            (age <= BubbleUtils.TIMESTAMP_THRESHOLD)) -> true

                    else -> false
                }
            )
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: -1
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position) ?: return -1
        return when (message.isMe()) {
            true -> VIEW_TYPE_MESSAGE_OUT
            false -> VIEW_TYPE_MESSAGE_IN
        }
    }

    fun expandMessages(messageIds: List<Long>, expand: Boolean) {
        messageIds.forEach { expanded[it] = expand }
        notifyDataSetChanged()
    }

    /**
     * Cache the contacts in a map by the address, because the messages we're binding don't have
     * a reference to the contact.
     */
    private inner class ContactCache : HashMap<String, Recipient?>() {
        override fun get(key: String): Recipient? {
            if (super.get(key)?.isValid != true)
                set(
                    key,
                    conversation?.recipients?.firstOrNull {
                        phoneNumberUtils.compare(it.address, key)
                    }
                )

            return super.get(key)?.takeIf { it.isValid }
        }

    }
}
