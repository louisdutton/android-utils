package dev.octoshrimpy.quik.feature.main

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.recyclerview.widget.ItemTouchHelper
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.repository.SyncRepository
import dev.octoshrimpy.quik.util.Preferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesMainScreen(
    state: MainState,
    conversationRows: List<ConversationRowModel>,
    query: String,
    swipeRightAction: Int,
    swipeLeftAction: Int,
    selectedConversationIds: Set<Long>,
    dateFormatter: DateFormatter,
    onQueryChanged: (String) -> Unit,
    onCompose: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onDrawerChanged: (Boolean) -> Unit,
    onNavigate: (NavItem) -> Unit,
    onSnackbarAction: () -> Unit,
    onConversationClick: (Long) -> Unit,
    onConversationLongClick: (Long) -> Unit,
    onConversationSwipe: (Long, Int) -> Unit,
    onSearchResultClick: (SearchResult) -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onAddContact: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onBlock: () -> Unit,
    onRename: () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = messagesColorScheme(context, darkTheme)

    MaterialTheme(colorScheme = colorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            MessagesMainContent(
                state = state,
                conversationRows = conversationRows,
                query = query,
                swipeRightAction = swipeRightAction,
                swipeLeftAction = swipeLeftAction,
                selectedConversationIds = selectedConversationIds,
                dateFormatter = dateFormatter,
                onQueryChanged = onQueryChanged,
                onCompose = onCompose,
                onHome = onHome,
                onBack = onBack,
                onDrawerChanged = onDrawerChanged,
                onNavigate = onNavigate,
                onSnackbarAction = onSnackbarAction,
                onConversationClick = onConversationClick,
                onConversationLongClick = onConversationLongClick,
                onConversationSwipe = onConversationSwipe,
                onSearchResultClick = onSearchResultClick,
                onSelectAll = onSelectAll,
                onArchive = onArchive,
                onUnarchive = onUnarchive,
                onDelete = onDelete,
                onAddContact = onAddContact,
                onPin = onPin,
                onUnpin = onUnpin,
                onRead = onRead,
                onUnread = onUnread,
                onBlock = onBlock,
                onRename = onRename,
            )
        }
    }
}

private fun messagesColorScheme(
    context: Context,
    darkTheme: Boolean,
): ColorScheme {
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val base = scheme.copy(
        primary = context.materialColor(androidx.appcompat.R.attr.colorPrimary, scheme.primary),
        onPrimary = context.materialColor(MaterialR.attr.colorOnPrimary, scheme.onPrimary),
        primaryContainer = context.materialColor(MaterialR.attr.colorPrimaryContainer, scheme.primaryContainer),
        onPrimaryContainer = context.materialColor(MaterialR.attr.colorOnPrimaryContainer, scheme.onPrimaryContainer),
        secondary = context.materialColor(MaterialR.attr.colorSecondary, scheme.secondary),
        onSecondary = context.materialColor(MaterialR.attr.colorOnSecondary, scheme.onSecondary),
        secondaryContainer = context.materialColor(MaterialR.attr.colorSecondaryContainer, scheme.secondaryContainer),
        onSecondaryContainer = context.materialColor(MaterialR.attr.colorOnSecondaryContainer, scheme.onSecondaryContainer),
        background = context.materialColor(MaterialR.attr.colorSurface, scheme.surface),
        onBackground = context.materialColor(MaterialR.attr.colorOnSurface, scheme.onSurface),
        surface = context.materialColor(MaterialR.attr.colorSurface, scheme.surface),
        onSurface = context.materialColor(MaterialR.attr.colorOnSurface, scheme.onSurface),
        surfaceVariant = context.materialColor(MaterialR.attr.colorSurfaceVariant, scheme.surfaceVariant),
        onSurfaceVariant = context.materialColor(MaterialR.attr.colorOnSurfaceVariant, scheme.onSurfaceVariant),
        surfaceContainer = context.materialColor(MaterialR.attr.colorSurfaceContainer, scheme.surfaceContainer),
        surfaceContainerHigh = context.materialColor(MaterialR.attr.colorSurfaceContainerHigh, scheme.surfaceContainerHigh),
        surfaceContainerHighest = context.materialColor(MaterialR.attr.colorSurfaceContainerHighest, scheme.surfaceContainerHighest),
        outline = context.materialColor(MaterialR.attr.colorOutline, scheme.outline),
        outlineVariant = context.materialColor(MaterialR.attr.colorOutlineVariant, scheme.outlineVariant),
    )

    return if (darkTheme) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color(0xFF101010),
            surfaceContainerHighest = Color(0xFF181818),
            surfaceVariant = Color(0xFF202020),
        )
    } else {
        base
    }
}

