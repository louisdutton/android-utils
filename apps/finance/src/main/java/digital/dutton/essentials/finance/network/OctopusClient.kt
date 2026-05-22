package digital.dutton.essentials.finance.network

import digital.dutton.essentials.finance.data.BillingBreakdownRow
import digital.dutton.essentials.finance.data.BillingCategory
import digital.dutton.essentials.finance.data.BillingFuel
import digital.dutton.essentials.finance.data.BillingPeriod
import digital.dutton.essentials.finance.data.BillingSummary
import digital.dutton.essentials.finance.data.CategoryBillingTotal
import digital.dutton.essentials.finance.data.OctopusAccountSnapshot
import digital.dutton.essentials.finance.data.OctopusAgreement
import digital.dutton.essentials.finance.data.OctopusCredentials
import digital.dutton.essentials.finance.data.OctopusMeterPoint
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class OctopusClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    fun fetchAccount(credentials: OctopusCredentials): OctopusAccountSnapshot {
        val accountNumber = credentials.accountNumber.trim()
        require(accountNumber.isNotBlank()) { "Octopus account number is required." }
        require(credentials.apiKey.isNotBlank()) { "Octopus API key is required." }

        val url = BaseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("accounts")
            .addPathSegment(accountNumber)
            .addPathSegment("")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(credentials.apiKey.trim(), ""))
            .header("User-Agent", UserAgent)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "Octopus rejected the API key or account number."
                    404 -> "Octopus could not find that account number."
                    else -> "Octopus returned HTTP ${response.code}."
                }
                throw IllegalStateException(message)
            }

            val body = response.body?.string()
                ?: throw IllegalStateException("Octopus returned an empty response.")
            return parseOctopusAccountSnapshot(body, accountNumber)
        }
    }

    fun fetchBillingSummary(
        credentials: OctopusCredentials,
        snapshot: OctopusAccountSnapshot,
        period: BillingPeriod,
        categoryFilter: BillingCategory?,
    ): BillingSummary {
        if (categoryFilter != null && categoryFilter !in OctopusCategories) {
            return emptyBillingSummary(
                period = period,
                categoryFilter = categoryFilter,
                notice = "That bill category is not connected yet.",
            )
        }

        val range = periodRange(period)
        val bucketsByCategory = OctopusCategories.associateWith { buildBuckets(range) }
        val notices = linkedSetOf<String>()
        val returnedMeterPoints = snapshot.meterPoints
            .filter { !it.isExport && it.meterPointNumber.isNotBlank() && it.meterSerialNumbers.isNotEmpty() }
        val meterPoints = returnedMeterPoints
            .filter { categoryFilter == null || it.fuel.toCategory() == categoryFilter }

        if (returnedMeterPoints.isEmpty()) {
            notices.add("Octopus did not return smart meter details for this account.")
        } else if (meterPoints.isEmpty()) {
            notices.add("Octopus did not return smart meter details for this category.")
        }

        meterPoints.forEach { meterPoint ->
            val category = meterPoint.fuel.toCategory()
            val buckets = bucketsByCategory.getValue(category)
            val tariffWindows = fetchTariffWindows(meterPoint, range, notices)

            buckets.forEach { bucket ->
                val standingCost = standingCostPence(
                    intervalStart = bucket.start.toInstant(),
                    intervalEnd = bucket.end.toInstant(),
                    charges = tariffWindows.standingCharges,
                )
                if (standingCost != null) {
                    bucket.standingCostPence += standingCost
                    bucket.hasCost = true
                }
            }

            meterPoint.meterSerialNumbers.forEach { serialNumber ->
                runCatching {
                    fetchConsumption(
                        credentials = credentials,
                        meterPoint = meterPoint,
                        serialNumber = serialNumber,
                        range = range,
                    )
                }.onSuccess { consumption ->
                    val energyMultiplier = energyMultiplier(
                        meterPoint = meterPoint,
                        range = range,
                        consumption = consumption,
                        notices = notices,
                    )
                    consumption.forEach { entry ->
                        val bucket = buckets.firstOrNull { it.contains(entry.intervalStart, range.zone) }
                            ?: return@forEach
                        val usageKwh = entry.consumption * energyMultiplier
                        bucket.usageKwh += usageKwh

                        val rate = weightedRatePence(
                            intervalStart = entry.intervalStart,
                            intervalEnd = entry.intervalEnd,
                            windows = tariffWindows.unitRates,
                        )
                        if (rate != null) {
                            bucket.usageCostPence += usageKwh * rate
                            bucket.hasCost = true
                        } else if (usageKwh > 0.0) {
                            notices.add("Some Octopus tariff rates were not available for this period.")
                        }
                    }
                }.onFailure {
                    notices.add("Some Octopus usage could not be loaded.")
                }
            }
        }

        val activeCategories = if (categoryFilter == null) OctopusCategories else listOf(categoryFilter)
        val categoryRows = activeCategories.flatMap { category ->
            bucketsByCategory.getValue(category)
                .filter { it.usageKwh > 0.0 || it.hasCost }
                .map { bucket ->
                    BillingBreakdownRow(
                        label = bucket.label(period),
                        category = category,
                        categoryName = category.displayName,
                        usageKwh = bucket.usageKwh,
                        estimatedCostPence = bucket.estimatedCostPence,
                        detail = if (bucket.hasCost) {
                            "Includes available unit rates and standing charges."
                        } else {
                            "Usage only. Tariff rates were not available."
                        },
                    )
                }
        }

        val categoryTotals = activeCategories.mapNotNull { category ->
            val rowsForCategory = categoryRows.filter { it.category == category }
            if (rowsForCategory.isEmpty()) return@mapNotNull null
            CategoryBillingTotal(
                category = category,
                categoryName = category.displayName,
                usageKwh = rowsForCategory.sumOf { it.usageKwh },
                estimatedCostPence = rowsForCategory.mapNotNull { it.estimatedCostPence }
                    .takeIf { it.isNotEmpty() }
                    ?.sum(),
            )
        }
        val rows = if (categoryFilter == null) {
            combinedRows(
                period = period,
                categories = activeCategories,
                bucketsByCategory = bucketsByCategory,
            )
        } else {
            categoryRows
        }
        val totalUsage = categoryTotals.sumOf { it.usageKwh }
        val totalCost = categoryTotals.mapNotNull { it.estimatedCostPence }.takeIf { it.isNotEmpty() }?.sum()

        return BillingSummary(
            period = period,
            categoryFilter = categoryFilter,
            rangeLabel = range.label,
            totalUsageKwh = totalUsage,
            estimatedCostPence = totalCost,
            categoryTotals = categoryTotals,
            rows = rows,
            generatedAtMillis = System.currentTimeMillis(),
            notice = notices.joinToString(" ").ifBlank { null },
        )
    }

    private fun fetchConsumption(
        credentials: OctopusCredentials,
        meterPoint: OctopusMeterPoint,
        serialNumber: String,
        range: PeriodRange,
    ): List<ConsumptionEntry> {
        val url = BaseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment(
                when (meterPoint.fuel) {
                    BillingFuel.Electricity -> "electricity-meter-points"
                    BillingFuel.Gas -> "gas-meter-points"
                },
            )
            .addPathSegment(meterPoint.meterPointNumber)
            .addPathSegment("meters")
            .addPathSegment(serialNumber)
            .addPathSegment("consumption")
            .addPathSegment("")
            .addQueryParameter("period_from", IsoInstantFormatter.format(range.start.toInstant()))
            .addQueryParameter("period_to", IsoInstantFormatter.format(range.end.toInstant()))
            .addQueryParameter("order_by", "period")
            .addQueryParameter("page_size", "1500")
            .apply {
                range.groupBy?.let { addQueryParameter("group_by", it) }
            }
            .build()

        return fetchPagedResults(url, credentials.apiKey)
            .mapNotNull { item ->
                val start = parseInstant(item.optString("interval_start")) ?: return@mapNotNull null
                val end = parseInstant(item.optString("interval_end"))
                    ?: fallbackIntervalEnd(start, range.period)
                ConsumptionEntry(
                    consumption = item.optDouble("consumption", 0.0),
                    intervalStart = start,
                    intervalEnd = end,
                )
            }
    }

    private fun fetchTariffWindows(
        meterPoint: OctopusMeterPoint,
        range: PeriodRange,
        notices: MutableSet<String>,
    ): TariffWindows {
        val agreements = meterPoint.agreements
            .filter { it.overlaps(range.start.toInstant(), range.end.toInstant()) }
            .ifEmpty { meterPoint.agreements.take(1) }

        val unitRates = mutableListOf<PriceWindow>()
        val standingCharges = mutableListOf<PriceWindow>()

        agreements.forEach { agreement ->
            val productCode = productCodeFromTariff(agreement.tariffCode)
            if (productCode == null) {
                notices.add("Some Octopus tariff codes could not be mapped to product prices.")
                return@forEach
            }

            val tariffType = when (meterPoint.fuel) {
                BillingFuel.Electricity -> "electricity-tariffs"
                BillingFuel.Gas -> "gas-tariffs"
            }

            runCatching {
                fetchPriceWindows(
                    productCode = productCode,
                    tariffType = tariffType,
                    tariffCode = agreement.tariffCode,
                    pricePath = "standard-unit-rates",
                    range = range,
                )
            }.onSuccess { unitRates += it }
                .onFailure { notices.add("Some Octopus unit rates could not be loaded.") }

            runCatching {
                fetchPriceWindows(
                    productCode = productCode,
                    tariffType = tariffType,
                    tariffCode = agreement.tariffCode,
                    pricePath = "standing-charges",
                    range = range,
                )
            }.onSuccess { standingCharges += it }
                .onFailure { notices.add("Some Octopus standing charges could not be loaded.") }
        }

        return TariffWindows(
            unitRates = unitRates.distinctBy { it.identityKey },
            standingCharges = standingCharges.distinctBy { it.identityKey },
        )
    }

    private fun fetchPriceWindows(
        productCode: String,
        tariffType: String,
        tariffCode: String,
        pricePath: String,
        range: PeriodRange,
    ): List<PriceWindow> {
        val url = BaseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("products")
            .addPathSegment(productCode)
            .addPathSegment(tariffType)
            .addPathSegment(tariffCode)
            .addPathSegment(pricePath)
            .addPathSegment("")
            .addQueryParameter("period_from", IsoInstantFormatter.format(range.start.toInstant()))
            .addQueryParameter("period_to", IsoInstantFormatter.format(range.end.toInstant()))
            .addQueryParameter("page_size", "1500")
            .build()

        return fetchPagedResults(url, apiKey = null)
            .mapNotNull { item ->
                val value = item.optDouble("value_inc_vat", Double.NaN)
                if (value.isNaN()) return@mapNotNull null
                PriceWindow(
                    valueIncVat = value,
                    validFrom = parseInstant(item.optString("valid_from")),
                    validTo = parseInstant(item.optString("valid_to")),
                )
            }
    }

    private fun fetchPagedResults(
        initialUrl: HttpUrl,
        apiKey: String?,
    ): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        var nextUrl: HttpUrl? = initialUrl

        while (nextUrl != null) {
            val requestBuilder = Request.Builder()
                .url(nextUrl)
                .header("User-Agent", UserAgent)

            if (!apiKey.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(apiKey.trim(), ""))
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Octopus returned HTTP ${response.code}.")
                }

                val body = response.body?.string()
                    ?: throw IllegalStateException("Octopus returned an empty response.")
                val json = JSONObject(body)
                val pageResults = json.optJSONArray("results") ?: JSONArray()
                for (index in 0 until pageResults.length()) {
                    pageResults.optJSONObject(index)?.let(results::add)
                }
                nextUrl = json.optString("next")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.toHttpUrl()
            }
        }

        return results
    }

    companion object {
        private const val BaseUrl = "https://api.octopus.energy/v1/"
        private const val UserAgent = "GrapheneOS Essentials Bills/0.2"
    }
}

