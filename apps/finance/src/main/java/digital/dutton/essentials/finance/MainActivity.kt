package digital.dutton.essentials.finance

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import digital.dutton.essentials.finance.data.BillingBreakdownRow
import digital.dutton.essentials.finance.data.BillingCategory
import digital.dutton.essentials.finance.data.BillingPeriod
import digital.dutton.essentials.finance.data.BillingSummary
import digital.dutton.essentials.finance.data.CategoryBillingTotal
import digital.dutton.essentials.finance.data.ConnectionState
import digital.dutton.essentials.finance.data.FinanceConnectionStore
import digital.dutton.essentials.finance.data.FinanceProviders
import digital.dutton.essentials.finance.data.OctopusAccountSnapshot
import digital.dutton.essentials.finance.data.OctopusCredentials
import digital.dutton.essentials.finance.data.ProviderCardState
import digital.dutton.essentials.finance.data.ProviderKind
import digital.dutton.essentials.finance.data.UtilityAccount
import digital.dutton.essentials.finance.network.OctopusClient
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BillsApp() }
    }
}

enum class BillsTab {
    Overview,
    Providers,
}

private enum class BillingDisplayMetric {
    Usage,
    Cost,
}

private data class ChartBar(
    val label: String,
    val value: Double,
)

data class BillsUiState(
    val selectedTab: BillsTab = BillsTab.Overview,
    val selectedBillingPeriod: BillingPeriod = BillingPeriod.Month,
    val selectedCategory: BillingCategory? = null,
    val providerCards: List<ProviderCardState> = emptyList(),
    val billAccounts: List<UtilityAccount> = emptyList(),
    val billingSummary: BillingSummary? = null,
    val octopusAccountNumber: String? = null,
    val octopusSnapshot: OctopusAccountSnapshot? = null,
    val octopusLastRefreshMillis: Long? = null,
    val isRefreshingOctopus: Boolean = false,
    val isLoadingBilling: Boolean = false,
    val error: String? = null,
)

class BillsViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionStore = FinanceConnectionStore(application.applicationContext)
    private val octopusClient = OctopusClient()
    private val _uiState = MutableStateFlow(BillsUiState())
    val uiState: StateFlow<BillsUiState> = _uiState.asStateFlow()

    init {
        if (loadStoredConnections()) {
            refreshOctopus()
        }
    }

    fun selectTab(tab: BillsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectBillingPeriod(period: BillingPeriod) {
        if (_uiState.value.selectedBillingPeriod == period) return
        _uiState.update { it.copy(selectedBillingPeriod = period) }
        refreshBillingSummary()
    }

    fun selectCategory(category: BillingCategory?) {
        if (_uiState.value.selectedCategory == category) return
        _uiState.update { it.copy(selectedCategory = category) }
        refreshBillingSummary()
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun saveOctopusConnection(
        apiKeyInput: String,
        accountNumberInput: String,
    ) {
        val existing = connectionStore.getOctopusCredentials()
        val apiKey = apiKeyInput.trim().ifBlank { existing?.apiKey.orEmpty() }
        val accountNumber = accountNumberInput.trim()

        if (apiKey.isBlank() || accountNumber.isBlank()) {
            _uiState.update { it.copy(error = "Octopus API key and account number are required.") }
            return
        }

        connectionStore.saveOctopusCredentials(
            OctopusCredentials(
                apiKey = apiKey,
                accountNumber = accountNumber,
            ),
        )
        _uiState.update {
            it.copy(
                octopusAccountNumber = accountNumber,
                isRefreshingOctopus = true,
                isLoadingBilling = true,
                providerCards = providerCards(
                    octopusConnected = true,
                    octopusSnapshot = it.octopusSnapshot,
                    isRefreshingOctopus = true,
                ),
                error = null,
            )
        }
        refreshOctopus()
    }

    fun disconnectOctopus() {
        connectionStore.clearOctopusCredentials()
        _uiState.update {
            it.copy(
                billAccounts = emptyList(),
                selectedCategory = null,
                octopusAccountNumber = null,
                octopusSnapshot = null,
                octopusLastRefreshMillis = null,
                billingSummary = null,
                isRefreshingOctopus = false,
                isLoadingBilling = false,
                providerCards = providerCards(
                    octopusConnected = false,
                    octopusSnapshot = null,
                    isRefreshingOctopus = false,
                ),
                error = null,
            )
        }
    }

    fun refreshOctopus() {
        val credentials = connectionStore.getOctopusCredentials()
        if (credentials == null) {
            _uiState.update { it.copy(error = "Connect Octopus before refreshing.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isRefreshingOctopus = true,
                    isLoadingBilling = true,
                    providerCards = providerCards(
                        octopusConnected = true,
                        octopusSnapshot = it.octopusSnapshot,
                        isRefreshingOctopus = true,
                    ),
                )
            }

            runCatching {
                val snapshot = octopusClient.fetchAccount(credentials)
                val currentState = _uiState.value
                val billingSummary = octopusClient.fetchBillingSummary(
                    credentials = credentials,
                    snapshot = snapshot,
                    period = currentState.selectedBillingPeriod,
                    categoryFilter = currentState.selectedCategory,
                )
                snapshot to billingSummary
            }
                .onSuccess { (snapshot, billingSummary) ->
                    val refreshedAt = System.currentTimeMillis()
                    connectionStore.setOctopusLastRefreshMillis(refreshedAt)
                    _uiState.update {
                        it.copy(
                            billAccounts = listOf(snapshot.toUtilityAccount()),
                            billingSummary = billingSummary,
                            octopusAccountNumber = snapshot.accountNumber,
                            octopusSnapshot = snapshot,
                            octopusLastRefreshMillis = refreshedAt,
                            isRefreshingOctopus = false,
                            isLoadingBilling = false,
                            providerCards = providerCards(
                                octopusConnected = true,
                                octopusSnapshot = snapshot,
                                isRefreshingOctopus = false,
                            ),
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            octopusAccountNumber = credentials.accountNumber,
                            isRefreshingOctopus = false,
                            isLoadingBilling = false,
                            providerCards = providerCards(
                                octopusConnected = true,
                                octopusSnapshot = it.octopusSnapshot,
                                isRefreshingOctopus = false,
                            ),
                            error = error.message ?: "Unable to refresh Octopus.",
                        )
                    }
                }
        }
    }

    private fun refreshBillingSummary() {
        val credentials = connectionStore.getOctopusCredentials()
        val snapshot = _uiState.value.octopusSnapshot
        if (credentials == null || snapshot == null) {
            _uiState.update { it.copy(billingSummary = null, isLoadingBilling = false) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val period = _uiState.value.selectedBillingPeriod
            val category = _uiState.value.selectedCategory
            _uiState.update { it.copy(isLoadingBilling = true) }

            runCatching {
                octopusClient.fetchBillingSummary(
                    credentials = credentials,
                    snapshot = snapshot,
                    period = period,
                    categoryFilter = category,
                )
            }
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            billingSummary = summary,
                            isLoadingBilling = false,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoadingBilling = false,
                            error = error.message ?: "Unable to load billing usage.",
                        )
                    }
                }
        }
    }

    private fun loadStoredConnections(): Boolean {
        val octopusCredentials = connectionStore.getOctopusCredentials()
        val lastRefreshMillis = connectionStore.getOctopusLastRefreshMillis()

        _uiState.update {
            it.copy(
                octopusAccountNumber = octopusCredentials?.accountNumber,
                octopusLastRefreshMillis = lastRefreshMillis,
                providerCards = providerCards(
                    octopusConnected = octopusCredentials != null,
                    octopusSnapshot = null,
                    isRefreshingOctopus = false,
                ),
            )
        }

        return octopusCredentials != null
    }

    private fun providerCards(
        octopusConnected: Boolean,
        octopusSnapshot: OctopusAccountSnapshot?,
        isRefreshingOctopus: Boolean,
    ): List<ProviderCardState> {
        val octopusStatus = when {
            isRefreshingOctopus -> "Refreshing"
            octopusSnapshot != null -> "Connected"
            octopusConnected -> "Connected"
            else -> "Available"
        }
        val octopusDetail = when {
            octopusSnapshot != null -> buildString {
                append("${octopusSnapshot.propertyCount} properties")
                append(", ${octopusSnapshot.electricityMeterCount} electricity meters")
                append(", ${octopusSnapshot.gasMeterCount} gas meters")
            }
            octopusConnected -> "Credentials saved. Refresh to load account data."
            else -> FinanceProviders.Octopus.summary
        }

        return listOf(
            ProviderCardState(
                provider = FinanceProviders.Octopus,
                connectionState = if (octopusConnected) ConnectionState.Connected else ConnectionState.Available,
                status = octopusStatus,
                detail = octopusDetail,
                primaryAction = if (octopusConnected) "Edit" else "Connect",
                secondaryAction = if (octopusConnected) "Disconnect" else null,
            ),
            ProviderCardState(
                provider = FinanceProviders.SouthWestWater,
                connectionState = ConnectionState.Planned,
                status = "Planned",
                detail = FinanceProviders.SouthWestWater.summary,
                primaryAction = null,
            ),
            ProviderCardState(
                provider = FinanceProviders.Broadband,
                connectionState = ConnectionState.Planned,
                status = "Planned",
                detail = FinanceProviders.Broadband.summary,
                primaryAction = null,
            ),
            ProviderCardState(
                provider = FinanceProviders.CouncilTax,
                connectionState = ConnectionState.Planned,
                status = "Planned",
                detail = FinanceProviders.CouncilTax.summary,
                primaryAction = null,
            ),
        )
    }
}