private fun Context.materialColor(attr: Int, fallback: Color): Color {
    return Color(resolveThemeColor(attr, fallback.toArgb()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesMainContent(
    state: MainState,
    conversationRows: List<ConversationRowModel>,
    query: String,
    swipeRightAction: Int,
    swipeLeftAction: Int,
    selectedConversationIds: Set<Long>,
    dateFormatter: DateFormatter,
    onQueryChanged: (String) -> Unit,
    onCompose: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
    onDrawerChanged: (Boolean) -> Unit,
    onNavigate: (NavItem) -> Unit,
    onSnackbarAction: () -> Unit,
    onConversationClick: (Long) -> Unit,
    onConversationLongClick: (Long) -> Unit,
    onConversationSwipe: (Long, Int) -> Unit,
    onSearchResultClick: (SearchResult) -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onAddContact: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onBlock: () -> Unit,
    onRename: () -> Unit,
) {
    val drawerState = rememberDrawerState(
        initialValue = if (state.drawerOpen) DrawerValue.Open else DrawerValue.Closed
    )

    LaunchedEffect(state.drawerOpen) {
        if (state.drawerOpen) drawerState.open() else drawerState.close()
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }
            .distinctUntilChanged()
            .collect { onDrawerChanged(it == DrawerValue.Open) }
    }

    BackHandler {
        onBack()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.currentValue == DrawerValue.Open,
        drawerContent = {
            MessagesDrawer(
                state = state,
                onNavigate = onNavigate,
            )
        },
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                MessagesTopBar(
                    state = state,
                    query = query,
                    selectedCount = selectedConversationIds.size,
                    onQueryChanged = onQueryChanged,
                    onHome = onHome,
                    onSelectAll = onSelectAll,
                    onArchive = onArchive,
                    onUnarchive = onUnarchive,
                    onDelete = onDelete,
                    onAddContact = onAddContact,
                    onPin = onPin,
                    onUnpin = onUnpin,
                    onRead = onRead,
                    onUnread = onUnread,
                    onBlock = onBlock,
                    onRename = onRename,
                )
            },
            floatingActionButton = {
                if (state.page is Inbox || state.page is Archived) {
                    FloatingActionButton(
                        onClick = onCompose,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    }
                }
            },
            bottomBar = {
                MessagesStatusBar(
                    state = state,
                    onAction = onSnackbarAction,
                )
            },
        ) { paddingValues ->
            MessagesPage(
                state = state,
                conversationRows = conversationRows,
                paddingValues = paddingValues,
                swipeRightAction = swipeRightAction,
                swipeLeftAction = swipeLeftAction,
                selectedConversationIds = selectedConversationIds,
                dateFormatter = dateFormatter,
                onConversationClick = onConversationClick,
                onConversationLongClick = onConversationLongClick,
                onConversationSwipe = onConversationSwipe,
                onSearchResultClick = onSearchResultClick,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesTopBar(
    state: MainState,
    query: String,
    selectedCount: Int,
    onQueryChanged: (String) -> Unit,
    onHome: () -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onAddContact: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onRead: () -> Unit,
    onUnread: () -> Unit,
    onBlock: () -> Unit,
    onRename: () -> Unit,
) {
    val page = state.page
    val selecting = selectedCount > 0
    val showBack = selecting || page is Searching || page is Archived
    var menuOpen by remember { mutableStateOf(false) }

    CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        navigationIcon = {
            IconButton(onClick = onHome) {
                Icon(
                    if (showBack) Icons.Rounded.ArrowBack else Icons.Rounded.Menu,
                    contentDescription = null,
                )
            }
        },
        title = {
            when {
                selecting -> Text(
                    text = "$selectedCount selected",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                page is Archived -> Text("Archived")

                else -> MessagesSearchField(
                    query = query,
                    onQueryChanged = onQueryChanged,
                )
            }
        },
        actions = {
            if (selecting) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = null)
                }
                if (page is Archived) {
                    IconButton(onClick = onUnarchive) {
                        Icon(Icons.Rounded.Unarchive, contentDescription = null)
                    }
                } else {
                    IconButton(onClick = onArchive) {
                        Icon(Icons.Rounded.Archive, contentDescription = null)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        SelectionMenuItem("Add contact", Icons.Rounded.PersonAdd) {
                            menuOpen = false
                            onAddContact()
                        }
                        SelectionMenuItem("Pin", Icons.Rounded.PushPin) {
                            menuOpen = false
                            onPin()
                        }
                        SelectionMenuItem("Unpin", Icons.Rounded.PushPin) {
                            menuOpen = false
                            onUnpin()
                        }
                        SelectionMenuItem("Mark read", Icons.Rounded.MarkEmailRead) {
                            menuOpen = false
                            onRead()
                        }
                        SelectionMenuItem("Mark unread", Icons.Rounded.MarkEmailUnread) {
                            menuOpen = false
                            onUnread()
                        }
                        SelectionMenuItem("Block", Icons.Rounded.Block) {
                            menuOpen = false
                            onBlock()
                        }
                        SelectionMenuItem("Rename", Icons.Rounded.Sms) {
                            menuOpen = false
                            onRename()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MessagesSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null)
        },
        placeholder = {
            Text("Search messages")
        },
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

@Composable
private fun SelectionMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun MessagesDrawer(
    state: MainState,
    onNavigate: (NavItem) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Messages",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            NavigationDrawerItem(
                label = { Text("Inbox") },
                icon = { Icon(Icons.Rounded.Inbox, contentDescription = null) },
                selected = state.page is Inbox,
                onClick = { onNavigate(NavItem.INBOX) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
            NavigationDrawerItem(
                label = { Text("Archived") },
                icon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                selected = state.page is Archived,
                onClick = { onNavigate(NavItem.ARCHIVED) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                selected = false,
                onClick = { onNavigate(NavItem.SETTINGS) },
            )
        }
    }
}

@Composable
private fun MessagesPage(
    state: MainState,
    conversationRows: List<ConversationRowModel>,
    paddingValues: PaddingValues,
    swipeRightAction: Int,
    swipeLeftAction: Int,
    selectedConversationIds: Set<Long>,
    dateFormatter: DateFormatter,
    onConversationClick: (Long) -> Unit,
    onConversationLongClick: (Long) -> Unit,
    onConversationSwipe: (Long, Int) -> Unit,
    onSearchResultClick: (SearchResult) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        when (val page = state.page) {
            is Inbox -> ConversationList(
                rows = conversationRows,
                emptyText = "Your conversations will appear here",
                swipeRightAction = swipeRightAction,
                swipeLeftAction = swipeLeftAction,
                swipesEnabled = selectedConversationIds.isEmpty(),
                selectedConversationIds = selectedConversationIds,
                onConversationClick = onConversationClick,
                onConversationLongClick = onConversationLongClick,
                onConversationSwipe = onConversationSwipe,
            )

            is Archived -> ConversationList(
                rows = conversationRows,
                emptyText = "Your archived conversations will appear here",
                swipeRightAction = swipeRightAction,
                swipeLeftAction = swipeLeftAction,
                swipesEnabled = selectedConversationIds.isEmpty(),
                selectedConversationIds = selectedConversationIds,
                onConversationClick = onConversationClick,
                onConversationLongClick = onConversationLongClick,
                onConversationSwipe = onConversationSwipe,
            )

            is Searching -> SearchResults(
                page = page,
                dateFormatter = dateFormatter,
                onSearchResultClick = onSearchResultClick,
            )
        }
    }
}

@Composable
private fun SearchResults(
    page: Searching,
    dateFormatter: DateFormatter,
    onSearchResultClick: (SearchResult) -> Unit,
) {
    when {
        page.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        page.data.isNullOrEmpty() -> EmptyState("No matching conversations")

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(
                items = page.data,
                key = { result -> result.conversation.id },
                contentType = { "search_result" },
            ) { result ->
                SearchResultRow(
                    result = result,
                    dateFormatter = dateFormatter,
                    onClick = { onSearchResultClick(result) },
                )
            }
        }
    }
}

@Composable
private fun ConversationList(
    rows: List<ConversationRowModel>,
    emptyText: String,
    swipeRightAction: Int,
    swipeLeftAction: Int,
    swipesEnabled: Boolean,
    selectedConversationIds: Set<Long>,
    onConversationClick: (Long) -> Unit,
    onConversationLongClick: (Long) -> Unit,
    onConversationSwipe: (Long, Int) -> Unit,
) {
    if (rows.isEmpty()) {
        EmptyState(emptyText)
        return
    }

    ConversationRecyclerList(
        rows = rows,
        selectedConversationIds = selectedConversationIds,
        swipeRightAction = swipeRightAction,
        swipeLeftAction = swipeLeftAction,
        swipesEnabled = swipesEnabled,
        onConversationClick = onConversationClick,
        onConversationLongClick = onConversationLongClick,
        onConversationSwipe = onConversationSwipe,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    row: ConversationRowModel,
    selected: Boolean,
    swipeRightAction: Int,
    swipeLeftAction: Int,
    swipesEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: (Int) -> Unit,
) {
    val swipeEnabled = swipesEnabled &&
        (swipeRightAction != Preferences.SWIPE_ACTION_NONE ||
            swipeLeftAction != Preferences.SWIPE_ACTION_NONE)
    val density = LocalDensity.current
    val maxSwipePx = with(density) { 96.dp.toPx() }
    val thresholdPx = with(density) { 56.dp.toPx() }
    val minOffset = if (swipeLeftAction != Preferences.SWIPE_ACTION_NONE) -maxSwipePx else 0f
    val maxOffset = if (swipeRightAction != Preferences.SWIPE_ACTION_NONE) maxSwipePx else 0f
    var offsetX by remember(row.id) { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                enabled = swipeEnabled,
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    offsetX = (offsetX + delta).coerceIn(minOffset, maxOffset)
                },
                onDragStopped = {
                    when {
                        offsetX >= thresholdPx -> onSwipe(ItemTouchHelper.RIGHT)
                        offsetX <= -thresholdPx -> onSwipe(ItemTouchHelper.LEFT)
                    }
                    offsetX = 0f
                },
            ),
    ) {
        if (offsetX != 0f) {
            SwipeActionBackground(
                direction = if (offsetX > 0) ItemTouchHelper.RIGHT else ItemTouchHelper.LEFT,
                swipeRightAction = swipeRightAction,
                swipeLeftAction = swipeLeftAction,
            )
        }

        ConversationRowContent(
            row = row,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRowContent(
    row: ConversationRowModel,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowColor = when {
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.background
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        color = rowColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(text = row.avatarText, unread = row.unread)
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    row.timestamp?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.snippet,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.unread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.pinned) {
                        Icon(
                            Icons.Rounded.PushPin,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.unread) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeActionBackground(
    direction: Int,
    swipeRightAction: Int,
    swipeLeftAction: Int,
) {
    val action = when (direction) {
        ItemTouchHelper.RIGHT -> swipeRightAction
        ItemTouchHelper.LEFT -> swipeLeftAction
        else -> Preferences.SWIPE_ACTION_NONE
    }
    val icon = swipeActionIcon(action)
    val alignment = when (direction) {
        ItemTouchHelper.LEFT -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun swipeActionIcon(action: Int): ImageVector? {
    return when (action) {
        Preferences.SWIPE_ACTION_ARCHIVE -> Icons.Rounded.Archive
        Preferences.SWIPE_ACTION_DELETE -> Icons.Rounded.Delete
        Preferences.SWIPE_ACTION_BLOCK -> Icons.Rounded.Block
        Preferences.SWIPE_ACTION_CALL -> Icons.Rounded.Call
        Preferences.SWIPE_ACTION_READ -> Icons.Rounded.MarkEmailRead
        Preferences.SWIPE_ACTION_UNREAD -> Icons.Rounded.MarkEmailUnread
        Preferences.SWIPE_ACTION_SPEAK -> Icons.Rounded.VolumeUp
        else -> null
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    dateFormatter: DateFormatter,
    onClick: () -> Unit,
) {
    val conversation = result.conversation
    val title = conversation.getTitle().ifBlank { "Unknown" }
    val snippet = when {
        conversation.draft.isNotEmpty() -> "Draft: ${conversation.draft}"
        conversation.me -> "You: ${conversation.snippet.orEmpty()}"
        else -> conversation.snippet.orEmpty()
    }

    ConversationRow(
        row = ConversationRowModel(
            id = conversation.id,
            title = title,
            avatarText = title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            snippet = snippet,
            timestamp = conversation.date.takeIf { it > 0 }?.let(dateFormatter::getConversationTimestamp),
            unread = conversation.unread,
            pinned = conversation.pinned,
            recipients = conversation.recipients,
        ),
        selected = false,
        swipeRightAction = Preferences.SWIPE_ACTION_NONE,
        swipeLeftAction = Preferences.SWIPE_ACTION_NONE,
        swipesEnabled = false,
        onClick = onClick,
        onLongClick = onClick,
        onSwipe = {},
    )
}

@Composable
private fun Avatar(text: String, unread: Boolean) {
    val color = if (unread) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val textColor = if (unread) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Immutable
data class ConversationRowModel(
    val id: Long,
    val title: String,
    val avatarText: String,
    val snippet: String,
    val timestamp: String?,
    val unread: Boolean,
    val pinned: Boolean,
    val recipients: List<Recipient>,
)

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessagesStatusBar(
    state: MainState,
    onAction: () -> Unit,
) {
    when (val syncing = state.syncing) {
        is SyncRepository.SyncProgress.Running -> SyncingBar(
            label = "Syncing messages...",
            progress = syncing.progress,
            max = syncing.max,
            indeterminate = syncing.indeterminate,
        )

        is SyncRepository.SyncProgress.ParsingEmojis -> SyncingBar(
            label = "Parsing emoji reactions...",
            progress = syncing.progress,
            max = syncing.max,
            indeterminate = syncing.indeterminate,
        )

        SyncRepository.SyncProgress.Idle -> PermissionBar(
            state = state,
            onAction = onAction,
        )
    }
}

@Composable
private fun SyncingBar(
    label: String,
    progress: Int,
    max: Int,
    indeterminate: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (indeterminate || max == 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress.toFloat() / max.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PermissionBar(
    state: MainState,
    onAction: () -> Unit,
) {
    val message = when {
        !state.defaultSms -> "Set Messages as your default SMS app"
        !state.smsPermission -> "Allow SMS access to send and view messages"
        !state.contactPermission -> "Allow contacts access to show names and avatars"
        !state.notificationPermission -> "Allow notifications for incoming messages"
        else -> null
    } ?: return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column {
            HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAction) {
                    Text(if (!state.defaultSms) "Change" else "Allow")
                }
            }
        }
    }
}