fun parseOctopusAccountSnapshot(
    body: String,
    fallbackAccountNumber: String,
): OctopusAccountSnapshot {
    val json = JSONObject(body)
    val properties = json.optJSONArray("properties") ?: JSONArray()
    val tariffCodes = linkedSetOf<String>()
    val meterPoints = mutableListOf<OctopusMeterPoint>()
    var electricityMeterPointCount = 0
    var electricityMeterCount = 0
    var gasMeterPointCount = 0
    var gasMeterCount = 0

    for (propertyIndex in 0 until properties.length()) {
        val property = properties.optJSONObject(propertyIndex) ?: continue

        val electricityMeterPoints = property.optJSONArray("electricity_meter_points") ?: JSONArray()
        electricityMeterPointCount += electricityMeterPoints.length()
        for (meterPointIndex in 0 until electricityMeterPoints.length()) {
            val meterPoint = electricityMeterPoints.optJSONObject(meterPointIndex) ?: continue
            val meters = meterPoint.optJSONArray("meters") ?: JSONArray()
            electricityMeterCount += meters.length()
            parseMeterPoint(
                fuel = BillingFuel.Electricity,
                meterPoint = meterPoint,
                meterPointNumberKey = "mpan",
                meters = meters,
            )?.let(meterPoints::add)
        }

        val gasMeterPoints = property.optJSONArray("gas_meter_points") ?: JSONArray()
        gasMeterPointCount += gasMeterPoints.length()
        for (meterPointIndex in 0 until gasMeterPoints.length()) {
            val meterPoint = gasMeterPoints.optJSONObject(meterPointIndex) ?: continue
            val meters = meterPoint.optJSONArray("meters") ?: JSONArray()
            gasMeterCount += meters.length()
            parseMeterPoint(
                fuel = BillingFuel.Gas,
                meterPoint = meterPoint,
                meterPointNumberKey = "mprn",
                meters = meters,
            )?.let(meterPoints::add)
        }
    }

    collectTariffCodes(json, tariffCodes)

    return OctopusAccountSnapshot(
        accountNumber = json.optString("number").takeIf { it.isNotBlank() } ?: fallbackAccountNumber,
        propertyCount = properties.length(),
        electricityMeterPointCount = electricityMeterPointCount,
        electricityMeterCount = electricityMeterCount,
        gasMeterPointCount = gasMeterPointCount,
        gasMeterCount = gasMeterCount,
        tariffCodes = tariffCodes.toList(),
        meterPoints = meterPoints,
    )
}

