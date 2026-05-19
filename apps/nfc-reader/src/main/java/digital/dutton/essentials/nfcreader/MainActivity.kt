package digital.dutton.essentials.nfcreader

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val ReaderFlags =
    NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NFC_BARCODE or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

private const val SavedCardsFileName = "saved_cards.json"
private const val ReaderPreferences = "nfc_reader"
private const val ActiveEmulationCardKey = "active_emulation_card_id"

private val ScanTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val SavedTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val readerState = MutableStateFlow(NfcReaderState())
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = getSystemService(NfcManager::class.java)?.defaultAdapter
        refreshAdapterState()
        reloadSavedCards()

        setContent {
            val state by readerState.collectAsState()
            NfcReaderApp(
                state = state,
                onClear = {
                    readerState.update {
                        it.copy(lastReading = null, lastError = null, isReading = false)
                    }
                },
                onSaveReading = ::saveReading,
                onOpenSavedCard = { savedCard ->
                    readerState.update {
                        it.copy(
                            lastReading = savedCard.reading,
                            lastError = null,
                            isReading = false,
                        )
                    }
                },
                onDeleteSavedCard = ::deleteSavedCard,
                onSetActiveEmulation = ::setActiveEmulation,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdapterState()
        enableReaderMode()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        readerState.update {
            it.copy(
                isReading = true,
                lastError = null,
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                tag.toReading()
            }.onSuccess { reading ->
                readerState.update {
                    it.copy(
                        availability = currentAvailability(),
                        isReading = false,
                        lastReading = reading,
                        lastError = null,
                    )
                }
            }.onFailure { error ->
                readerState.update {
                    it.copy(
                        availability = currentAvailability(),
                        isReading = false,
                        lastError = error.message ?: "Unable to read this tag.",
                    )
                }
            }
        }
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        adapter.enableReaderMode(this, this, ReaderFlags, options)
    }

    private fun refreshAdapterState() {
        readerState.update { it.copy(availability = currentAvailability()) }
    }

    private fun reloadSavedCards() {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedCards = loadSavedCards()
            val activeId = getActiveEmulationCardId()
                ?.takeIf { id -> savedCards.any { it.id == id && it.reading.ndefMessageHex != null } }
            if (activeId == null) {
                clearActiveEmulationCardId()
            }
            readerState.update {
                it.copy(
                    savedCards = savedCards,
                    activeEmulationCardId = activeId,
                )
            }
        }
    }

    private fun saveReading(
        name: String,
        reading: TagReading,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedCards = loadSavedCards().toMutableList()
            savedCards.add(
                0,
                SavedCard(
                    id = UUID.randomUUID().toString(),
                    name = name.ifBlank { "Untitled card" },
                    savedAt = LocalDateTime.now().format(SavedTimeFormatter),
                    reading = reading,
                ),
            )
            writeSavedCards(savedCards)
            readerState.update { it.copy(savedCards = savedCards) }
        }
    }

    private fun deleteSavedCard(cardId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedCards = loadSavedCards().filterNot { it.id == cardId }
            writeSavedCards(savedCards)
            val activeId = getActiveEmulationCardId()
            val nextActiveId = activeId?.takeUnless { it == cardId }
            if (nextActiveId == null) {
                clearActiveEmulationCardId()
            }
            readerState.update {
                it.copy(
                    savedCards = savedCards,
                    activeEmulationCardId = nextActiveId,
                )
            }
        }
    }

    private fun setActiveEmulation(cardId: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedCards = loadSavedCards()
            val activeId = cardId
                ?.takeIf { id -> savedCards.any { it.id == id && it.reading.ndefMessageHex != null } }
            setActiveEmulationCardId(activeId)
            readerState.update {
                it.copy(
                    savedCards = savedCards,
                    activeEmulationCardId = activeId,
                )
            }
        }
    }

    private fun currentAvailability(): NfcAvailability {
        val adapter = nfcAdapter ?: return NfcAvailability.Unavailable
        return if (adapter.isEnabled) {
            NfcAvailability.Enabled
        } else {
            NfcAvailability.Disabled
        }
    }
}

