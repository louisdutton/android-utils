package digital.dutton.essentials.scores

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object ScoreLyricLineSelector {
    fun selectForStaff(
        text: List<ScoreLyricText>,
        sourceLeft: Float,
        sourceTop: Float,
        sourceRight: Float,
        sourceBottom: Float,
        nextStaffTop: Float?,
    ): List<ScoreLyricText> {
        if (text.isEmpty()) return emptyList()
        val sourceHeight = (sourceBottom - sourceTop).coerceAtLeast(1f)
        val sourceWidth = (sourceRight - sourceLeft).coerceAtLeast(1f)
        val lower = sourceTop + sourceHeight * 0.52f
        val upper = nextStaffTop ?: (sourceBottom + sourceHeight * 0.8f)
        val horizontalMargin = max(sourceHeight * 0.75f, sourceWidth * 0.04f)
        val candidates = text.filter { token ->
            token.yPx >= lower &&
                token.yPx <= upper &&
                token.xPx >= sourceLeft - horizontalMargin &&
                token.xPx <= sourceRight + horizontalMargin
        }
        if (candidates.isEmpty()) return emptyList()

        val grouped = candidates.sortedBy { it.yPx }.fold(mutableListOf<MutableList<ScoreLyricText>>()) { groups, item ->
            val current = groups.lastOrNull()
            if (current == null || abs(current.map { it.yPx }.average() - item.yPx) > sourceHeight * 0.12f) {
                groups += mutableListOf(item)
            } else {
                current += item
            }
            groups
        }
        val primaryLine = grouped.maxByOrNull { it.size }.orEmpty()
        if (primaryLine.isEmpty()) return emptyList()

        val primaryY = primaryLine.map { it.yPx }.average().toFloat()
        val primaryLeft = primaryLine.minOf { min(it.leftPx, it.rightPx) }
        val primaryRight = primaryLine.maxOf { max(it.leftPx, it.rightPx) }
        val closeRowTolerance = sourceHeight * 0.22f
        val continuationRowTolerance = sourceHeight * 0.48f
        val horizontalExtension = sourceHeight * 0.18f

        return grouped
            .filter { group ->
                if (group === primaryLine) return@filter true
                val groupY = group.map { it.yPx }.average().toFloat()
                val yDistance = abs(groupY - primaryY)
                if (yDistance <= closeRowTolerance) return@filter true
                val groupLeft = group.minOf { min(it.leftPx, it.rightPx) }
                val groupRight = group.maxOf { max(it.leftPx, it.rightPx) }
                yDistance <= continuationRowTolerance &&
                    (groupLeft < primaryLeft - horizontalExtension || groupRight > primaryRight + horizontalExtension)
            }
            .flatten()
            .sortedBy { it.xPx }
    }
}