private fun parseMeterPoint(
    fuel: BillingFuel,
    meterPoint: JSONObject,
    meterPointNumberKey: String,
    meters: JSONArray,
): OctopusMeterPoint? {
    val meterPointNumber = meterPoint.optString(meterPointNumberKey).takeIf { it.isNotBlank() }
        ?: return null
    val serialNumbers = buildList {
        for (meterIndex in 0 until meters.length()) {
            meters.optJSONObject(meterIndex)
                ?.optString("serial_number")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }.distinct()

    return OctopusMeterPoint(
        fuel = fuel,
        meterPointNumber = meterPointNumber,
        meterSerialNumbers = serialNumbers,
        agreements = parseAgreements(meterPoint.optJSONArray("agreements") ?: JSONArray()),
        annualConsumptionKwh = meterPoint.optDouble("consumption_standard", Double.NaN)
            .takeUnless { it.isNaN() },
        isExport = meterPoint.optBoolean("is_export", false),
    )
}

private fun parseAgreements(agreements: JSONArray): List<OctopusAgreement> {
    return buildList {
        for (agreementIndex in 0 until agreements.length()) {
            val agreement = agreements.optJSONObject(agreementIndex) ?: continue
            val tariffCode = agreement.optString("tariff_code").takeIf { it.isNotBlank() }
                ?: continue
            add(
                OctopusAgreement(
                    tariffCode = tariffCode,
                    validFromMillis = parseInstant(agreement.optString("valid_from"))?.toEpochMilli(),
                    validToMillis = parseInstant(agreement.optString("valid_to"))?.toEpochMilli(),
                ),
            )
        }
    }
}

private fun collectTariffCodes(
    value: Any?,
    destination: MutableSet<String>,
) {
    when (value) {
        is JSONObject -> {
            value.optString("tariff_code")
                .takeIf { it.isNotBlank() }
                ?.let(destination::add)

            val keys = value.keys()
            while (keys.hasNext()) {
                collectTariffCodes(value.opt(keys.next()), destination)
            }
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                collectTariffCodes(value.opt(index), destination)
            }
        }
    }
}

