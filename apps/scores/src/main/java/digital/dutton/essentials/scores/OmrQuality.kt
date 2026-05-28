package digital.dutton.essentials.scores

object OmrQuality {
    private const val MaxMeasuresPerSourcePage = 32

    fun lowConfidenceWarnings(
        musicXml: ByteArray,
        sourcePageCount: Int,
    ): List<ScoreWarning> {
        val safePageCount = sourcePageCount.coerceAtLeast(1)
        val measureCount = MusicXmlFiles.maxMeasureCountInPart(musicXml) ?: return emptyList()
        val maxExpectedMeasures = safePageCount * MaxMeasuresPerSourcePage
        if (measureCount <= maxExpectedMeasures) return emptyList()

        return listOf(
            ScoreWarning(
                pageIndex = null,
                code = "omr_low_confidence",
                message = "OMR produced $measureCount measures from $safePageCount source page(s), above the V1 quality gate of $maxExpectedMeasures. The import was marked failed so this output is not treated as a reliable score.",
            ),
        )
    }
}