private enum class NfcAvailability {
    Enabled,
    Disabled,
    Unavailable,
}

private data class NfcReaderState(
    val availability: NfcAvailability = NfcAvailability.Unavailable,
    val isReading: Boolean = false,
    val lastReading: TagReading? = null,
    val savedCards: List<SavedCard> = emptyList(),
    val activeEmulationCardId: String? = null,
    val lastError: String? = null,
)

private data class TagReading(
    val idHex: String,
    val discoveredAt: String,
    val technologies: List<String>,
    val sections: List<InfoSection>,
    val ndefRecords: List<NdefRecordInfo>,
    val ndefMessageHex: String?,
)

private data class SavedCard(
    val id: String,
    val name: String,
    val savedAt: String,
    val reading: TagReading,
)

private data class InfoSection(
    val title: String,
    val rows: List<InfoRow>,
)

private data class InfoRow(
    val label: String,
    val value: String,
)

private data class NdefRecordInfo(
    val title: String,
    val rows: List<InfoRow>,
    val preview: String?,
)

@Composable
private fun NfcReaderApp(
    state: NfcReaderState,
    onClear: () -> Unit,
    onSaveReading: (String, TagReading) -> Unit,
    onOpenSavedCard: (SavedCard) -> Unit,
    onDeleteSavedCard: (String) -> Unit,
    onSetActiveEmulation: (String?) -> Unit,
) {
    val context = LocalContext.current

    NfcReaderTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NfcReaderScreen(
                state = state,
                onClear = onClear,
                onSaveReading = onSaveReading,
                onOpenSavedCard = onOpenSavedCard,
                onDeleteSavedCard = onDeleteSavedCard,
                onSetActiveEmulation = onSetActiveEmulation,
                onOpenNfcSettings = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                },
            )
        }
    }
}