private data class PeriodRange(
    val period: BillingPeriod,
    val zone: ZoneId,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val groupBy: String?,
    val label: String,
)

private data class ConsumptionEntry(
    val consumption: Double,
    val intervalStart: Instant,
    val intervalEnd: Instant,
)

private data class PriceWindow(
    val valueIncVat: Double,
    val validFrom: Instant?,
    val validTo: Instant?,
) {
    val identityKey: String = "${valueIncVat}_${validFrom}_${validTo}"
}

private data class TariffWindows(
    val unitRates: List<PriceWindow>,
    val standingCharges: List<PriceWindow>,
)

private class BucketAccumulator(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
) {
    var usageKwh: Double = 0.0
    var usageCostPence: Double = 0.0
    var standingCostPence: Double = 0.0
    var hasCost: Boolean = false

    val estimatedCostPence: Double?
        get() = if (hasCost) usageCostPence + standingCostPence else null

    fun contains(instant: Instant, zone: ZoneId): Boolean {
        val value = instant.atZone(zone)
        return !value.isBefore(start) && value.isBefore(end)
    }

    fun label(period: BillingPeriod): String {
        val pattern = when (period) {
            BillingPeriod.Day -> "HH:mm"
            BillingPeriod.Week,
            BillingPeriod.Month -> "d MMM"
            BillingPeriod.Year -> "MMM"
        }
        return DateTimeFormatter.ofPattern(pattern).format(start)
    }
}

