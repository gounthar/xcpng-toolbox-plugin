package dev.gounthar.xcpng.toolbox.xo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * [XoClient] over Xen Orchestra's REST API, written against XO's own OpenAPI document
 * (`https://<xo>/rest/v0/docs/swagger.json`, `@xen-orchestra/rest-api` 0.35.0).
 *
 * Hand-written rather than generated. The document describes 217 paths and 264 schemas; this
 * needs a handful, and a generated client of that size would be almost entirely dead code in a
 * plugin. The generator stays the right answer if the surface ever grows.
 *
 * Uses [HttpURLConnection] rather than `java.net.http.HttpClient`, and that is forced rather
 * than chosen: **Toolbox ships a jlink'd JRE of 19 modules and `java.net.http` is not one of
 * them**, so `HttpClient` throws `NoClassDefFoundError` at runtime inside a plugin. This is why
 * Coder bundles OkHttp and Retrofit. `HttpURLConnection` lives in `java.base`, so it keeps this
 * plugin dependency-free, which is worth more than elegance for a client this size.
 */
class XoRestClient(
    baseUrl: String,
    private val token: String,
    allowUnauthorized: Boolean = false,
) : XoClient {

    private val base = baseUrl.trimEnd('/') + "/rest/v0"

    private val json = Json { ignoreUnknownKeys = true }

    // XOA ships a self-signed certificate, so this is the common case rather than an edge one.
    // Opt-in only: never the default, and it mirrors what xo-cli calls `allowUnauthorized`.
    private val insecure = allowUnauthorized

    /**
     * Fields XO should return. Asking for a subset keeps the payload small.
     *
     * `os_version` is the guest-reporting signal, and that is measured rather than assumed: across
     * the ten VMs on the lab pool it was non-empty on 5/5 of those reporting a `mainIpAddress` and
     * empty on 0/5 of those not. `pvDriversDetected` was true on 3/5 VMs that reported **no**
     * address, so PV drivers are not the same question as "is the guest talking to us".
     *
     * **`xentools` is deliberately not requested, and the reason corrects what this comment used
     * to say.** It claimed the REST schema spells the field `xentools` and that only the JSON-RPC
     * surface spells it `xenTools`. Measured on a guest that has the tools, 2026-08-19: REST
     * returns **`xenTools`**, camelCase, and returns it as an **object**
     * (`{"major": 7, "minor": 30, "version": 7.3}`) rather than the string its own OpenAPI document
     * declares. So the old field name was never returned and the old string read could never have
     * matched one. `os_version` was doing all the work; it now does it alone.
     */
    private val vmFields = "uuid,name_label,power_state,mainIpAddress,os_version"

    /** `$snapshot_of` is the parent VM, and it is what makes a snapshot list per-VM. */
    private val snapshotFields = "id,uuid,name_label,snapshot_time,\$snapshot_of"

    override fun ping() {
        // Deliberately blocking: a settings page's test-connection button wants an answer, and a
        // failure here should surface as an exception saying what is actually wrong.
        val response = get("/vms?limit=1")
        if (response.status != 200) {
            error(xoFailureMessage("/vms", response.status, response.body))
        }
    }

    override suspend fun listVms(): List<XoVm> = withContext(Dispatchers.IO) {
        val response = get("/vms?fields=$vmFields")
        require(response.status == 200) { xoFailureMessage("/vms", response.status, response.body) }
        json.parseToJsonElement(response.body).jsonArray.map { it.jsonObject.toXoVm() }
    }

    override suspend fun getVm(uuid: String): XoVm? = withContext(Dispatchers.IO) {
        val response = get("/vms/$uuid?fields=$vmFields")
        // 404 is a real answer, not a failure: the VM was deleted in XO while Toolbox had it on
        // screen. Anything else is a transport or auth problem and should be loud.
        if (response.status == 404) return@withContext null
        require(response.status == 200) { xoFailureMessage("/vms/$uuid", response.status, response.body) }
        json.parseToJsonElement(response.body).jsonObject.toXoVm()
    }

    override suspend fun start(vm: XoVm) = action(vm, "start")

    override suspend fun resume(vm: XoVm) = action(vm, "resume")

    override suspend fun unpause(vm: XoVm) = action(vm, "unpause")

    override suspend fun cleanShutdown(vm: XoVm) = action(vm, "clean_shutdown")

    override suspend fun hardShutdown(vm: XoVm) = action(vm, "hard_shutdown")

    override suspend fun snapshot(vm: XoVm, nameLabel: String): String? = withContext(Dispatchers.IO) {
        // 201 for the sync case, 202 when XO decided to go async anyway.
        val response = post("/vms/${vm.uuid}/actions/snapshot?sync=true", """{"name_label":${quote(nameLabel)}}""")
        response.orThrow("snapshot of ${vm.uuid}")
        // Undocumented, so read defensively rather than requiring it. See XoClient.snapshot.
        runCatching { json.parseToJsonElement(response.body).jsonObject.str("id") }.getOrNull()
    }

    override suspend fun listSnapshots(vm: XoVm): List<XoSnapshot> = withContext(Dispatchers.IO) {
        val response = get("/vm-snapshots?fields=$snapshotFields")
        require(response.status == 200) {
            xoFailureMessage("/vm-snapshots", response.status, response.body)
        }
        json.parseToJsonElement(response.body).jsonArray
            .map { it.jsonObject }
            // Filtered here rather than through XO's `filter` query parameter. The parameter
            // exists, but its expression syntax is XO's own and is not described in the OpenAPI
            // document, so using it would be guessing. A pool's snapshot list is small enough
            // that one unfiltered read costs less than a wrong query.
            .filter { it.str("\$snapshot_of") == vm.uuid }
            .map {
                XoSnapshot(
                    id = it.str("id") ?: it.str("uuid") ?: return@map null,
                    nameLabel = it.str("name_label") ?: "(unnamed snapshot)",
                    takenAt = it.str("snapshot_time")?.toDoubleOrNull()?.toLong() ?: 0L,
                )
            }
            .filterNotNull()
            .sortedByDescending { it.takenAt }
    }

    override suspend fun revertSnapshot(vm: XoVm, snapshotId: String) = withContext(Dispatchers.IO) {
        // The id in the path is the VM's, and the snapshot goes in a required body. Reversing
        // those is the natural guess and it is wrong; XO's own document is the authority here.
        val response = post(
            "/vms/${vm.uuid}/actions/revert_snapshot?sync=true",
            """{"snapshotId":${quote(snapshotId)}}""",
        )
        response.orThrow("revert of ${vm.uuid}")
    }

    override suspend fun primaryIpAddress(vm: XoVm): String? = getVm(vm.uuid)?.mainIpAddress

    override fun close() {
        // Nothing to release: each call opens and disconnects its own connection, and XO auth is
        // a bare token per request rather than a session.
    }

    /**
     * `sync=true` is the important part. Without it XO answers 202 with a task reference and the
     * caller has to poll, which is exactly the loop XAPI forces on the Jenkins plugin. With it,
     * the call returns when the work is done, as 204.
     *
     * A 202 despite `sync=true` is still tolerated rather than thrown: see [actionSucceeded] for
     * what it means and why nothing here has to poll.
     */
    private suspend fun action(vm: XoVm, name: String) = withContext(Dispatchers.IO) {
        post("/vms/${vm.uuid}/actions/$name?sync=true").orThrow("$name on ${vm.uuid}")
    }

    private fun get(path: String): Response = call("GET", path, null)

    private fun post(path: String, body: String? = null): Response = call("POST", path, body)

    private fun call(method: String, path: String, body: String?): Response {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // XO accepts a token cookie or HTTP Basic. The cookie is what `xo-cli token.create`
            // produces, and it keeps a password out of the configuration.
            setRequestProperty("Cookie", "authenticationToken=$token")
            setRequestProperty("Accept", "application/json")
            if (insecure && this is HttpsURLConnection) {
                sslSocketFactory = TrustEverythingSslContext.create().socketFactory
                setHostnameVerifier { _, _ -> true }
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            // A 4xx or 5xx puts the body on the error stream, and that body is where XO explains
            // itself, so it is read rather than discarded. Only that branch is bounded: see
            // [readResponseBody] for why the two branches cannot share one read.
            val failed = status !in 200..399
            val stream = if (failed) connection.errorStream else connection.inputStream
            Response(status, readResponseBody(stream, bounded = failed))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * XO's own words for a failure, or null when the body is not one of its error objects.
     *
     * A failed action answers with `{"error": "...", "info": "This is a XenServer/XCP-ng error,
     * not an XO error"}`, and the `error` string is a raw XAPI code such as
     * `VM_LACKS_FEATURE(OpaqueRef:...)`. That is cryptic, but it is the actual cause and it is
     * searchable, which a truncated JSON blob in a popup is not.
     */
    private fun Response.xoError(): String? =
        runCatching { json.parseToJsonElement(body).jsonObject.str("error") }.getOrNull()

    /** Throw with XO's explanation when it gave one, the raw body when it did not. */
    private fun Response.orThrow(what: String) {
        if (actionSucceeded(status)) return
        error("$what returned $status: ${xoError() ?: body.take(200).ifBlank { "no detail" }}")
    }

    private data class Response(val status: Int, val body: String)

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * One response body as a string: in full, or through a bounded prefix.
 *
 * Split out of `XoRestClient.call` so both rules can be held against a stream in a test rather
 * than against an appliance, because they pull in opposite directions and only one of them fails
 * loudly. Bounding a failure body costs nothing, since every consumer keeps a couple of hundred
 * characters of it; bounding a success body would truncate the pool's VM list, which is the
 * response this client exists to read, and `listVms` would then fail to parse a payload that had
 * arrived intact. That asymmetry is why the [ERROR_BODY_PREFIX_BYTES] fix applied to the event
 * stream could not simply be copied here.
 *
 * [bounded] is passed rather than derived from the status, because the caller has already decided
 * which of the two streams to read and deriving it again here would be the same decision written
 * twice, in two places free to drift apart.
 */
internal fun readResponseBody(stream: InputStream?, bounded: Boolean): String =
    stream
        ?.use { if (bounded) it.readNBytes(ERROR_BODY_PREFIX_BYTES) else it.readBytes() }
        ?.toString(Charsets.UTF_8)
        .orEmpty()

/**
 * Whether XO's answer to an action counts as "not a failure".
 *
 * The done answers are **200**, **201** and **204**. **202 is deliberately included here and it
 * does not mean done**: it means XO queued the work despite `?sync=true` and the task is still
 * running. It used to be accepted by a bare `200..204` range, which said nothing about whether
 * that was intended, inside a method called `orThrow`.
 *
 * It is accepted, and the reason is a property of the caller rather than of the status. Every
 * action this plugin issues is followed by `XcpngVmEnvironment.refreshSelf()`, which re-reads the
 * VM from the pool and republishes its real power state, and which runs before any failure is
 * reported. So a queued action corrects itself on the next read, and the worst case is a row that
 * looks settled for the moment it takes the pool to catch up. Throwing instead would report a
 * failure for an action XO accepted and is about to carry out, which is the worse of the two
 * wrong answers: one is briefly stale, the other is a popup saying something broke when nothing
 * did.
 *
 * 203 is not on the list. The old range accepted it by accident rather than by decision; XO does
 * not send it, and a status nobody has ever seen should not be pre-approved.
 */
internal fun actionSucceeded(status: Int): Boolean =
    status == 200 || status == 201 || status == 202 || status == 204

/**
 * One string field, or null for anything that is not usable as one.
 *
 * The cast is `as?` rather than `jsonPrimitive` on purpose, and it is the whole point of this
 * function. `jsonPrimitive` **throws** on a `JsonObject` or a `JsonArray` rather than returning
 * null, so reading a field XO decided to nest turns a missing value into an exception, and the
 * caller that hurts most is [xoFailureMessage], whose entire job is to explain a failure and
 * which would instead throw while doing it. That is not hypothetical here: `xenTools` is declared
 * a string in XO's own OpenAPI document and arrives as an object.
 *
 * `JsonNull` is excluded after the cast, not before, because `JsonNull` *is* a `JsonPrimitive` in
 * kotlinx and its `content` is the four characters `null`.
 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull }
        ?.content
        ?.takeIf { it.isNotBlank() }

/**
 * One VM object from XO, as the plugin's own model.
 *
 * `internal` rather than private so a test can hold it against captured XO JSON. That is worth a
 * widened visibility: the `mainIpAddress` rule below is the single measured behaviour the plugin
 * most depends on, and it is invisible from the outside: a client that returned the stale
 * address would look entirely healthy until an IDE tried to dial it.
 */
internal fun JsonObject.toXoVm(): XoVm = XoVm(
    uuid = str("uuid") ?: str("id") ?: error("VM object has neither uuid nor id"),
    nameLabel = str("name_label") ?: "(unnamed)",
    powerState = when (str("power_state")) {
        "Running" -> XoPowerState.RUNNING
        "Halted" -> XoPowerState.HALTED
        "Paused" -> XoPowerState.PAUSED
        "Suspended" -> XoPowerState.SUSPENDED
        else -> XoPowerState.UNKNOWN
    },
    // Only trusted while the VM is running. Measured on the lab pool 2026-08-19: 4 of 6 HALTED
    // VMs reported a mainIpAddress and only 1 of 4 running ones did, so XO is serving the last
    // known address rather than a live one. Handing a halted VM's cached address to an IDE as an
    // SSH target would look like a working connection and fail.
    mainIpAddress = str("mainIpAddress")?.takeIf { str("power_state") == "Running" },
    // A non-empty os_version means the guest is reporting, which is what actually matters, and it
    // is the only signal read: see the note on vmFields for the xenTools casing and type trap that
    // made the old fallback unreachable. Absent stays null, because "XO did not send the field" is
    // a different answer from "the guest is silent".
    guestIsReporting = (this["os_version"] as? JsonObject)?.isNotEmpty(),
)
