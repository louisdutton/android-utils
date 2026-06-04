package dev.octoshrimpy.quik.feature.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.util.Preferences
import kotlin.math.abs
import kotlin.math.ceil

@Composable
internal fun ConversationRecyclerList(
    rows: List<ConversationRowModel>,
    selectedConversationIds: Set<Long>,
    swipeRightAction: Int,
    swipeLeftAction: Int,
    swipesEnabled: Boolean,
    onConversationClick: (Long) -> Unit,
    onConversationLongClick: (Long) -> Unit,
    onConversationSwipe: (Long, Int) -> Unit,
) {
    val colors = ConversationRecyclerColors(
        background = MaterialTheme.colorScheme.background.toArgb(),
        selected = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb(),
        onSurface = MaterialTheme.colorScheme.onSurface.toArgb(),
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        primary = MaterialTheme.colorScheme.primary.toArgb(),
        primaryContainer = MaterialTheme.colorScheme.primaryContainer.toArgb(),
        onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
        surfaceContainerHighest = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb(),
        swipeBackground = MaterialTheme.colorScheme.primaryContainer.toArgb(),
        swipeIcon = MaterialTheme.colorScheme.onPrimaryContainer.toArgb(),
    )

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val adapter = ConversationRowsAdapter(
                onConversationClick = onConversationClick,
                onConversationLongClick = onConversationLongClick,
            )
            val swipeCallback = ConversationSwipeCallback(
                context = context,
                adapter = adapter,
                onConversationSwipe = onConversationSwipe,
            )

            RecyclerView(context).apply {
                id = R.id.recyclerView
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                setHasFixedSize(true)
                setItemViewCacheSize(24)
                recycledViewPool.setMaxRecycledViews(0, 48)
                clipToPadding = false
                setPadding(0, 8.dp(context), 0, 8.dp(context))
                this.adapter = adapter
                ItemTouchHelper(swipeCallback).attachToRecyclerView(this)
                setTag(R.id.recyclerView, swipeCallback)
            }
        },
        update = { recyclerView ->
            val adapter = recyclerView.adapter as ConversationRowsAdapter
            val swipeCallback = recyclerView.getTag(R.id.recyclerView) as ConversationSwipeCallback
            adapter.submit(rows, selectedConversationIds, colors)
            swipeCallback.configure(
                swipesEnabled = swipesEnabled && selectedConversationIds.isEmpty(),
                swipeRightAction = swipeRightAction,
                swipeLeftAction = swipeLeftAction,
                colors = colors,
            )
        },
    )
}

private data class ConversationRecyclerColors(
    val background: Int,
    val selected: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val surfaceContainerHighest: Int,
    val swipeBackground: Int,
    val swipeIcon: Int,
)

private class ConversationRowsAdapter(
    private val onConversationClick: (Long) -> Unit,
    private val onConversationLongClick: (Long) -> Unit,
) : RecyclerView.Adapter<ConversationRowsAdapter.ViewHolder>() {

    private var rows: List<ConversationRowModel> = emptyList()
    private var selectedIds: Set<Long> = emptySet()
    private var colors = ConversationRecyclerColors(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    init {
        setHasStableIds(true)
    }

    fun submit(
        newRows: List<ConversationRowModel>,
        newSelectedIds: Set<Long>,
        newColors: ConversationRecyclerColors,
    ) {
        val colorsChanged = colors != newColors
        val previousSelectedIds = selectedIds

        colors = newColors
        selectedIds = newSelectedIds

        if (rows !== newRows) {
            val oldRows = rows
            rows = newRows
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldRows.size
                override fun getNewListSize(): Int = newRows.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldRows[oldItemPosition].id == newRows[newItemPosition].id
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldRows[oldItemPosition] == newRows[newItemPosition]
                }
            }).dispatchUpdatesTo(this)
        }

        val changedSelection = previousSelectedIds.symmetricDifference(newSelectedIds)
        changedSelection.forEach { id ->
            val index = rows.indexOfFirst { row -> row.id == id }
            if (index != -1) notifyItemChanged(index)
        }

        if (colorsChanged) {
            notifyItemRangeChanged(0, rows.size)
        }
    }

    fun rowId(position: Int): Long? = rows.getOrNull(position)?.id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ConversationRowItemView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rows[position], selectedIds.contains(rows[position].id), colors)
    }

    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id ?: RecyclerView.NO_ID

    override fun getItemCount(): Int = rows.size

    inner class ViewHolder(
        private val rowView: ConversationRowItemView,
    ) : RecyclerView.ViewHolder(rowView) {

        init {
            rowView.setOnClickListener {
                val row = rows.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                onConversationClick(row.id)
            }
            rowView.setOnLongClickListener {
                val row = rows.getOrNull(bindingAdapterPosition) ?: return@setOnLongClickListener true
                onConversationLongClick(row.id)
                true
            }
        }

        fun bind(row: ConversationRowModel, selected: Boolean, colors: ConversationRecyclerColors) {
            rowView.bind(row, selected, colors)
        }
    }
}