@Composable
private fun BillsApp() {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BillsScreen()
        }
    }
}

@Composable
private fun BillsScreen(viewModel: BillsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showOctopusDialog by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.selectedTab == BillsTab.Overview,
                    onClick = { viewModel.selectTab(BillsTab.Overview) },
                    icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                    label = { Text("Overview") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == BillsTab.Providers,
                    onClick = { viewModel.selectTab(BillsTab.Providers) },
                    icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                    label = { Text("Providers") },
                )
            }
        },
    ) { padding ->
        when (state.selectedTab) {
            BillsTab.Overview -> OverviewContent(
                state = state,
                contentPadding = padding,
                onOpenProviders = { viewModel.selectTab(BillsTab.Providers) },
                onRefreshOctopus = viewModel::refreshOctopus,
                onSelectPeriod = viewModel::selectBillingPeriod,
                onSelectCategory = viewModel::selectCategory,
            )
            BillsTab.Providers -> ProvidersContent(
                state = state,
                contentPadding = padding,
                onConnectOctopus = { showOctopusDialog = true },
                onDisconnectOctopus = viewModel::disconnectOctopus,
                onRefreshOctopus = viewModel::refreshOctopus,
            )
        }
    }

    if (showOctopusDialog) {
        OctopusConnectionDialog(
            accountNumber = state.octopusAccountNumber.orEmpty(),
            hasExistingCredentials = state.octopusAccountNumber != null,
            onDismiss = { showOctopusDialog = false },
            onSave = { apiKey, accountNumber ->
                showOctopusDialog = false
                viewModel.saveOctopusConnection(apiKey, accountNumber)
            },
        )
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Bills") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun OverviewContent(
    state: BillsUiState,
    contentPadding: PaddingValues,
    onOpenProviders: () -> Unit,
    onRefreshOctopus: () -> Unit,
    onSelectPeriod: (BillingPeriod) -> Unit,
    onSelectCategory: (BillingCategory?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BillingDashboard(
                state = state,
                onOpenProviders = onOpenProviders,
                onRefreshOctopus = onRefreshOctopus,
                onSelectPeriod = onSelectPeriod,
                onSelectCategory = onSelectCategory,
            )
        }
    }
}