@Composable
private fun NfcReaderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NfcReaderScreen(
    state: NfcReaderState,
    onClear: () -> Unit,
    onSaveReading: (String, TagReading) -> Unit,
    onOpenSavedCard: (SavedCard) -> Unit,
    onDeleteSavedCard: (String) -> Unit,
    onSetActiveEmulation: (String?) -> Unit,
    onOpenNfcSettings: () -> Unit,
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    val reading = state.lastReading

    if (showSaveDialog && reading != null) {
        SaveReadingDialog(
            reading = reading,
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                onSaveReading(name, reading)
                showSaveDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFC Reader") },
                actions = {
                    if (state.lastReading != null || state.lastError != null) {
                        TextButton(onClick = onClear) {
                            Text("Clear")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusPanel(
                    state = state,
                    onOpenNfcSettings = onOpenNfcSettings,
                )
            }

            if (state.lastError != null) {
                item {
                    ErrorPanel(message = state.lastError)
                }
            }

            if (state.savedCards.isNotEmpty()) {
                item {
                    SavedCardsPanel(
                        savedCards = state.savedCards,
                        activeEmulationCardId = state.activeEmulationCardId,
                        onOpenSavedCard = onOpenSavedCard,
                        onDeleteSavedCard = onDeleteSavedCard,
                        onSetActiveEmulation = onSetActiveEmulation,
                    )
                }
            }

            if (reading == null) {
                item {
                    WaitingPanel()
                }
            } else {
                item {
                    TagHeader(
                        reading = reading,
                        onSave = { showSaveDialog = true },
                    )
                }
                items(reading.sections) { section ->
                    InfoSectionCard(section = section)
                }
                items(reading.ndefRecords) { record ->
                    NdefRecordCard(record = record)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(
    state: NfcReaderState,
    onOpenNfcSettings: () -> Unit,
) {
    val (title, detail, color) = when {
        state.availability == NfcAvailability.Unavailable -> Triple(
            "NFC unavailable",
            "This device does not report NFC hardware.",
            MaterialTheme.colorScheme.error,
        )
        state.availability == NfcAvailability.Disabled -> Triple(
            "NFC is off",
            "Turn on NFC to scan cards.",
            MaterialTheme.colorScheme.tertiary,
        )
        state.isReading -> Triple(
            "Reading card",
            "Processing the detected tag.",
            MaterialTheme.colorScheme.primary,
        )
        else -> Triple(
            "Ready",
            state.lastReading?.let { "Last scan at ${it.discoveredAt}." } ?: "Waiting for a card.",
            MaterialTheme.colorScheme.primary,
        )
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.availability == NfcAvailability.Disabled) {
                Button(onClick = onOpenNfcSettings) {
                    Text("Settings")
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WaitingPanel() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "No card scanned",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Hold a supported NFC card near the device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagHeader(reading: TagReading) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Card identifier",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            SelectionContainer {
                Text(
                    text = reading.idHex.ifBlank { "No identifier exposed" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = reading.technologies.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun InfoSectionCard(section: InfoSection) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            section.rows.forEachIndexed { index, row ->
                InfoRowView(row = row)
                if (index != section.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun NdefRecordCard(record: NdefRecordInfo) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            record.preview?.let { preview ->
                SelectionContainer {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            record.rows.forEachIndexed { index, row ->
                InfoRowView(row = row)
                if (index != record.rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoRowView(row: InfoRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        SelectionContainer(
            modifier = Modifier.weight(0.58f),
        ) {
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun Tag.toReading(): TagReading {
    val tagId = id ?: ByteArray(0)
    val techNames = techList.map(::shortTechName).sorted()
    val sections = buildList {
        add(
            InfoSection(
                title = "Scan",
                rows = listOf(
                    InfoRow("Identifier bytes", tagId.size.toString()),
                    InfoRow("Technologies", techNames.joinToString(", ")),
                    InfoRow("Mode", "Read-only inspection"),
                    InfoRow("Protected sectors", "Not attempted"),
                ),
            ),
        )
        addTechnologySections(this@toReading)
    }

    val (ndefSection, records) = readNdef()
    return TagReading(
        idHex = tagId.toHex(),
        discoveredAt = LocalTime.now().format(ScanTimeFormatter),
        technologies = techNames,
        sections = sections + ndefSection,
        ndefRecords = records,
    )
}

private fun MutableList<InfoSection>.addTechnologySections(tag: Tag) {
    NfcA.get(tag)?.let { nfcA ->
        add(
            InfoSection(
                title = "NFC-A",
                rows = listOf(
                    InfoRow("ATQA", nfcA.atqa.toHex()),
                    InfoRow("SAK", nfcA.sak.toHexByte()),
                    InfoRow("Max transceive", "${nfcA.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }

    NfcB.get(tag)?.let { nfcB ->
        add(
            InfoSection(
                title = "NFC-B",
                rows = listOf(
                    InfoRow("Application data", nfcB.applicationData.toHex()),
                    InfoRow("Protocol info", nfcB.protocolInfo.toHex()),
                    InfoRow("Max transceive", "${nfcB.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }

    NfcF.get(tag)?.let { nfcF ->
        add(
            InfoSection(
                title = "NFC-F",
                rows = listOf(
                    InfoRow("Manufacturer", nfcF.manufacturer.toHex()),
                    InfoRow("System code", nfcF.systemCode.toHex()),
                    InfoRow("Max transceive", "${nfcF.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }

    NfcV.get(tag)?.let { nfcV ->
        add(
            InfoSection(
                title = "NFC-V",
                rows = listOf(
                    InfoRow("DSFID", nfcV.dsfId.toHexByte()),
                    InfoRow("Response flags", nfcV.responseFlags.toHexByte()),
                    InfoRow("Max transceive", "${nfcV.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }

    IsoDep.get(tag)?.let { isoDep ->
        add(
            InfoSection(
                title = "ISO-DEP",
                rows = listOfNotNull(
                    isoDep.historicalBytes?.let { InfoRow("Historical bytes", it.toHex()) },
                    isoDep.hiLayerResponse?.let { InfoRow("Higher-layer response", it.toHex()) },
                    InfoRow(
                        "Extended APDU",
                        if (isoDep.isExtendedLengthApduSupported) "Supported" else "Not supported",
                    ),
                    InfoRow("Max transceive", "${isoDep.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }

    MifareClassic.get(tag)?.let { mifare ->
        add(
            InfoSection(
                title = "MIFARE Classic",
                rows = listOf(
                    InfoRow("Type", mifare.classicTypeName()),
                    InfoRow("Size", "${mifare.size} bytes"),
                    InfoRow("Sectors", mifare.sectorCount.toString()),
                    InfoRow("Blocks", mifare.blockCount.toString()),
                    InfoRow("Max transceive", "${mifare.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }

    MifareUltralight.get(tag)?.let { ultralight ->
        add(
            InfoSection(
                title = "MIFARE Ultralight",
                rows = listOf(
                    InfoRow("Type", ultralight.ultralightTypeName()),
                    InfoRow("Max transceive", "${ultralight.maxTransceiveLength} bytes"),
                ),
            ),
        )
    }
}

private fun Tag.readNdef(): Pair<InfoSection, List<NdefRecordInfo>> {
    val ndef = Ndef.get(this)
    if (ndef == null) {
        val status = if (NdefFormatable.get(this) != null) {
            "Formatable"
        } else {
            "Not exposed"
        }
        return InfoSection(
            title = "NDEF",
            rows = listOf(InfoRow("Status", status)),
        ) to emptyList()
    }

    var readError: String? = null
    var message: NdefMessage? = ndef.cachedNdefMessage

    runCatching {
        ndef.connect()
        message = ndef.ndefMessage ?: message
    }.onFailure { error ->
        readError = error.message ?: error::class.java.simpleName
    }
    runCatching {
        ndef.close()
    }

    val records = message
        ?.records
        ?.mapIndexed { index, record -> record.toInfo(index) }
        .orEmpty()

    val rows = buildList {
        add(InfoRow("Status", "Readable"))
        add(InfoRow("Type", ndef.type))
        add(InfoRow("Max size", "${ndef.maxSize} bytes"))
        add(InfoRow("Writable", if (ndef.isWritable) "Yes" else "No"))
        add(InfoRow("Can make read-only", if (ndef.canMakeReadOnly()) "Yes" else "No"))
        add(InfoRow("Records", records.size.toString()))
        readError?.let { add(InfoRow("Read warning", it)) }
    }

    return InfoSection(
        title = "NDEF",
        rows = rows,
    ) to records
}

private fun NdefRecord.toInfo(index: Int): NdefRecordInfo {
    val decoded = decodeKnownPayload()
    val rows = buildList {
        add(InfoRow("TNF", tnfName(tnf)))
        if (type.isNotEmpty()) {
            add(InfoRow("Type", type.toAsciiOrHex()))
        }
        if (id.isNotEmpty()) {
            add(InfoRow("Record id", id.toHex()))
        }
        add(InfoRow("Payload size", "${payload.size} bytes"))
        if (decoded == null && payload.isNotEmpty()) {
            add(InfoRow("Payload preview", payload.toPreviewHex()))
        }
    }

    return NdefRecordInfo(
        title = "NDEF record ${index + 1}",
        rows = rows,
        preview = decoded,
    )
}

private fun NdefRecord.decodeKnownPayload(): String? {
    if (tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_TEXT)) {
        return decodeTextPayload(payload)
    }

    if (tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_URI)) {
        return decodeUriPayload(payload)
    }

    return payload.toPrintableTextPreview()
}

private fun decodeTextPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null

    val status = payload[0].toInt() and 0xFF
    val languageLength = status and 0x3F
    val textStart = 1 + languageLength
    if (textStart > payload.size) return null

    val charset = if ((status and 0x80) == 0) {
        Charsets.UTF_8
    } else {
        Charsets.UTF_16
    }

    return payload.decodeFrom(textStart, charset)
}

private fun decodeUriPayload(payload: ByteArray): String? {
    if (payload.isEmpty()) return null

    val prefix = UriPrefixes[payload[0].toInt() and 0xFF] ?: ""
    val suffix = payload.decodeFrom(1, Charsets.UTF_8) ?: return null
    return prefix + suffix
}

private fun ByteArray.decodeFrom(
    startIndex: Int,
    charset: Charset,
): String? {
    if (startIndex >= size) return ""
    return runCatching {
        String(this, startIndex, size - startIndex, charset).trim()
    }.getOrNull()?.ifBlank { null }
}

private fun ByteArray.toPrintableTextPreview(): String? {
    if (isEmpty()) return null

    val printableBytes = count { byte ->
        val value = byte.toInt() and 0xFF
        value == 0x09 || value == 0x0A || value == 0x0D || value in 0x20..0x7E
    }
    if (printableBytes < size * 0.85f) return null

    return runCatching {
        String(this, Charsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
    }.getOrNull()?.ifBlank { null }
}

private fun shortTechName(techName: String): String =
    techName.substringAfterLast('.')

private fun tnfName(tnf: Short): String = when (tnf) {
    NdefRecord.TNF_EMPTY -> "Empty"
    NdefRecord.TNF_WELL_KNOWN -> "Well-known"
    NdefRecord.TNF_MIME_MEDIA -> "MIME media"
    NdefRecord.TNF_ABSOLUTE_URI -> "Absolute URI"
    NdefRecord.TNF_EXTERNAL_TYPE -> "External type"
    NdefRecord.TNF_UNKNOWN -> "Unknown"
    NdefRecord.TNF_UNCHANGED -> "Unchanged"
    else -> "0x${tnf.toInt().toString(16)}"
}

private fun MifareClassic.classicTypeName(): String = when (type) {
    MifareClassic.TYPE_CLASSIC -> "Classic"
    MifareClassic.TYPE_PLUS -> "Plus"
    MifareClassic.TYPE_PRO -> "Pro"
    else -> "Unknown"
}

private fun MifareUltralight.ultralightTypeName(): String = when (type) {
    MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight"
    MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"
    else -> "Unknown"
}

private fun ByteArray.toAsciiOrHex(): String {
    val ascii = toPrintableTextPreview()
    return ascii ?: toHex()
}

private fun ByteArray.toHex(): String =
    joinToString(" ") { byte -> byte.toHexByte() }

private fun ByteArray.toPreviewHex(limit: Int = 32): String {
    val shown = take(limit).joinToString(" ") { byte -> byte.toHexByte() }
    return if (size > limit) "$shown ..." else shown
}

private fun Byte.toHexByte(): String =
    (toInt() and 0xFF).toHexByte()

private fun Short.toHexByte(): String =
    (toInt() and 0xFF).toHexByte()

private fun Int.toHexByte(): String =
    "0x" + toString(16).uppercase().padStart(2, '0')

private val UriPrefixes = mapOf(
    0x00 to "",
    0x01 to "http://www.",
    0x02 to "https://www.",
    0x03 to "http://",
    0x04 to "https://",
    0x05 to "tel:",
    0x06 to "mailto:",
    0x07 to "ftp://anonymous:anonymous@",
    0x08 to "ftp://ftp.",
    0x09 to "ftps://",
    0x0A to "sftp://",
    0x0B to "smb://",
    0x0C to "nfs://",
    0x0D to "ftp://",
    0x0E to "dav://",
    0x0F to "news:",
    0x10 to "telnet://",
    0x11 to "imap:",
    0x12 to "rtsp://",
    0x13 to "urn:",
    0x14 to "pop:",
    0x15 to "sip:",
    0x16 to "sips:",
    0x17 to "tftp:",
    0x18 to "btspp://",
    0x19 to "btl2cap://",
    0x1A to "btgoep://",
    0x1B to "tcpobex://",
    0x1C to "irdaobex://",
    0x1D to "file://",
    0x1E to "urn:epc:id:",
    0x1F to "urn:epc:tag:",
    0x20 to "urn:epc:pat:",
    0x21 to "urn:epc:raw:",
    0x22 to "urn:epc:",
    0x23 to "urn:nfc:",
)
