package dev.octoshrimpy.quik.model

open class MessageContentFilter(
        var id: Long = 0,

        var value: String = "",
        var caseSensitive: Boolean = false,
        var isRegex: Boolean = false,
        var includeContacts: Boolean = false
) : ModelObject()

data class MessageContentFilterData(
        var value: String = "",
        var caseSensitive: Boolean = false,
        var isRegex: Boolean = false,
        var includeContacts: Boolean = false
)
