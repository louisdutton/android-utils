package digital.dutton.essentials.scores

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class NativeScoreOmr(
    private val context: Context,
) {
    suspend fun recognize(
        title: String,
        pages: List<ScorePageBitmap>,
        lyricText: List<ScoreLyricText>,
        progress: suspend (pageIndex: Int, pageCount: Int, message: String) -> Unit,
    ): OmrResult {
        val modelFiles = copyModelAssets()
        val recognizedStaves = mutableListOf<List<HomrSymbol>>()
        val warnings = mutableListOf<ScoreWarning>()
        var inferredStaffsPerSystem: Int? = null

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val segmentationModel = runCatching {
            SegmentationModel(modelFiles.segmentation, threads)
        }.getOrElse { error ->
            warnings += ScoreWarning(
                pageIndex = null,
                code = "omr_segmentation_unavailable",
                message = "Segmentation model could not be loaded; using row-profile staff detection. ${error.message.orEmpty()}",
            )
            null
        }
        val model = Staff2ScoreModel()
        try {
            model.load(
                modelFiles.encoder.absolutePath,
                modelFiles.decoder.absolutePath,
                threads,
            )

            for (page in pages) {
                val pageLyricText = lyricText.filter { it.pageIndex == page.index }
                progress(page.index, pages.size, "Segmenting page ${page.index + 1}")
                val omrBitmap = page.bitmap.enhancedForOmr()
                val cropResult = try {
                    StaffCropper.findStaffCrops(omrBitmap, segmentationModel)
                } finally {
                    if (omrBitmap !== page.bitmap) omrBitmap.recycle()
                }
                cropResult.warning?.let { message ->
                    warnings += ScoreWarning(
                        pageIndex = page.index,
                        code = "omr_segmentation_fallback",
                        message = message,
                    )
                }

                progress(page.index, pages.size, "Finding staves on page ${page.index + 1}")
                val crops = cropResult.crops
                val staffsPerSystem = inferredStaffsPerSystem ?: inferStaffsPerSystem(crops.size).also {
                    inferredStaffsPerSystem = it
                }
                if (crops.isEmpty()) {
                    warnings += ScoreWarning(
                        pageIndex = page.index,
                        code = "omr_no_staffs",
                        message = "No staff lines were detected on page ${page.index + 1}.",
                    )
                    continue
                }

                crops.forEachIndexed { staffIndex, crop ->
                    progress(
                        page.index,
                        pages.size,
                        "Recognizing staff ${staffIndex + 1} of ${crops.size} on page ${page.index + 1}",
                    )
                    val input = crop.toTransformerInput()
                    val tokens = model.run(input)
                    if (!tokens.reachedEndOfSequence()) {
                        warnings += ScoreWarning(
                            pageIndex = page.index,
                            code = "omr_staff_unbounded",
                            message = "Staff ${staffIndex + 1} on page ${page.index + 1} did not produce a bounded symbol sequence.",
                        )
                        crop.bitmap.recycle()
                        return@forEachIndexed
                    }
                    val decodedSymbols = HomrVocabulary.decode(tokens)
                        .filterNot { it.position == "lower" }
                    val symbols = if (shouldAttachLyrics(staffIndex, staffsPerSystem)) {
                        decodedSymbols.withLyrics(
                            pageLyricText.lyricsForStaff(
                                crop = crop,
                                nextCrop = crops.getOrNull(staffIndex + 1),
                            ),
                        )
                    } else {
                        decodedSymbols
                    }
                    if (symbols.none { it.rhythm.startsWith("note") || it.rhythm.startsWith("rest") }) {
                        warnings += ScoreWarning(
                            pageIndex = page.index,
                            code = "omr_staff_empty",
                            message = "Staff ${staffIndex + 1} on page ${page.index + 1} did not produce notes.",
                    )
                    } else {
                        recognizedStaves += symbols + HomrSymbol("barline", ".", ".", -1, "upper")
                    }
                    crop.bitmap.recycle()
                }
            }
        } finally {
            runCatching { segmentationModel?.close() }
            runCatching { model.close() }
        }

        if (recognizedStaves.isEmpty()) {
            return OmrResult(
                musicXml = null,
                pageCount = pages.size,
                warnings = warnings.ifEmpty {
                    listOf(
                        ScoreWarning(
                            pageIndex = null,
                            code = "omr_no_symbols",
                            message = "No playable symbols were recognized.",
                        ),
                    )
                },
            )
        }

        val nonEmptyParts = buildLogicalParts(
            recognizedStaves = recognizedStaves,
            staffsPerSystem = inferredStaffsPerSystem ?: 1,
        )
        val musicXml = HomrMusicXmlWriter.writeParts(
            title = title,
            parts = nonEmptyParts,
        )
        val qualityWarnings = OmrQuality.lowConfidenceWarnings(
            musicXml = musicXml,
            sourcePageCount = pages.size,
        )
        if (qualityWarnings.isNotEmpty()) {
            return OmrResult(
                musicXml = null,
                pageCount = pages.size,
                warnings = warnings + qualityWarnings,
            )
        }

        return OmrResult(
            musicXml = musicXml,
            pageCount = pages.size,
            warnings = warnings,
        )
    }

    private fun copyModelAssets(): ModelFiles {
        val directory = File(context.noBackupFilesDir, "scores-omr-models").apply { mkdirs() }
        return ModelFiles(
            segmentation = copyAsset("omr/segmentation.tflite", File(directory, "segmentation.tflite")),
            encoder = copyAsset("omr/encoder.tflite", File(directory, "encoder.tflite")),
            decoder = copyAsset("omr/decoder.onnx", File(directory, "decoder.onnx")),
        )
    }

    private fun copyAsset(
        assetPath: String,
        destination: File,
    ): File {
        if (destination.exists() && destination.length() > 0L) return destination
        destination.outputStream().use { output ->
            context.assets.open(assetPath).use { input -> input.copyTo(output) }
        }
        return destination
    }

    private data class ModelFiles(
        val segmentation: File,
        val encoder: File,
        val decoder: File,
    )

    private fun inferStaffsPerSystem(staffCount: Int): Int {
        return when {
            staffCount >= 6 && staffCount % 3 == 0 -> 3
            staffCount >= 4 && staffCount % 2 == 0 -> 2
            else -> 1
        }
    }

    private fun buildLogicalParts(
        recognizedStaves: List<List<HomrSymbol>>,
        staffsPerSystem: Int,
    ): List<HomrPart> {
        if (staffsPerSystem == 3) {
            val voice = mutableListOf<HomrSymbol>()
            val pianoRight = mutableListOf<HomrSymbol>()
            val pianoLeft = mutableListOf<HomrSymbol>()
            recognizedStaves.forEachIndexed { index, symbols ->
                when (index % 3) {
                    0 -> voice += symbols
                    1 -> pianoRight += symbols
                    else -> pianoLeft += symbols
                }
            }
            return listOf(
                HomrPart(name = "Voice", symbols = voice),
                HomrPart(name = "Piano", symbols = pianoRight, additionalStaves = listOf(pianoLeft)),
            )
        }

        if (staffsPerSystem == 2) {
            val upper = mutableListOf<HomrSymbol>()
            val lower = mutableListOf<HomrSymbol>()
            recognizedStaves.forEachIndexed { index, symbols ->
                if (index % 2 == 0) upper += symbols else lower += symbols
            }
            return listOf(
                HomrPart(name = "Piano", symbols = upper, additionalStaves = listOf(lower)),
            )
        }

        return listOf(
            HomrPart(name = "Score", symbols = recognizedStaves.flatten()),
        )
    }

    private fun shouldAttachLyrics(
        staffIndex: Int,
        staffsPerSystem: Int,
    ): Boolean {
        return staffsPerSystem == 1 || (staffsPerSystem >= 3 && staffIndex % staffsPerSystem == 0)
    }

    private fun List<ScoreLyricText>.lyricsForStaff(
        crop: StaffCrop,
        nextCrop: StaffCrop?,
    ): List<HomrLyric> {
        if (isEmpty()) return emptyList()
        val source = crop.source
        val lower = source.top + source.height() * 0.52f
        val upper = nextCrop?.source?.top?.toFloat()
            ?: (source.bottom + source.height() * 0.8f)
        val horizontalMargin = source.height() * 0.35f
        val candidates = filter { text ->
            text.yPx >= lower &&
                text.yPx <= upper &&
                text.xPx >= source.left - horizontalMargin &&
                text.xPx <= source.right + horizontalMargin
        }
        if (candidates.isEmpty()) return emptyList()

        val grouped = candidates.sortedBy { it.yPx }.fold(mutableListOf<MutableList<ScoreLyricText>>()) { groups, item ->
            val current = groups.lastOrNull()
            if (current == null || kotlin.math.abs(current.map { it.yPx }.average() - item.yPx) > source.height() * 0.12f) {
                groups += mutableListOf(item)
            } else {
                current += item
            }
            groups
        }
        val lyricLine = grouped.maxByOrNull { it.size }.orEmpty().sortedBy { it.xPx }
        return lyricLine.flatMap { it.toLyrics() }
    }

    private fun List<HomrSymbol>.withLyrics(lyrics: List<HomrLyric>): List<HomrSymbol> {
        if (lyrics.isEmpty()) return this
        var lyricIndex = 0
        var chordNext = false
        return map { symbol ->
            if (symbol.rhythm == "chord") {
                chordNext = true
                return@map symbol
            }
            val isChordNote = chordNext
            val withLyric = if (symbol.isLyricNote() && !isChordNote && lyricIndex < lyrics.size) {
                symbol.copy(lyric = lyrics[lyricIndex++])
            } else {
                symbol
            }
            if (symbol.rhythm.startsWith("note") || symbol.rhythm.startsWith("rest")) {
                chordNext = false
            }
            withLyric
        }
    }

    private fun HomrSymbol.isLyricNote(): Boolean {
        return rhythm.startsWith("note") && pitch != "." && pitch != "_"
    }

    private fun ScoreLyricText.toLyrics(): List<HomrLyric> {
        return text
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .flatMap { token ->
                val parts = token.split('-').filter { it.isNotBlank() }
                if (parts.size <= 1) {
                    listOf(HomrLyric(token))
                } else {
                    parts.mapIndexed { index, part ->
                        HomrLyric(
                            text = part,
                            syllabic = when (index) {
                                0 -> "begin"
                                parts.lastIndex -> "end"
                                else -> "middle"
                            },
                        )
                    }
                }
            }
    }
}

