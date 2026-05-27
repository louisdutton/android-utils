package digital.dutton.essentials.scores

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

data class NormalizedMusicXml(
    val bytes: ByteArray,
    val warnings: List<ScoreWarning>,
)

object MusicXmlFiles {
    private const val MaxEntryBytes = 30 * 1024 * 1024
    private const val MaxArchiveBytes = 60 * 1024 * 1024

    fun normalize(
        bytes: ByteArray,
        mimeType: String,
        displayName: String,
    ): NormalizedMusicXml {
        val normalizedMime = ScoreMimeTypes.normalize(mimeType, displayName)
        return if (ScoreMimeTypes.isCompressedMusicXml(normalizedMime) || bytes.looksLikeZip()) {
            readCompressedMusicXml(bytes)
        } else {
            NormalizedMusicXml(bytes = bytes, warnings = validate(bytes))
        }
    }

    fun validate(bytes: ByteArray): List<ScoreWarning> {
        val document = runCatching { parseDocument(bytes) }.getOrElse { error ->
            return listOf(
                ScoreWarning(
                    pageIndex = null,
                    code = "musicxml_parse_failed",
                    message = error.message ?: "MusicXML could not be parsed.",
                ),
            )
        }

        val rootName = document.documentElement?.tagName.orEmpty()
        val warnings = mutableListOf<ScoreWarning>()
        if (rootName != "score-partwise" && rootName != "score-timewise") {
            warnings += ScoreWarning(
                pageIndex = null,
                code = "musicxml_unexpected_root",
                message = "Expected score-partwise or score-timewise, found $rootName.",
            )
        }
        warnings += rhythmWarnings(document)
        return warnings
    }

    fun title(bytes: ByteArray): String? {
        val document = runCatching { parseDocument(bytes) }.getOrNull() ?: return null
        return document.firstText("movement-title")
            ?: document.firstText("work-title")
            ?: document.firstText("credit-words")
    }

    fun estimatedPageCount(bytes: ByteArray): Int {
        val document = runCatching { parseDocument(bytes) }.getOrNull() ?: return 1
        val measures = document.getElementsByTagName("measure").length.coerceAtLeast(1)
        return ((measures + 11) / 12).coerceIn(1, 100)
    }

    private fun readCompressedMusicXml(bytes: ByteArray): NormalizedMusicXml {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.trim('/')
                if (entry.isDirectory || name.isBlank()) {
                    zip.closeEntry()
                    continue
                }

                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    totalBytes += read
                    if (entryBytes > MaxEntryBytes || totalBytes > MaxArchiveBytes) {
                        throw IllegalArgumentException("Compressed MusicXML archive is too large.")
                    }
                    output.write(buffer, 0, read)
                }
                entries[name] = output.toByteArray()
                zip.closeEntry()
            }
        }

        val rootPath = entries["META-INF/container.xml"]?.let(::rootPathFromContainer)
        val scorePath = rootPath?.takeIf(entries::containsKey)
            ?: entries.keys.firstOrNull { name ->
                val lower = name.lowercase()
                lower.endsWith(".musicxml") || lower.endsWith(".xml")
            }
            ?: throw IllegalArgumentException("Compressed MusicXML archive does not contain a score XML document.")

        val musicXml = entries.getValue(scorePath)
        return NormalizedMusicXml(bytes = musicXml, warnings = validate(musicXml))
    }

    private fun rootPathFromContainer(bytes: ByteArray): String? {
        val document = runCatching { parseDocument(bytes) }.getOrNull() ?: return null
        val rootFiles = document.getElementsByTagName("rootfile")
        for (index in 0 until rootFiles.length) {
            val element = rootFiles.item(index) as? Element ?: continue
            val mediaType = element.getAttribute("media-type")
            if (mediaType.isBlank() || mediaType == ScoreMimeTypes.MusicXml) {
                return element.getAttribute("full-path").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun rhythmWarnings(document: Document): List<ScoreWarning> {
        val warnings = mutableListOf<ScoreWarning>()
        val parts = document.getElementsByTagName("part")
        for (partIndex in 0 until parts.length) {
            val part = parts.item(partIndex) as? Element ?: continue
            var divisions = 1
            var beats: Int? = null
            var beatType: Int? = null
            val measures = part.directChildren("measure")

            for (measure in measures) {
                measure.directChildren("attributes").forEach { attributes ->
                    attributes.firstDirectText("divisions")?.toIntOrNull()?.let { divisions = it.coerceAtLeast(1) }
                    attributes.directChildren("time").firstOrNull()?.let { time ->
                        beats = time.firstDirectText("beats")?.toIntOrNull()
                        beatType = time.firstDirectText("beat-type")?.toIntOrNull()
                    }
                }

                val expected = expectedMeasureDuration(divisions, beats, beatType) ?: continue
                val voiceDurations = linkedMapOf<String, Int>()
                measure.directChildren("note").forEach { note ->
                    if (note.firstDirectChild("chord") != null || note.firstDirectChild("grace") != null) return@forEach
                    val duration = note.firstDirectText("duration")?.toIntOrNull() ?: return@forEach
                    val voice = note.firstDirectText("voice") ?: "1"
                    voiceDurations[voice] = (voiceDurations[voice] ?: 0) + duration
                }

                if (voiceDurations.size == 1) {
                    val actual = voiceDurations.values.single()
                    if (actual != expected) {
                        val measureNumber = measure.getAttribute("number").ifBlank { "?" }
                        warnings += ScoreWarning(
                            pageIndex = null,
                            code = "measure_duration_mismatch",
                            message = "Measure $measureNumber has duration $actual but expected $expected.",
                        )
                    }
                }
            }
        }
        return warnings
    }

    private fun expectedMeasureDuration(
        divisions: Int,
        beats: Int?,
        beatType: Int?,
    ): Int? {
        val safeBeats = beats ?: return null
        val safeBeatType = beatType ?: return null
        if (safeBeats <= 0 || safeBeatType <= 0) return null
        return safeBeats * divisions * 4 / safeBeatType
    }

    private fun parseDocument(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeatureSafely(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }
}

private fun ByteArray.looksLikeZip(): Boolean {
    return size >= 2 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte()
}

private fun Document.firstText(tagName: String): String? {
    val nodes = getElementsByTagName(tagName)
    for (index in 0 until nodes.length) {
        val text = nodes.item(index)?.textContent?.trim()
        if (!text.isNullOrBlank()) return text
    }
    return null
}

private fun Element.directChildren(tagName: String): List<Element> {
    return buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val child = nodes.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                add(child as Element)
            }
        }
    }
}

private fun Element.firstDirectChild(tagName: String): Element? {
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val child = nodes.item(index)
        if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
            return child as Element
        }
    }
    return null
}

private fun Element.firstDirectText(tagName: String): String? {
    return firstDirectChild(tagName)?.textContent?.trim()?.takeIf { it.isNotBlank() }
}

private fun DocumentBuilderFactory.setFeatureSafely(
    feature: String,
    enabled: Boolean,
) {
    runCatching { setFeature(feature, enabled) }
}
