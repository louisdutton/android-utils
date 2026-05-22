package digital.dutton.essentials.finance.data

enum class ProviderKind {
    Energy,
    Water,
    Connectivity,
    LocalAuthority,
}

enum class ConnectionState {
    Available,
    Connected,
    NeedsSetup,
    Planned,
}

enum class BillingPeriod {
    Day,
    Week,
    Month,
    Year,
}

enum class BillingFuel {
    Electricity,
    Gas,
}

enum class BillingCategory(
    val id: String,
    val displayName: String,
) {
    Electricity("electricity", "Electricity"),
    Gas("gas", "Gas"),
    Water("water", "Water"),
    Broadband("broadband", "Broadband"),
    CouncilTax("council-tax", "Council tax"),
}

data class ProviderDefinition(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val summary: String,
    val documentationUrl: String,
)

data class ProviderCardState(
    val provider: ProviderDefinition,
    val connectionState: ConnectionState,
    val status: String,
    val detail: String,
    val primaryAction: String?,
    val secondaryAction: String? = null,
)

data class UtilityAccount(
    val providerName: String,
    val displayName: String,
    val status: String,
    val detail: String,
)

data class OctopusCredentials(
    val apiKey: String,
    val accountNumber: String,
)

data class OctopusAgreement(
    val tariffCode: String,
    val validFromMillis: Long?,
    val validToMillis: Long?,
)

data class OctopusMeterPoint(
    val fuel: BillingFuel,
    val meterPointNumber: String,
    val meterSerialNumbers: List<String>,
    val agreements: List<OctopusAgreement>,
    val annualConsumptionKwh: Double?,
    val isExport: Boolean = false,
)

data class OctopusAccountSnapshot(
    val accountNumber: String,
    val propertyCount: Int,
    val electricityMeterPointCount: Int,
    val electricityMeterCount: Int,
    val gasMeterPointCount: Int,
    val gasMeterCount: Int,
    val tariffCodes: List<String>,
    val meterPoints: List<OctopusMeterPoint> = emptyList(),
)

data class CategoryBillingTotal(
    val category: BillingCategory,
    val categoryName: String,
    val usageKwh: Double,
    val estimatedCostPence: Double?,
)

data class BillingBreakdownRow(
    val label: String,
    val category: BillingCategory?,
    val categoryName: String,
    val usageKwh: Double,
    val estimatedCostPence: Double?,
    val detail: String,
)

data class BillingSummary(
    val period: BillingPeriod,
    val categoryFilter: BillingCategory?,
    val rangeLabel: String,
    val totalUsageKwh: Double,
    val estimatedCostPence: Double?,
    val categoryTotals: List<CategoryBillingTotal>,
    val rows: List<BillingBreakdownRow>,
    val generatedAtMillis: Long,
    val notice: String? = null,
)

object FinanceProviders {
    val Octopus = ProviderDefinition(
        id = "octopus",
        name = "Octopus Energy",
        kind = ProviderKind.Energy,
        summary = "Energy account, meters, tariffs, and consumption data.",
        documentationUrl = "https://docs.octopus.energy/rest/guides/endpoints/",
    )

    val SouthWestWater = ProviderDefinition(
        id = "south-west-water",
        name = "South West Water",
        kind = ProviderKind.Water,
        summary = "Water account, billing, and meter data.",
        documentationUrl = "https://www.southwestwater.co.uk/",
    )

    val Broadband = ProviderDefinition(
        id = "broadband",
        name = "Broadband",
        kind = ProviderKind.Connectivity,
        summary = "Internet account, package, renewal date, and monthly bill.",
        documentationUrl = "",
    )

    val CouncilTax = ProviderDefinition(
        id = "council-tax",
        name = "Council tax",
        kind = ProviderKind.LocalAuthority,
        summary = "Local authority account, billing schedule, and balance.",
        documentationUrl = "",
    )

    val All = listOf(Octopus, SouthWestWater, Broadband, CouncilTax)
}