private class SegmentationModel(
    modelFile: File,
    threads: Int,
) : AutoCloseable {
    private val model = LiteRtModel()

    init {
        model.load(modelFile.absolutePath, threads)
    }

    fun staffMask(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val gray = bitmap.toGray()
        val votes = ByteArray(width * height)
        val weights = ByteArray(width * height)
        val startsY = patchStarts(height)
        val startsX = patchStarts(width)
        val input = ByteBuffer
            .allocateDirect(4 * 3 * WindowSize * WindowSize)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (startY in startsY) {
            for (startX in startsX) {
                input.clear()
                repeat(3) {
                    for (patchY in 0 until WindowSize) {
                        val sourceY = startY + patchY
                        for (patchX in 0 until WindowSize) {
                            val sourceX = startX + patchX
                            val value = if (sourceY in 0 until height && sourceX in 0 until width) {
                                gray[sourceY * width + sourceX].toFloat()
                            } else {
                                255f
                            }
                            input.put(value)
                        }
                    }
                }
                input.flip()

                val labels = model.runInt(input)
                for (patchY in 0 until WindowSize) {
                    val sourceY = startY + patchY
                    if (sourceY !in 0 until height) continue
                    val outputOffset = patchY * WindowSize
                    val destinationOffset = sourceY * width
                    for (patchX in 0 until WindowSize) {
                        val sourceX = startX + patchX
                        if (sourceX !in 0 until width) continue
                        val destinationIndex = destinationOffset + sourceX
                        if (labels[outputOffset + patchX] == StaffLabel) {
                            votes[destinationIndex] = (votes[destinationIndex] + 1).toByte()
                        }
                        weights[destinationIndex] = (weights[destinationIndex] + 1).toByte()
                    }
                }
            }
        }

        val mask = ByteArray(width * height)
        for (index in mask.indices) {
            val weight = weights[index].toInt()
            if (weight > 0 && votes[index].toInt() * 2 >= weight) {
                mask[index] = 1
            }
        }
        return mask
    }

    override fun close() {
        model.close()
    }

    private fun patchStarts(length: Int): List<Int> {
        if (length <= WindowSize) return listOf(0)
        val starts = mutableListOf<Int>()
        var next = 0
        while (true) {
            val start = min(next, length - WindowSize)
            if (starts.lastOrNull() != start) starts += start
            if (start == length - WindowSize) break
            next += StepSize
        }
        return starts
    }

    private companion object {
        const val WindowSize = 320
        const val StepSize = 320
        const val StaffLabel = 4L
    }
}