@Composable
private fun BillingDashboard(
    state: BillsUiState,
    onOpenProviders: () -> Unit,
    onRefreshOctopus: () -> Unit,
    onSelectPeriod: (BillingPeriod) -> Unit,
    onSelectCategory: (BillingCategory?) -> Unit,
) {
    var displayMetric by remember { mutableStateOf(BillingDisplayMetric.Usage) }
    val summary = state.billingSummary

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PeriodSelector(
            selectedPeriod = state.selectedBillingPeriod,
            onSelectPeriod = onSelectPeriod,
        )

        CategoryFilterSelector(
            state = state,
            onSelectCategory = onSelectCategory,
        )

        if (state.isLoadingBilling) {
            LoadingBillingRow()
        } else {
            when {
                summary == null -> InlineEmptyStateRow(
                    icon = Icons.Rounded.Bolt,
                    title = "No usage loaded",
                    detail = "Connect Octopus to show billing usage and estimated costs.",
                    onClick = onOpenProviders,
                )
                summary.rows.isEmpty() -> InlineEmptyStateRow(
                    icon = Icons.Rounded.Bolt,
                    title = "No usage in this period",
                    detail = summary.notice ?: "Try a different period or refresh Octopus.",
                    onClick = onRefreshOctopus,
                )
                else -> BillingGraphContent(
                    summary = summary,
                    displayMetric = displayMetric,
                    onDisplayMetricChange = { displayMetric = it },
                )
            }
        }

        summary?.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: BillingPeriod,
    onSelectPeriod: (BillingPeriod) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BillingPeriod.entries.forEach { period ->
            FilterChip(
                selected = selectedPeriod == period,
                onClick = { onSelectPeriod(period) },
                label = { Text(period.label()) },
            )
        }
    }
}

@Composable
private fun CategoryFilterSelector(
    state: BillsUiState,
    onSelectCategory: (BillingCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.selectedCategory == null,
            onClick = { onSelectCategory(null) },
            label = { Text("All") },
        )
        FilterChip(
            selected = state.selectedCategory == BillingCategory.Electricity,
            onClick = { onSelectCategory(BillingCategory.Electricity) },
            enabled = (state.octopusSnapshot?.electricityMeterCount ?: 0) > 0,
            label = { Text("Electricity") },
        )
        FilterChip(
            selected = state.selectedCategory == BillingCategory.Gas,
            onClick = { onSelectCategory(BillingCategory.Gas) },
            enabled = (state.octopusSnapshot?.gasMeterCount ?: 0) > 0,
            label = { Text("Gas") },
        )
        FilterChip(
            selected = false,
            onClick = {},
            enabled = false,
            label = { Text("Water") },
        )
        FilterChip(
            selected = false,
            onClick = {},
            enabled = false,
            label = { Text("Broadband") },
        )
        FilterChip(
            selected = false,
            onClick = {},
            enabled = false,
            label = { Text("Council tax") },
        )
    }
}

@Composable
private fun BillingGraphContent(
    summary: BillingSummary,
    displayMetric: BillingDisplayMetric,
    onDisplayMetricChange: (BillingDisplayMetric) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
                Column {
                    Text(
                        text = summary.headlineValue(displayMetric),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (displayMetric == BillingDisplayMetric.Usage) "USED" else "ESTIMATED",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            MetricToggle(
                displayMetric = displayMetric,
                onDisplayMetricChange = onDisplayMetricChange,
            )
        }

        BillingBarsChart(
            summary = summary,
            displayMetric = displayMetric,
        )

        ServiceTotalsPanel(summary)
    }
}