private class ConversationRowItemView(context: Context) : View(context) {
    private val horizontalPadding = 16.dp(context)
    private val minHeightPx = 72.dp(context)
    private val avatarSize = 48.dp(context)
    private val avatarTextGap = 14.dp(context)
    private val textGap = 3.dp(context)
    private val trailingGap = 8.dp(context)
    private val unreadDotSize = 8.dp(context)
    private val pinnedSize = 16.dp(context)
    private val pinnedGap = 8.dp(context)
    private val backgroundPaint = Paint()
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unreadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatarTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 18.sp(context)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16.sp(context)
    }
    private val snippetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14.sp(context)
    }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.sp(context)
    }
    private val pinnedIcon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_push_pin_24)
        ?.mutate()

    private var row: ConversationRowModel? = null
    private var selected = false
    private var colors = ConversationRecyclerColors(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    init {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    fun bind(row: ConversationRowModel, selected: Boolean, colors: ConversationRecyclerColors) {
        this.row = row
        this.selected = selected
        this.colors = colors
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textHeight = lineHeight(titlePaint) + textGap + lineHeight(snippetPaint)
        val desiredHeight = maxOf(minHeightPx, ceil(textHeight + 20.dp(context)).toInt())
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val row = row ?: return
        val width = width.toFloat()
        val height = height.toFloat()
        val unread = row.unread

        backgroundPaint.color = if (selected) colors.selected else colors.background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        val avatarRadius = avatarSize / 2f
        val avatarCx = horizontalPadding + avatarRadius
        val avatarCy = height / 2f
        avatarPaint.color = if (unread) colors.primaryContainer else colors.surfaceContainerHighest
        canvas.drawCircle(avatarCx, avatarCy, avatarRadius, avatarPaint)

        avatarTextPaint.color = if (unread) colors.onPrimaryContainer else colors.onSurfaceVariant
        val avatarBaseline = avatarCy - (avatarTextPaint.descent() + avatarTextPaint.ascent()) / 2f
        canvas.drawText(row.avatarText, avatarCx, avatarBaseline, avatarTextPaint)

        titlePaint.color = colors.onSurface
        titlePaint.isFakeBoldText = unread
        snippetPaint.color = if (unread) colors.onSurface else colors.onSurfaceVariant
        snippetPaint.isFakeBoldText = unread
        datePaint.color = colors.onSurfaceVariant
        datePaint.isFakeBoldText = unread

        val textStart = horizontalPadding + avatarSize + avatarTextGap.toFloat()
        val date = row.timestamp.orEmpty()
        val dateWidth = if (date.isEmpty()) 0f else datePaint.measureText(date)
        val dateStart = width - horizontalPadding - dateWidth
        val titleEnd = if (dateWidth > 0f) dateStart - trailingGap else width - horizontalPadding
        val contentHeight = lineHeight(titlePaint) + textGap + lineHeight(snippetPaint)
        val contentTop = (height - contentHeight) / 2f
        val titleBaseline = contentTop - titlePaint.fontMetrics.ascent
        val snippetBaseline = contentTop + lineHeight(titlePaint) + textGap - snippetPaint.fontMetrics.ascent

        drawSingleLine(canvas, row.title, titlePaint, textStart, titleBaseline, titleEnd - textStart)
        if (date.isNotEmpty()) {
            canvas.drawText(date, dateStart, titleBaseline, datePaint)
        }

        var snippetEnd = width - horizontalPadding
        if (row.unread) {
            snippetEnd -= unreadDotSize
        }
        if (row.pinned) {
            snippetEnd -= pinnedSize + pinnedGap
        }
        drawSingleLine(canvas, row.snippet, snippetPaint, textStart, snippetBaseline, snippetEnd - textStart)

        var trailingX = width - horizontalPadding
        if (row.unread) {
            unreadPaint.color = colors.primary
            val radius = unreadDotSize / 2f
            canvas.drawCircle(trailingX - radius, snippetBaseline - radius, radius, unreadPaint)
            trailingX -= unreadDotSize + pinnedGap
        }
        if (row.pinned) {
            pinnedIcon?.let { icon ->
                DrawableCompat.setTint(icon, colors.onSurfaceVariant)
                val top = (snippetBaseline - pinnedSize + 2.dp(context)).toInt()
                val left = (trailingX - pinnedSize).toInt()
                icon.setBounds(left, top, left + pinnedSize, top + pinnedSize)
                icon.draw(canvas)
            }
        }
    }

    private fun drawSingleLine(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        x: Float,
        baseline: Float,
        maxWidth: Float,
    ) {
        if (maxWidth <= 0f) return

        val ellipsized = TextUtils.ellipsize(
            text,
            paint,
            maxWidth,
            TextUtils.TruncateAt.END,
        )
        canvas.drawText(ellipsized, 0, ellipsized.length, x, baseline, paint)
    }

    private fun lineHeight(paint: Paint): Float {
        return paint.fontMetrics.descent - paint.fontMetrics.ascent
    }
}

private class ConversationSwipeCallback(
    private val context: Context,
    private val adapter: ConversationRowsAdapter,
    private val onConversationSwipe: (Long, Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, 0) {

    private var swipesEnabled = true
    private var swipeRightAction = Preferences.SWIPE_ACTION_NONE
    private var swipeLeftAction = Preferences.SWIPE_ACTION_NONE
    private var colors = ConversationRecyclerColors(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val iconSize = 24.dp(context)
    private val iconMargin = 24.dp(context)
    private val backgroundPaint = Paint()
    private var rightIcon: Drawable? = null
    private var leftIcon: Drawable? = null

    fun configure(
        swipesEnabled: Boolean,
        swipeRightAction: Int,
        swipeLeftAction: Int,
        colors: ConversationRecyclerColors,
    ) {
        if (
            this.swipesEnabled == swipesEnabled &&
            this.swipeRightAction == swipeRightAction &&
            this.swipeLeftAction == swipeLeftAction &&
            this.colors == colors
        ) {
            return
        }

        this.swipesEnabled = swipesEnabled
        this.swipeRightAction = swipeRightAction
        this.swipeLeftAction = swipeLeftAction
        this.colors = colors
        backgroundPaint.color = colors.swipeBackground
        rightIcon = iconForAction(swipeRightAction, colors.swipeIcon)
        leftIcon = iconForAction(swipeLeftAction, colors.swipeIcon)
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        if (!swipesEnabled) return 0

        val swipeFlags =
            (if (swipeRightAction == Preferences.SWIPE_ACTION_NONE) 0 else ItemTouchHelper.RIGHT) or
                (if (swipeLeftAction == Preferences.SWIPE_ACTION_NONE) 0 else ItemTouchHelper.LEFT)

        return makeMovementFlags(0, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            val item = viewHolder.itemView
            val left = if (dX > 0) item.left else item.right + dX.toInt()
            val right = if (dX > 0) item.left + dX.toInt() else item.right
            c.drawRect(left.toFloat(), item.top.toFloat(), right.toFloat(), item.bottom.toFloat(), backgroundPaint)
            drawSwipeIcon(c, if (dX > 0) rightIcon else leftIcon, item.left, item.right, item.top, item.bottom, dX)
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        adapter.rowId(position)?.let { id -> onConversationSwipe(id, direction) }
        if (position != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(position)
        }
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.4f

    private fun drawSwipeIcon(
        canvas: Canvas,
        icon: Drawable?,
        itemLeft: Int,
        itemRight: Int,
        itemTop: Int,
        itemBottom: Int,
        dX: Float,
    ) {
        if (icon == null || abs(dX) < iconMargin + iconSize) return

        val top = itemTop + (itemBottom - itemTop - iconSize) / 2
        val bottom = top + iconSize
        val left = if (dX > 0) {
            itemLeft + iconMargin
        } else {
            itemRight - iconMargin - iconSize
        }

        icon.setBounds(left, top, left + iconSize, bottom)
        icon.draw(canvas)
    }

    private fun iconForAction(action: Int, tint: Int): Drawable? {
        val res = when (action) {
            Preferences.SWIPE_ACTION_ARCHIVE -> R.drawable.ic_archive_white_24dp
            Preferences.SWIPE_ACTION_DELETE -> R.drawable.ic_delete_white_24dp
            Preferences.SWIPE_ACTION_BLOCK -> R.drawable.ic_block_white_24dp
            Preferences.SWIPE_ACTION_CALL -> R.drawable.ic_call_white_24dp
            Preferences.SWIPE_ACTION_READ -> R.drawable.ic_check_white_24dp
            Preferences.SWIPE_ACTION_UNREAD -> R.drawable.ic_markunread_black_24dp
            Preferences.SWIPE_ACTION_SPEAK -> R.drawable.ic_speaker_black_24dp
            else -> null
        }

        return res
            ?.let { ContextCompat.getDrawable(context, it) }
            ?.mutate()
            ?.let { drawable ->
                DrawableCompat.setTint(drawable, tint)
                drawable
            }
    }
}

private fun Set<Long>.symmetricDifference(other: Set<Long>): Set<Long> {
    return (this - other) + (other - this)
}

private fun Int.dp(context: Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}

private fun Int.sp(context: Context): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        toFloat(),
        context.resources.displayMetrics,
    )
}