private fun Bitmap.toGray(): IntArray {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    val gray = IntArray(pixels.size)
    for (index in pixels.indices) {
        val color = pixels[index]
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        gray[index] = ((red * 30 + green * 59 + blue * 11) / 100)
    }
    return gray
}

private fun Bitmap.enhancedForOmr(): Bitmap {
    return runCatching {
        if (!OpenCVLoader.initLocal()) return this
        val rgba = Mat()
        val gray = Mat()
        val enhanced = Mat()
        val outputRgba = Mat()
        try {
            Utils.bitmapToMat(this, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(1.0, Size(8.0, 8.0))
            try {
                clahe.apply(gray, enhanced)
            } finally {
                clahe.collectGarbage()
            }
            Imgproc.cvtColor(enhanced, outputRgba, Imgproc.COLOR_GRAY2RGBA)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { output ->
                Utils.matToBitmap(outputRgba, output)
            }
        } finally {
            rgba.release()
            gray.release()
            enhanced.release()
            outputRgba.release()
        }
    }.getOrDefault(this)
}

private object StaffCropper {
    private const val TargetWidth = 1280
    private const val TargetHeight = 256
    private const val BlackThreshold = 160

    fun findStaffCrops(
        bitmap: Bitmap,
        segmentationModel: SegmentationModel?,
    ): StaffCropResult {
        val gray = bitmap.toGray()
        val staffMask = segmentationModel?.let { model ->
            runCatching { model.staffMask(bitmap) }.getOrNull()
        }
        val segmentationLineCenters = staffMask?.let { mask ->
            detectMaskLineCenters(mask, bitmap.width, bitmap.height)
        }.orEmpty()
        val fallbackLineCenters = detectLineCenters(gray, bitmap.width, bitmap.height)
        val lineCenters = segmentationLineCenters.takeIf { groupStaffLines(it).isNotEmpty() }
            ?: fallbackLineCenters
        val warning = when {
            segmentationModel == null -> null
            segmentationLineCenters.isEmpty() -> "Segmentation did not return staff-line candidates; used row-profile detection."
            lineCenters === fallbackLineCenters -> "Segmentation staff-line candidates were inconsistent; used row-profile detection."
            else -> null
        }
        val groups = groupStaffLines(lineCenters)
        return StaffCropResult(
            crops = groups.mapIndexed { index, group ->
                val source = cropRectForGroup(
                    gray = gray,
                    staffMask = staffMask,
                    width = bitmap.width,
                    height = bitmap.height,
                    group = group,
                    previousGroup = groups.getOrNull(index - 1),
                    nextGroup = groups.getOrNull(index + 1),
                )
                StaffCrop(
                    index = index,
                    source = source,
                    bitmap = bitmap.cropToWhiteCanvas(source),
                )
            },
            warning = warning,
        )
    }

    private fun cropRectForGroup(
        gray: IntArray,
        staffMask: ByteArray?,
        width: Int,
        height: Int,
        group: List<Int>,
        previousGroup: List<Int>?,
        nextGroup: List<Int>?,
    ): Rect {
        val spacing = group.zipWithNext { a, b -> b - a }.average().takeIf { it.isFinite() } ?: 12.0
        val previousBoundary = previousGroup?.let { ((it.last() + group.first()) / 2.0).roundToInt() } ?: 0
        val nextBoundary = nextGroup?.let { ((group.last() + it.first()) / 2.0).roundToInt() } ?: height - 1
        val top = max(
            (group.first() - spacing * 4).roundToInt(),
            previousBoundary,
        ).coerceIn(0, height - 1)
        val bottom = min(
            (group.last() + spacing * 4).roundToInt(),
            nextBoundary,
        ).coerceIn(top + 1, height)

        val xRange = xRangeFromMask(staffMask, width, height, group, spacing)
            ?: xRangeFromGray(gray, width, height, group, spacing)
            ?: (0 until width)
        val horizontalMargin = (spacing * 6).roundToInt().coerceAtLeast(24)
        val left = (xRange.first - horizontalMargin).coerceAtLeast(0)
        val right = (xRange.last + horizontalMargin).coerceAtMost(width - 1)
        return Rect(left, top, max(left + 1, right + 1), bottom)
    }

    private fun xRangeFromMask(
        mask: ByteArray?,
        width: Int,
        height: Int,
        group: List<Int>,
        spacing: Double,
    ): IntRange? {
        if (mask == null) return null
        val top = (group.first() - spacing).roundToInt().coerceAtLeast(0)
        val bottom = (group.last() + spacing).roundToInt().coerceAtMost(height - 1)
        val counts = IntArray(width)
        for (y in top..bottom) {
            val offset = y * width
            for (x in 0 until width) {
                if (mask[offset + x].toInt() != 0) counts[x] += 1
            }
        }
        val threshold = max(1, ((bottom - top + 1) * 0.12f).roundToInt())
        val first = counts.indexOfFirst { it >= threshold }
        val last = counts.indexOfLast { it >= threshold }
        if (first < 0 || last <= first || last - first < width / 4) return null
        return first..last
    }

    private fun xRangeFromGray(
        gray: IntArray,
        width: Int,
        height: Int,
        group: List<Int>,
        spacing: Double,
    ): IntRange? {
        val top = (group.first() - spacing).roundToInt().coerceAtLeast(0)
        val bottom = (group.last() + spacing).roundToInt().coerceAtMost(height - 1)
        val counts = IntArray(width)
        for (y in top..bottom) {
            val offset = y * width
            for (x in 0 until width) {
                if (gray[offset + x] < BlackThreshold) counts[x] += 1
            }
        }
        val threshold = max(1, ((bottom - top + 1) * 0.08f).roundToInt())
        val first = counts.indexOfFirst { it >= threshold }
        val last = counts.indexOfLast { it >= threshold }
        if (first < 0 || last <= first || last - first < width / 4) return null
        return first..last
    }

    private fun detectMaskLineCenters(
        mask: ByteArray,
        width: Int,
        height: Int,
    ): List<Int> {
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            val offset = y * width
            for (x in 0 until width) {
                if (mask[offset + x].toInt() != 0) count++
            }
            rowCounts[y] = count
        }

        val threshold = max(width / 12, rowCounts.maxOrNull()?.let { (it * 0.35f).roundToInt() } ?: 0)
        val centers = mutableListOf<Int>()
        var y = 0
        while (y < height) {
            if (rowCounts[y] < threshold) {
                y++
                continue
            }
            val start = y
            var weighted = 0L
            var total = 0L
            while (y < height && rowCounts[y] >= threshold) {
                weighted += y.toLong() * rowCounts[y]
                total += rowCounts[y]
                y++
            }
            centers += if (total > 0) (weighted / total).toInt() else (start + y) / 2
        }
        return centers.distinctSorted(minDistance = 3)
    }

    private fun detectLineCenters(
        gray: IntArray,
        width: Int,
        height: Int,
    ): List<Int> {
        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            val offset = y * width
            for (x in 0 until width) {
                if (gray[offset + x] < BlackThreshold) count++
            }
            rowCounts[y] = count
        }

        val threshold = max(width / 5, rowCounts.maxOrNull()?.let { (it * 0.45f).roundToInt() } ?: 0)
        val centers = mutableListOf<Int>()
        var y = 0
        while (y < height) {
            if (rowCounts[y] < threshold) {
                y++
                continue
            }
            val start = y
            var weighted = 0L
            var total = 0L
            while (y < height && rowCounts[y] >= threshold) {
                weighted += y.toLong() * rowCounts[y]
                total += rowCounts[y]
                y++
            }
            centers += if (total > 0) (weighted / total).toInt() else (start + y) / 2
        }
        return centers.distinctSorted(minDistance = 3)
    }

    private fun groupStaffLines(lines: List<Int>): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        var index = 0
        while (index + 4 < lines.size) {
            val candidate = lines.subList(index, index + 5)
            val diffs = candidate.zipWithNext { a, b -> b - a }
            val average = diffs.average()
            val consistent = average in 4.0..48.0 && diffs.all { abs(it - average) <= max(3.0, average * 0.45) }
            if (consistent) {
                groups += candidate.toList()
                index += 5
            } else {
                index += 1
            }
        }
        return groups
    }

    private fun List<Int>.distinctSorted(minDistance: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (value in sorted()) {
            if (result.isEmpty() || value - result.last() >= minDistance) {
                result += value
            }
        }
        return result
    }

    private fun Bitmap.cropToWhiteCanvas(source: Rect): Bitmap {
        val croppedWidth = source.width().coerceAtLeast(1)
        val croppedHeight = source.height().coerceAtLeast(1)
        val scale = min(TargetWidth / croppedWidth.toFloat(), TargetHeight / croppedHeight.toFloat())
        val scaledWidth = (croppedWidth * scale).roundToInt().coerceIn(1, TargetWidth)
        val scaledHeight = (croppedHeight * scale).roundToInt().coerceIn(1, TargetHeight)
        val output = Bitmap.createBitmap(TargetWidth, TargetHeight, Bitmap.Config.ARGB_8888)
        output.eraseColor(Color.WHITE)
        val destination = Rect(
            0,
            (TargetHeight - scaledHeight) / 2,
            scaledWidth,
            (TargetHeight - scaledHeight) / 2 + scaledHeight,
        )
        Canvas(output).drawBitmap(this, source, destination, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }
}

private data class StaffCrop(
    val index: Int,
    val source: Rect,
    val bitmap: Bitmap,
) {
    fun toTransformerInput(): java.nio.FloatBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val buffer = ByteBuffer
            .allocateDirect(4 * bitmap.width * bitmap.height)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (color in pixels) {
            val gray = (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100
            buffer.put(((gray / 255f) - 0.7931f) / 0.1738f)
        }
        buffer.rewind()
        return buffer
    }
}

private data class StaffCropResult(
    val crops: List<StaffCrop>,
    val warning: String?,
)

private fun Array<LongArray>.reachedEndOfSequence(): Boolean {
    return firstOrNull()?.any { it == 3L } == true
}