@Composable
private fun MetricToggle(
    displayMetric: BillingDisplayMetric,
    onDisplayMetricChange: (BillingDisplayMetric) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            MetricToggleButton(
                label = "kWh",
                selected = displayMetric == BillingDisplayMetric.Usage,
                onClick = { onDisplayMetricChange(BillingDisplayMetric.Usage) },
            )
            MetricToggleButton(
                label = "Cost",
                selected = displayMetric == BillingDisplayMetric.Cost,
                onClick = { onDisplayMetricChange(BillingDisplayMetric.Cost) },
            )
        }
    }
}

@Composable
private fun MetricToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val selectedText = MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedText = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (selected) selectedColor else Color.Transparent,
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) selectedText else unselectedText,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BillingBarsChart(
    summary: BillingSummary,
    displayMetric: BillingDisplayMetric,
) {
    val bars = summary.chartBars(displayMetric)
    val maxValue = bars.maxOfOrNull { it.value }?.takeIf { it > 0.0 } ?: 1.0
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val barTop = MaterialTheme.colorScheme.primary
    val barBottom = MaterialTheme.colorScheme.tertiary
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.height(222.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = maxValue.axisLabel(displayMetric),
                style = MaterialTheme.typography.bodySmall,
                color = axisText,
            )
            Text(
                text = (maxValue / 2.0).axisLabel(displayMetric),
                style = MaterialTheme.typography.bodySmall,
                color = axisText,
            )
            Text(
                text = displayMetric.unitLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = axisText,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(222.dp),
            ) {
                val chartStartX = 0f
                val chartEndX = size.width
                val chartTop = 8.dp.toPx()
                val chartBottom = size.height - 4.dp.toPx()
                val chartHeight = chartBottom - chartTop
                val dash = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(8.dp.toPx(), 8.dp.toPx()),
                )

                listOf(chartTop, chartTop + chartHeight / 2f, chartBottom).forEach { y ->
                    drawLine(
                        color = gridColor.copy(alpha = 0.8f),
                        start = Offset(chartStartX, y),
                        end = Offset(chartEndX, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }
                drawLine(
                    color = axisColor.copy(alpha = 0.6f),
                    start = Offset(chartStartX, chartTop),
                    end = Offset(chartStartX, chartBottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = axisColor.copy(alpha = 0.6f),
                    start = Offset(chartStartX, chartBottom),
                    end = Offset(chartEndX, chartBottom),
                    strokeWidth = 1.dp.toPx(),
                )

                if (bars.isNotEmpty()) {
                    val slotWidth = size.width / bars.size
                    val barWidth = (slotWidth * 0.34f).coerceIn(3.dp.toPx(), 11.dp.toPx())
                    bars.forEachIndexed { index, bar ->
                        val heightFraction = (bar.value / maxValue).toFloat().coerceIn(0.02f, 1f)
                        val barHeight = chartHeight * heightFraction
                        val left = index * slotWidth + (slotWidth - barWidth) / 2f
                        val top = chartBottom - barHeight
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(barTop, barBottom),
                                startY = top,
                                endY = chartBottom,
                            ),
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                summary.axisLabels().forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = axisText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServiceTotalsPanel(summary: BillingSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        summary.categoryTotals.forEach { total ->
            CategoryTotalRow(total)
        }
    }
}

@Composable
private fun BillingSummaryMetrics(state: BillsUiState) {
    val summary = state.billingSummary
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile(
            modifier = Modifier.weight(1f),
            label = "Estimated",
            value = summary?.estimatedCostPence.formatCost(),
        )
        MetricTile(
            modifier = Modifier.weight(1f),
            label = "Usage",
            value = summary?.totalUsageKwh.formatUsage(),
        )
        MetricTile(
            modifier = Modifier.weight(1f),
            label = "Updated",
            value = state.octopusLastRefreshMillis?.formatTimestamp() ?: "Never",
        )
    }
}

@Composable
private fun LoadingBillingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Loading usage and estimated costs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InlineEmptyStateRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BillingBreakdownList(summary: BillingSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Breakdown",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        summary.categoryTotals.forEach { total ->
            CategoryTotalRow(total)
        }
        summary.rows.forEach { row ->
            BillingBreakdownRowItem(row)
        }
    }
}

@Composable
private fun CategoryTotalRow(total: CategoryBillingTotal) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = total.categoryName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = total.usageKwh.formatUsage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = total.estimatedCostPence.formatCost(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BillingBreakdownRowItem(row: BillingBreakdownRow) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = row.estimatedCostPence.formatCost(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = row.usageKwh.formatUsage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier.height(76.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProvidersContent(
    state: BillsUiState,
    contentPadding: PaddingValues,
    onConnectOctopus: () -> Unit,
    onDisconnectOctopus: () -> Unit,
    onRefreshOctopus: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.providerCards.forEach { provider ->
            item {
                ProviderCard(
                    state = provider,
                    isRefreshing = provider.provider == FinanceProviders.Octopus && state.isRefreshingOctopus,
                    onPrimaryAction = {
                        if (provider.provider == FinanceProviders.Octopus) {
                            onConnectOctopus()
                        }
                    },
                    onSecondaryAction = {
                        if (provider.provider == FinanceProviders.Octopus) {
                            onDisconnectOctopus()
                        }
                    },
                    onRefresh = {
                        if (provider.provider == FinanceProviders.Octopus) {
                            onRefreshOctopus()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    state: ProviderCardState,
    isRefreshing: Boolean,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onRefresh: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProviderIcon(state.provider.kind)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(state.connectionState)
            }

            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.provider == FinanceProviders.Octopus && state.connectionState == ConnectionState.Connected) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RefreshButtonContent(
                        isRefreshing = isRefreshing,
                        text = "Refresh",
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                state.secondaryAction?.let { label ->
                    TextButton(onClick = onSecondaryAction) {
                        Icon(Icons.Rounded.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(label)
                    }
                }
                state.primaryAction?.let { label ->
                    Button(onClick = onPrimaryAction) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(state: ConnectionState) {
    val icon = when (state) {
        ConnectionState.Connected -> Icons.Rounded.CheckCircle
        ConnectionState.Available -> Icons.Rounded.Link
        ConnectionState.NeedsSetup -> Icons.Rounded.Settings
        ConnectionState.Planned -> Icons.Rounded.PendingActions
    }
    val label = when (state) {
        ConnectionState.Connected -> "Live"
        ConnectionState.Available -> "Ready"
        ConnectionState.NeedsSetup -> "Setup"
        ConnectionState.Planned -> "Later"
    }

    AssistChip(
        onClick = {},
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        label = { Text(label) },
    )
}

@Composable
private fun ProviderIcon(kind: ProviderKind) {
    val icon = when (kind) {
        ProviderKind.Energy -> Icons.Rounded.Bolt
        ProviderKind.Water -> Icons.Rounded.WaterDrop
        ProviderKind.Connectivity -> Icons.Rounded.Wifi
        ProviderKind.LocalAuthority -> Icons.Rounded.Home
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun EmptyStateRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlannedProviderRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    EmptyStateRow(
        icon = icon,
        title = title,
        detail = detail,
        onClick = onClick,
    )
}

@Composable
private fun UtilityAccountRow(account: UtilityAccount) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Bolt, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = account.providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = account.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = account.status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun RefreshButtonContent(
    isRefreshing: Boolean,
    text: String,
) {
    if (isRefreshing) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
    } else {
        Icon(Icons.Rounded.Refresh, contentDescription = null)
    }
    Spacer(modifier = Modifier.size(8.dp))
    Text(text)
}

@Composable
private fun OctopusConnectionDialog(
    accountNumber: String,
    hasExistingCredentials: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var account by remember(accountNumber) { mutableStateOf(accountNumber) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Octopus Energy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Account number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(if (hasExistingCredentials) "API key (leave blank to keep)" else "API key")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(apiKey, account) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun OctopusAccountSnapshot.toUtilityAccount(): UtilityAccount {
    val meterSummary = buildList {
        if (electricityMeterCount > 0) add("$electricityMeterCount electricity")
        if (gasMeterCount > 0) add("$gasMeterCount gas")
    }.joinToString(", ").ifBlank { "No smart meters found" }

    val tariffs = tariffCodes
        .take(3)
        .joinToString(", ")
        .ifBlank { "No tariff codes returned" }

    return UtilityAccount(
        providerName = FinanceProviders.Octopus.name,
        displayName = "Account $accountNumber",
        status = "Live",
        detail = "$meterSummary. $tariffs",
    )
}

private fun Long.formatTimestamp(): String {
    return DateTimeFormatter.ofPattern("d MMM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))
}

private fun BillingPeriod.label(): String {
    return when (this) {
        BillingPeriod.Day -> "Day"
        BillingPeriod.Week -> "Week"
        BillingPeriod.Month -> "Month"
        BillingPeriod.Year -> "Year"
    }
}

private fun BillingPeriod.defaultRangeLabel(): String {
    return when (this) {
        BillingPeriod.Day -> "Today"
        BillingPeriod.Week -> "This week"
        BillingPeriod.Month -> "This month"
        BillingPeriod.Year -> "This year"
    }
}

private fun BillingDisplayMetric.unitLabel(): String {
    return when (this) {
        BillingDisplayMetric.Usage -> "kWh"
        BillingDisplayMetric.Cost -> "GBP"
    }
}

private fun BillingSummary.chartBars(displayMetric: BillingDisplayMetric): List<ChartBar> {
    return rows.map { row ->
        ChartBar(
            label = row.label,
            value = when (displayMetric) {
                BillingDisplayMetric.Usage -> row.usageKwh
                BillingDisplayMetric.Cost -> row.estimatedCostPence?.div(100.0) ?: 0.0
            },
        )
    }
}

private fun BillingSummary.axisLabels(): List<String> {
    if (period == BillingPeriod.Day) {
        return listOf("00", "06", "12", "18", "23")
    }

    val labels = rows.map { it.label }
    return when {
        labels.isEmpty() -> emptyList()
        labels.size <= 4 -> labels
        else -> listOf(
            labels.first(),
            labels[labels.size / 2],
            labels.last(),
        )
    }
}

private fun BillingSummary.headlineValue(displayMetric: BillingDisplayMetric): String {
    return when (displayMetric) {
        BillingDisplayMetric.Usage -> totalUsageKwh.formatUsageCompact()
        BillingDisplayMetric.Cost -> estimatedCostPence.formatCost()
    }
}

private fun Double.axisLabel(displayMetric: BillingDisplayMetric): String {
    return when (displayMetric) {
        BillingDisplayMetric.Usage -> when {
            this >= 10.0 -> String.format(Locale.UK, "%.0f", this)
            this >= 1.0 -> String.format(Locale.UK, "%.1f", this)
            else -> String.format(Locale.UK, "%.2f", this)
        }
        BillingDisplayMetric.Cost -> NumberFormat.getCurrencyInstance(Locale.UK).format(this)
    }
}

private fun Double.formatUsageCompact(): String {
    val value = when {
        this >= 100.0 -> String.format(Locale.UK, "%,.0f", this)
        this >= 10.0 -> String.format(Locale.UK, "%,.1f", this)
        else -> String.format(Locale.UK, "%,.2f", this)
    }
    return "${value}kWh"
}

private fun Double?.formatCost(): String {
    return this?.let { pence ->
        NumberFormat.getCurrencyInstance(Locale.UK).format(pence / 100.0)
    } ?: "Pending"
}

private fun Double?.formatUsage(): String {
    val usage = this ?: return "No data"
    return when {
        usage >= 100.0 -> String.format(Locale.UK, "%,.0f kWh", usage)
        usage >= 10.0 -> String.format(Locale.UK, "%,.1f kWh", usage)
        else -> String.format(Locale.UK, "%,.2f kWh", usage)
    }
}