private fun combinedRows(
    period: BillingPeriod,
    categories: List<BillingCategory>,
    bucketsByCategory: Map<BillingCategory, List<BucketAccumulator>>,
): List<BillingBreakdownRow> {
    val bucketCount = categories.firstOrNull()
        ?.let { bucketsByCategory.getValue(it).size }
        ?: return emptyList()

    return buildList {
        for (index in 0 until bucketCount) {
            val buckets = categories.map { bucketsByCategory.getValue(it)[index] }
            val usageKwh = buckets.sumOf { it.usageKwh }
            val costs = buckets.mapNotNull { it.estimatedCostPence }
            if (usageKwh <= 0.0 && costs.isEmpty()) continue

            add(
                BillingBreakdownRow(
                    label = buckets.first().label(period),
                    category = null,
                    categoryName = "All categories",
                    usageKwh = usageKwh,
                    estimatedCostPence = costs.takeIf { it.isNotEmpty() }?.sum(),
                    detail = "Combined electricity and gas.",
                ),
            )
        }
    }
}

private fun energyMultiplier(
    meterPoint: OctopusMeterPoint,
    range: PeriodRange,
    consumption: List<ConsumptionEntry>,
    notices: MutableSet<String>,
): Double {
    if (meterPoint.fuel == BillingFuel.Electricity) return 1.0

    val totalConsumption = consumption.sumOf { it.consumption }
    if (totalConsumption <= 0.0) return 1.0

    val expectedKwh = meterPoint.annualConsumptionKwh?.let { annual ->
        annual * Duration.between(range.start.toInstant(), range.end.toInstant()).toMillis() / MillisPerYear
    }
    val looksLikeKwh = expectedKwh != null && totalConsumption > expectedKwh * GasKwhDetectionThreshold
    if (looksLikeKwh) {
        notices.add("Gas usage is treated as kWh because Octopus returned values close to expected energy use.")
        return 1.0
    }

    notices.add("Gas usage is converted to estimated kWh for aggregation because Octopus can return SMETS2 gas in cubic metres.")
    return GasKwhPerCubicMetreEstimate
}

private fun BillingFuel.toCategory(): BillingCategory {
    return when (this) {
        BillingFuel.Electricity -> BillingCategory.Electricity
        BillingFuel.Gas -> BillingCategory.Gas
    }
}

private fun emptyBillingSummary(
    period: BillingPeriod,
    categoryFilter: BillingCategory?,
    notice: String?,
): BillingSummary {
    val range = periodRange(period)
    return BillingSummary(
        period = period,
        categoryFilter = categoryFilter,
        rangeLabel = range.label,
        totalUsageKwh = 0.0,
        estimatedCostPence = null,
        categoryTotals = emptyList(),
        rows = emptyList(),
        generatedAtMillis = System.currentTimeMillis(),
        notice = notice,
    )
}

