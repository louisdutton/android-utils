package digital.dutton.essentials.calendar.sync

import android.graphics.Color
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

class CalDavClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    fun discoverCalendars(endpoint: CalDavEndpoint): List<CalDavDiscoveredCalendar> {
        return discover(endpoint).calendars
    }

    fun discover(endpoint: CalDavEndpoint): CalDavDiscovery {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val auth = Credentials.basic(endpoint.username, endpoint.password)
        val discoveryUrl = discoverUrl(baseUrl, auth)
        val principalUrl = discoveryUrl
            ?.let { url ->
                propfind(
                    url = url,
                    auth = auth,
                    depth = "0",
                    body = currentUserPrincipalRequest(),
                )
                    .firstOkProp()
                    ?.childText("current-user-principal", "href")
                    ?.let { url.resolveHref(it) }
            }

        val homeUrl = principalUrl
            ?.let { url ->
                propfind(
                    url = url,
                    auth = auth,
                    depth = "0",
                    body = calendarHomeSetRequest(),
                )
                    .firstOkProp()
                    ?.childText("calendar-home-set", "href")
                    ?.let { url.resolveHref(it) }
            }

        val discovered = homeUrl
            ?.let { url ->
                propfind(
                    url = url,
                    auth = auth,
                    depth = "1",
                    body = calendarCollectionRequest(),
                ).calendarCollections(url)
            }
            .orEmpty()

        if (discovered.isNotEmpty()) {
            return CalDavDiscovery(homeUrl = homeUrl, calendars = discovered)
        }

        val fallbackCalendars = propfind(
            url = baseUrl,
            auth = auth,
            depth = "0",
            body = calendarCollectionRequest(),
        ).calendarCollections(baseUrl)

        return CalDavDiscovery(homeUrl = homeUrl, calendars = fallbackCalendars)
    }

    fun createCalendar(
        endpoint: CalDavEndpoint,
        homeUrl: String?,
        displayName: String,
        components: Set<String> = setOf("VEVENT"),
    ): CalDavDiscoveredCalendar {
        val calendarHomeUrl = homeUrl
            ?: throw IllegalStateException("Unable to locate CalDAV calendar home.")
        val collectionName = displayName.toCollectionName()
        val calendarUrl = calendarHomeUrl.resolveHref("$collectionName/")
        val auth = Credentials.basic(endpoint.username, endpoint.password)
        val request = Request.Builder()
            .url(calendarUrl)
            .header("Authorization", auth)
            .header("User-Agent", UserAgent)
            .method(
                "MKCALENDAR",
                mkcalendarRequest(displayName, components)
                    .toRequestBody(XmlContentType.toMediaType()),
            )
            .build()

        httpClient.newCall(request).execute().use { response ->
            response.requireSuccessful("CalDAV calendar creation failed")
        }

        val supportsEvents = "VEVENT" in components
        val supportsTasks = "VTODO" in components
        return propfind(
            url = calendarUrl,
            auth = auth,
            depth = "0",
            body = calendarCollectionRequest(),
        ).calendarCollections(calendarHomeUrl).firstOrNull()
            ?.copy(
                supportsEvents = supportsEvents,
                supportsTasks = supportsTasks,
            )
            ?: CalDavDiscoveredCalendar(
                href = calendarUrl,
                displayName = displayName,
                color = null,
                syncToken = null,
                supportsEvents = supportsEvents,
                supportsTasks = supportsTasks,
            )
    }

    fun renameCalendar(
        endpoint: CalDavEndpoint,
        calendarHref: String,
        displayName: String,
    ) {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val calendarUrl = baseUrl.resolveHref(calendarHref)
        val request = Request.Builder()
            .url(calendarUrl)
            .header("Authorization", Credentials.basic(endpoint.username, endpoint.password))
            .header("User-Agent", UserAgent)
            .method(
                "PROPPATCH",
                setDisplayNameRequest(displayName)
                    .toRequestBody(XmlContentType.toMediaType()),
            )
            .build()

        httpClient.newCall(request).execute().use { response ->
            response.requireSuccessful("CalDAV calendar rename failed")
        }
    }

    fun fetchEvents(
        endpoint: CalDavEndpoint,
        calendarHref: String,
    ): List<CalDavRemoteEvent> {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val calendarUrl = baseUrl.resolveHref(calendarHref)
        val responses = report(
            url = calendarUrl,
            auth = Credentials.basic(endpoint.username, endpoint.password),
            body = calendarQueryRequest(),
        )

        return responses.mapNotNull { response ->
            val prop = response.okProp ?: return@mapNotNull null
            val calendarData = prop.descendantText("calendar-data")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            CalDavRemoteEvent(
                href = calendarUrl.resolveHref(response.href),
                etag = prop.childText("getetag"),
                calendarData = calendarData,
            )
        }
    }

    fun fetchEventChanges(
        endpoint: CalDavEndpoint,
        calendarHref: String,
        syncToken: String,
    ): CalDavRemoteChanges {
        return fetchChanges(
            endpoint = endpoint,
            calendarHref = calendarHref,
            syncToken = syncToken,
        )
    }

    fun fetchTasks(
        endpoint: CalDavEndpoint,
        calendarHref: String,
    ): List<CalDavRemoteEvent> {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val calendarUrl = baseUrl.resolveHref(calendarHref)
        val responses = report(
            url = calendarUrl,
            auth = Credentials.basic(endpoint.username, endpoint.password),
            body = calendarQueryRequest(componentName = "VTODO"),
        )

        return responses.mapNotNull { response ->
            val prop = response.okProp ?: return@mapNotNull null
            val calendarData = prop.descendantText("calendar-data")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            CalDavRemoteEvent(
                href = calendarUrl.resolveHref(response.href),
                etag = prop.childText("getetag"),
                calendarData = calendarData,
            )
        }
    }

    fun fetchTaskChanges(
        endpoint: CalDavEndpoint,
        calendarHref: String,
        syncToken: String,
    ): CalDavRemoteChanges {
        return fetchChanges(
            endpoint = endpoint,
            calendarHref = calendarHref,
            syncToken = syncToken,
        )
    }

    private fun fetchChanges(
        endpoint: CalDavEndpoint,
        calendarHref: String,
        syncToken: String,
    ): CalDavRemoteChanges {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val calendarUrl = baseUrl.resolveHref(calendarHref)
        val multistatus = reportMultistatus(
            url = calendarUrl,
            auth = Credentials.basic(endpoint.username, endpoint.password),
            body = syncCollectionRequest(syncToken),
        )

        val changed = mutableListOf<CalDavRemoteEvent>()
        val deleted = mutableSetOf<String>()
        multistatus.responses.forEach { response ->
            val href = calendarUrl.resolveHref(response.href)
            if (response.statusCode == 404 || response.statusCode == 410) {
                deleted += href
                return@forEach
            }

            val prop = response.okProp ?: return@forEach
            val calendarData = prop.descendantText("calendar-data")
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            changed += CalDavRemoteEvent(
                href = href,
                etag = prop.childText("getetag"),
                calendarData = calendarData,
            )
        }

        return CalDavRemoteChanges(
            changed = changed,
            deletedHrefs = deleted,
            syncToken = multistatus.syncToken,
        )
    }

    fun putEvent(
        endpoint: CalDavEndpoint,
        calendarHref: String,
        eventHref: String,
        etag: String?,
        body: String,
    ): String? {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val calendarUrl = baseUrl.resolveHref(calendarHref)
        val eventUrl = calendarUrl.resolveHref(eventHref)
        val request = Request.Builder()
            .url(eventUrl)
            .header("Authorization", Credentials.basic(endpoint.username, endpoint.password))
            .header("User-Agent", UserAgent)
            .header("Content-Type", IcsContentType)
            .apply {
                if (etag == null) {
                    header("If-None-Match", "*")
                } else {
                    header("If-Match", etag)
                }
            }
            .put(body.toRequestBody(IcsContentType.toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 412) {
                throw CalDavConflictException("CalDAV event changed on the server.")
            }
            response.requireSuccessful("CalDAV event upload failed")
            return response.header("ETag")
        }
    }

    fun deleteEvent(
        endpoint: CalDavEndpoint,
        eventHref: String,
        etag: String?,
    ) {
        val baseUrl = endpoint.baseUrl.normalizedCalDavUrl()
        val eventUrl = baseUrl.resolveHref(eventHref)
        val request = Request.Builder()
            .url(eventUrl)
            .header("Authorization", Credentials.basic(endpoint.username, endpoint.password))
            .header("User-Agent", UserAgent)
            .apply { etag?.let { header("If-Match", it) } }
            .delete()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return
            if (response.code == 412) {
                throw CalDavConflictException("CalDAV event changed on the server.")
            }
            response.requireSuccessful("CalDAV event delete failed")
        }
    }

    private fun discoverUrl(
        baseUrl: String,
        auth: String,
    ): String? {
        return runCatching {
            propfind(
                url = baseUrl,
                auth = auth,
                depth = "0",
                body = currentUserPrincipalRequest(),
            )
            baseUrl
        }.getOrElse {
            val wellKnown = baseUrl.originUrl() + "/.well-known/caldav"
            runCatching {
                propfind(
                    url = wellKnown,
                    auth = auth,
                    depth = "0",
                    body = currentUserPrincipalRequest(),
                )
                wellKnown
            }.getOrNull()
        }
    }

    private fun propfind(
        url: String,
        auth: String,
        depth: String,
        body: String,
    ): List<DavResponse> {
        return requestXml(
            method = "PROPFIND",
            url = url,
            auth = auth,
            depth = depth,
            body = body,
        )
    }

    private fun report(
        url: String,
        auth: String,
        body: String,
    ): List<DavResponse> {
        return requestXml(
            method = "REPORT",
            url = url,
            auth = auth,
            depth = "1",
            body = body,
        )
    }

    private fun reportMultistatus(
        url: String,
        auth: String,
        body: String,
    ): DavMultistatus {
        return requestDav(
            method = "REPORT",
            url = url,
            auth = auth,
            depth = "1",
            body = body,
        )
    }

    private fun requestXml(
        method: String,
        url: String,
        auth: String,
        depth: String,
        body: String,
    ): List<DavResponse> {
        return requestDav(
            method = method,
            url = url,
            auth = auth,
            depth = depth,
            body = body,
        ).responses
    }

    private fun requestDav(
        method: String,
        url: String,
        auth: String,
        depth: String,
        body: String,
    ): DavMultistatus {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Depth", depth)
            .header("User-Agent", UserAgent)
            .method(method, body.toRequestBody(XmlContentType.toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            response.requireSuccessful("$method failed")
            val responseBody = response.body?.string().orEmpty()
            return responseBody.parseDavMultistatus()
        }
    }

    private fun List<DavResponse>.calendarCollections(baseUrl: String): List<CalDavDiscoveredCalendar> {
        return mapNotNull { response ->
            val prop = response.okProp ?: return@mapNotNull null
            if (!prop.hasDescendant("calendar")) return@mapNotNull null
            val supportedComponents = prop.supportedCalendarComponents()
            val supportsEvents = supportedComponents.isEmpty() || "VEVENT" in supportedComponents
            val supportsTasks = "VTODO" in supportedComponents
            if (!supportsEvents && !supportsTasks) return@mapNotNull null

            val href = baseUrl.resolveHref(response.href)
            val name = prop.childText("displayname")
                ?.takeIf { it.isNotBlank() }
                ?: href.trimEnd('/').substringAfterLast('/').ifBlank { "Calendar" }

            CalDavDiscoveredCalendar(
                href = href,
                displayName = name,
                color = prop.descendantText("calendar-color")?.toCalendarColor(),
                syncToken = prop.childText("sync-token")
                    ?: prop.childText("getctag"),
                supportsEvents = supportsEvents,
                supportsTasks = supportsTasks,
            )
        }
    }

    private fun Element.supportedCalendarComponents(): Set<String> {
        val components = getElementsByTagNameNS("*", "comp")
        return buildSet {
            for (index in 0 until components.length) {
                val element = components.item(index) as? Element ?: continue
                element.getAttribute("name")
                    .takeIf { it.isNotBlank() }
                    ?.uppercase()
                    ?.let(::add)
            }
        }
    }

    private fun List<DavResponse>.firstOkProp(): Element? {
        return firstNotNullOfOrNull { it.okProp }
    }

    private fun String.parseDavMultistatus(): DavMultistatus {
        val document = DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                trySetFeature("http://xml.org/sax/features/external-general-entities", false)
                trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(this)))
        return DavMultistatus(
            responses = document.responses(),
            syncToken = document.documentElement.childText("sync-token"),
        )
    }

    private fun Document.responses(): List<DavResponse> {
        val responseNodes = getElementsByTagNameNS("*", "response")
        return buildList {
            for (index in 0 until responseNodes.length) {
                val response = responseNodes.item(index) as? Element ?: continue
                val href = response.childText("href") ?: continue
                val okProp = response.okProp()
                add(
                    DavResponse(
                        href = href,
                        okProp = okProp,
                        statusCode = response.childText("status")?.httpStatusCode()
                            ?: response.firstPropstatStatusCode(),
                    ),
                )
            }
        }
    }

    private fun Element.firstPropstatStatusCode(): Int? {
        val propstats = getElementsByTagNameNS("*", "propstat")
        for (index in 0 until propstats.length) {
            val propstat = propstats.item(index) as? Element ?: continue
            propstat.childText("status")?.httpStatusCode()?.let { return it }
        }
        return null
    }

    private fun String.httpStatusCode(): Int? {
        return Regex("""HTTP/\S+\s+(\d{3})""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun Element.okProp(): Element? {
        val propstats = getElementsByTagNameNS("*", "propstat")
        for (index in 0 until propstats.length) {
            val propstat = propstats.item(index) as? Element ?: continue
            val status = propstat.childText("status").orEmpty()
            if (" 200 " in status || status.endsWith(" 200 OK", ignoreCase = true)) {
                return propstat.firstChildElement("prop")
            }
        }
        return firstChildElement("prop")
    }

    private fun Element.childText(localName: String): String? {
        return firstChildElement(localName)?.textContent?.trim()
    }

    private fun Element.childText(
        parentLocalName: String,
        childLocalName: String,
    ): String? {
        return firstChildElement(parentLocalName)?.childText(childLocalName)
    }

    private fun Element.descendantText(localName: String): String? {
        return getElementsByTagNameNS("*", localName)
            .item(0)
            ?.textContent
            ?.trim()
    }

    private fun Element.hasDescendant(localName: String): Boolean {
        return getElementsByTagNameNS("*", localName).length > 0
    }

    private fun Element.firstChildElement(localName: String): Element? {
        val children = childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.localName == localName) return element
        }
        return null
    }

    private fun String.resolveHref(href: String): String {
        return parseHttpUrl()
            .resolve(href)
            ?.toString()
            ?: throw IllegalArgumentException("Unable to resolve CalDAV href.")
    }

    private fun String.parseHttpUrl(): okhttp3.HttpUrl {
        return toHttpUrl()
    }

    private fun String.normalizedCalDavUrl(): String {
        val trimmed = trim()
        val url = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Use a valid HTTP or HTTPS CalDAV URL.")
        require(url.scheme == "http" || url.scheme == "https") {
            "Use an HTTP or HTTPS CalDAV URL."
        }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun String.originUrl(): String {
        val url = parseHttpUrl()
        return "${url.scheme}://${url.host}${if (url.port != url.defaultPort()) ":${url.port}" else ""}"
    }

    private fun okhttp3.HttpUrl.defaultPort(): Int {
        return okhttp3.HttpUrl.defaultPort(scheme)
    }

    private fun String.toCalendarColor(): Int? {
        val cleaned = trim().takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            Color.parseColor(cleaned)
        }.getOrNull()
    }

    private fun Response.requireSuccessful(message: String) {
        if (code in 200..299 || code == 207) return
        val bodyText = body?.string()?.take(MaxErrorBodyChars).orEmpty()
        throw IllegalStateException("$message with HTTP $code${bodyText.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
    }

    private fun DocumentBuilderFactory.trySetFeature(
        name: String,
        value: Boolean,
    ) {
        runCatching { setFeature(name, value) }
    }

    private fun currentUserPrincipalRequest(): String {
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:current-user-principal />
              </D:prop>
            </D:propfind>
        """.trimIndent()
    }

    private fun calendarHomeSetRequest(): String {
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:prop>
                <C:calendar-home-set />
              </D:prop>
            </D:propfind>
        """.trimIndent()
    }

    private fun calendarCollectionRequest(): String {
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/" xmlns:A="http://apple.com/ns/ical/">
              <D:prop>
                <D:displayname />
                <D:resourcetype />
                <D:sync-token />
                <CS:getctag />
                <A:calendar-color />
                <C:supported-calendar-component-set />
              </D:prop>
            </D:propfind>
        """.trimIndent()
    }

    private fun calendarQueryRequest(componentName: String = "VEVENT"): String {
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:prop>
                <D:getetag />
                <C:calendar-data />
              </D:prop>
              <C:filter>
                <C:comp-filter name="VCALENDAR">
                  <C:comp-filter name="$componentName" />
                </C:comp-filter>
              </C:filter>
            </C:calendar-query>
        """.trimIndent()
    }

    private fun syncCollectionRequest(syncToken: String): String {
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:sync-collection xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:sync-token>${syncToken.xmlEscaped()}</D:sync-token>
              <D:sync-level>1</D:sync-level>
              <D:prop>
                <D:getetag />
                <C:calendar-data />
              </D:prop>
            </D:sync-collection>
        """.trimIndent()
    }

    private fun mkcalendarRequest(
        displayName: String,
        components: Set<String>,
    ): String {
        val componentXml = components
            .map { it.uppercase() }
            .distinct()
            .joinToString(separator = "\n") { component ->
                """                    <C:comp name="$component" />"""
            }
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:set>
                <D:prop>
                  <D:displayname>${displayName.xmlEscaped()}</D:displayname>
                  <C:supported-calendar-component-set>
$componentXml
                  </C:supported-calendar-component-set>
                </D:prop>
              </D:set>
            </C:mkcalendar>
        """.trimIndent()
    }

    private fun setDisplayNameRequest(displayName: String): String {
        return """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propertyupdate xmlns:D="DAV:">
              <D:set>
                <D:prop>
                  <D:displayname>${displayName.xmlEscaped()}</D:displayname>
                </D:prop>
              </D:set>
            </D:propertyupdate>
        """.trimIndent()
    }

    private fun String.toCollectionName(): String {
        val slug = trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { "calendar" }
    }

    private fun String.xmlEscaped(): String {
        return buildString(length) {
            this@xmlEscaped.forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&apos;"
                        else -> character
                    },
                )
            }
        }
    }

    private data class DavResponse(
        val href: String,
        val okProp: Element?,
        val statusCode: Int?,
    )

    private data class DavMultistatus(
        val responses: List<DavResponse>,
        val syncToken: String?,
    )

    private companion object {
        const val UserAgent = "Essentials Calendar/0.1"
        const val XmlContentType = "application/xml; charset=utf-8"
        const val IcsContentType = "text/calendar; charset=utf-8"
        const val MaxErrorBodyChars = 8 * 1024
    }
}

data class CalDavRemoteEvent(
    val href: String,
    val etag: String?,
    val calendarData: String,
)

data class CalDavRemoteChanges(
    val changed: List<CalDavRemoteEvent>,
    val deletedHrefs: Set<String>,
    val syncToken: String?,
)

data class CalDavDiscovery(
    val homeUrl: String?,
    val calendars: List<CalDavDiscoveredCalendar>,
)

class CalDavConflictException(message: String) : Exception(message)