private fun periodRange(
    period: BillingPeriod,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): PeriodRange {
    val end = now.atZone(zone)
    val today = end.toLocalDate()
    val start = when (period) {
        BillingPeriod.Day -> today.atStartOfDay(zone)
        BillingPeriod.Week -> today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone)
        BillingPeriod.Month -> today.withDayOfMonth(1).atStartOfDay(zone)
        BillingPeriod.Year -> today.withDayOfYear(1).atStartOfDay(zone)
    }
    val label = when (period) {
        BillingPeriod.Day -> "Today"
        BillingPeriod.Week -> "This week"
        BillingPeriod.Month -> "This month"
        BillingPeriod.Year -> "This year"
    }
    val groupBy = when (period) {
        BillingPeriod.Day -> null
        BillingPeriod.Week,
        BillingPeriod.Month -> "day"
        BillingPeriod.Year -> "month"
    }

    return PeriodRange(
        period = period,
        zone = zone,
        start = start,
        end = end,
        groupBy = groupBy,
        label = label,
    )
}

private fun buildBuckets(range: PeriodRange): List<BucketAccumulator> {
    val buckets = mutableListOf<BucketAccumulator>()
    var start = range.start
    while (start.isBefore(range.end)) {
        val next = when (range.period) {
            BillingPeriod.Day -> start.plusHours(1)
            BillingPeriod.Week,
            BillingPeriod.Month -> start.plusDays(1)
            BillingPeriod.Year -> start.plusMonths(1)
        }.coerceAtMost(range.end)
        buckets.add(BucketAccumulator(start, next))
        start = next
    }
    return buckets
}

private fun ZonedDateTime.coerceAtMost(maximum: ZonedDateTime): ZonedDateTime {
    return if (isAfter(maximum)) maximum else this
}

private fun fallbackIntervalEnd(
    start: Instant,
    period: BillingPeriod,
): Instant {
    return when (period) {
        BillingPeriod.Day -> start.plus(Duration.ofMinutes(30))
        BillingPeriod.Week,
        BillingPeriod.Month -> start.plus(Duration.ofDays(1))
        BillingPeriod.Year -> start.plus(Duration.ofDays(31))
    }
}

private fun OctopusAgreement.overlaps(
    start: Instant,
    end: Instant,
): Boolean {
    val validFrom = validFromMillis?.let(Instant::ofEpochMilli)
    val validTo = validToMillis?.let(Instant::ofEpochMilli)
    val startsBeforeEnd = validFrom == null || validFrom.isBefore(end)
    val endsAfterStart = validTo == null || validTo.isAfter(start)
    return startsBeforeEnd && endsAfterStart
}

private fun productCodeFromTariff(tariffCode: String): String? {
    val parts = tariffCode.split("-")
    return parts
        .takeIf { it.size >= 4 }
        ?.drop(2)
        ?.dropLast(1)
        ?.joinToString("-")
        ?.takeIf { it.isNotBlank() }
}

private fun weightedRatePence(
    intervalStart: Instant,
    intervalEnd: Instant,
    windows: List<PriceWindow>,
): Double? {
    if (!intervalEnd.isAfter(intervalStart) || windows.isEmpty()) return null

    var weightedTotal = 0.0
    var coveredMillis = 0L

    windows.forEach { window ->
        val overlapStart = maxOf(intervalStart, window.validFrom ?: intervalStart)
        val overlapEnd = minOf(intervalEnd, window.validTo ?: intervalEnd)
        if (overlapEnd.isAfter(overlapStart)) {
            val millis = Duration.between(overlapStart, overlapEnd).toMillis()
            coveredMillis += millis
            weightedTotal += window.valueIncVat * millis
        }
    }

    return when {
        coveredMillis > 0L -> weightedTotal / coveredMillis
        windows.size == 1 -> windows.first().valueIncVat
        else -> null
    }
}

private fun standingCostPence(
    intervalStart: Instant,
    intervalEnd: Instant,
    charges: List<PriceWindow>,
): Double? {
    val dailyCharge = weightedRatePence(intervalStart, intervalEnd, charges) ?: return null
    val days = Duration.between(intervalStart, intervalEnd).toMillis().toDouble() / MillisPerDay
    return dailyCharge * days
}

private fun parseInstant(value: String?): Instant? {
    val text = value?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }
        .getOrElse { runCatching { Instant.parse(text) }.getOrNull() }
}

private val IsoInstantFormatter = DateTimeFormatter.ISO_INSTANT
private val OctopusCategories = listOf(BillingCategory.Electricity, BillingCategory.Gas)
private const val MillisPerDay = 86_400_000.0
private const val MillisPerYear = 31_557_600_000.0
private const val GasKwhPerCubicMetreEstimate = 11.2
private const val GasKwhDetectionThreshold = 0.35
