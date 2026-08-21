package dev.gounthar.xcpng.toolbox.xo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * The self-signed-certificate checkbox, by its exact on-screen label.
 *
 * Shared rather than written twice, because [xoUnreachableMessage] tells the reader to tick a
 * control and naming it wrongly sends them looking for something that is not there. That is not a
 * hypothetical failure in this repo: `readableName` and the provider's constructor argument were
 * two independent strings for the same idea, one was changed, and the other kept rendering the old
 * value through a merged pull request — see PROVIDER_NAME.
 *
 * It lives here rather than beside the checkbox because the message that names it is here, and
 * this package cannot import the UI one. `PoolSettingsPage` builds the checkbox from it.
 */
internal const val SELF_SIGNED_CHECKBOX_LABEL = "Accept a self-signed certificate"

/**
 * What to tell somebody when Xen Orchestra refuses a read.
 *
 * This exists because the message it replaces named the two things that were fine. `ping()` used
 * to report every non-200 as "Check the URL and token", and the failure that prompted this is one
 * where both are correct and neither can be changed to fix it: on an unlicensed appliance the
 * whole non-admin REST surface answers 403 on a **licence** check rather than a permission one.
 * Sending the reader back to the URL and the token there costs them the evening.
 *
 * Every branch below is measured against the lab appliance (`@xen-orchestra/rest-api` 0.35.0,
 * xo-server 5.205.2) rather than read off the OpenAPI document, because the document describes
 * none of it. Bodies, verbatim, 2026-08-20:
 *
 * - **401** — `{"error": "invalid credentials"}`, for a malformed token and for no token at all.
 * - **403, licence gate** — `{"error": "feature Unauthorized", "data": {"featureCode": "RBAC",
 *   "currentPlan": 1, "minPlan": 3}}`. Reproduced here as **admin** on `/acl-roles`, so it is not
 *   a non-admin phenomenon and a plugin will meet it with a perfectly good token.
 * - **403, permission** — `{"error": "not enough permissions"}`, with no `data` object. Recorded
 *   on `/dashboard` with a non-admin token; that token has since been revoked, so this branch is
 *   keyed on the *absence* of `featureCode` rather than on matching that string. XO distinguishing
 *   the two is the finding, and keying on the discriminator rather than the wording is what keeps
 *   the split working if the wording moves.
 * - **404** — an HTML `Cannot GET /rest/v0/...` page, not JSON. Which is itself the signal: it
 *   means the request never reached a route, so the base URL is the thing to look at.
 */
internal fun xoFailureMessage(what: String, status: Int, body: String): String {
    val root = runCatching { errorJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val error = root?.str("error")
    val data = root?.get("data") as? JsonObject
    val featureCode = data?.str("featureCode")

    // Said once, up front, everywhere Xen Orchestra actually answered. The one branch that omits
    // it is the one where it would be a lie: an unparseable 404 is the case where the request
    // never reached a Xen Orchestra route, so nothing is known about what is at that address.
    val answered = "Xen Orchestra returned $status for $what."

    return when {
        status == 401 ->
            "$answered The token was rejected. The appliance answered, so the URL is right; the " +
                "token is wrong, revoked, or expired."

        status == 403 && featureCode != null -> {
            val plans = planNote(data.str("currentPlan"), data.str("minPlan"))
            "$answered This appliance's licence does not cover its $featureCode feature$plans. " +
                "The URL and the token are both correct and changing either will not help."
        }

        status == 403 ->
            "$answered The account this token belongs to is not allowed on that route. The URL " +
                "and the token are both valid; it is the account's privileges that are short."

        status == 404 && root == null ->
            "$what answered $status with a page rather than one of Xen Orchestra's JSON errors, " +
                "so the request never reached a Xen Orchestra route. The base URL should be the " +
                "appliance's own address with no path after it, such as https://xoa.example.com; " +
                "the plugin appends /rest/v0 itself."

        error != null -> "$answered $error"

        else -> "$answered ${body.take(200).ifBlank { "No detail was returned." }}"
    }
}

/**
 * The plan numbers, when XO gave both.
 *
 * Reported as bare integers, and what they mean is xo-server's own enum rather than anything
 * published: `FREE=1, STARTER=2, ENTERPRISE=3, PREMIUM=4`, read out of the installed
 * `dist/xo-mixins/authorization.mjs`. They are passed through as numbers on purpose. Naming them
 * would mean shipping a copy of an internal enum that this plugin cannot see change, and getting
 * that wrong would be worse than the digits, which the reader can hand to Vates verbatim.
 */
private fun planNote(current: String?, min: String?): String = when {
    current != null && min != null -> " (it reports plan $current, and that route needs plan $min)"
    else -> ""
}

/**
 * Why the appliance could not be reached at all, for a failure that never got a status code.
 *
 * [xoFailureMessage] is the other half of this and starts where this one stops: it explains a
 * refusal Xen Orchestra *sent*, and needs a response to read. Everything below happens before
 * there is one — DNS, TCP, TLS — and arrives as an exception out of `HttpURLConnection` whose own
 * message is usually a bare hostname or `Connection refused`, which names the symptom and no
 * cause. That is fine in a log and useless on a settings form, where the person reading it has
 * every input that could be at fault on screen in front of them.
 *
 * **Keyed on the exception type, never on its message.** Same reasoning as the `featureCode`
 * discriminator in [xoFailureMessage]: a JDK message is not API and can be reworded between
 * releases, while `UnknownHostException` cannot stop meaning what it means. The `when` runs
 * specific-to-general because all of these except [SSLException] descend from [IOException].
 *
 * The certificate branch is the one that earns this function. XOA ships a self-signed certificate,
 * so a first connection failing on TLS is the *expected* first experience rather than an edge
 * case, and the fix is a checkbox on the same form. Saying so beats `PKIX path building failed`.
 */
internal fun xoUnreachableMessage(baseUrl: String, failure: Throwable): String = when (failure) {
    is UnknownHostException ->
        "No host by that name. Nothing answered a DNS lookup for the address in $baseUrl, so " +
            "check it for a typo before looking at anything else."

    // Before SSLException's siblings, and before IOException: SSLHandshakeException is what a
    // self-signed certificate actually throws, and it is an SSLException, which is an IOException.
    is SSLException ->
        "The appliance answered but its certificate was not trusted. XOA ships a self-signed " +
            "certificate, so this is the ordinary first connection rather than a sign of " +
            "anything wrong. Tick \"$SELF_SIGNED_CHECKBOX_LABEL\" on this page. " +
            "(${failure.message ?: failure::class.java.simpleName})"

    is ConnectException ->
        "Nothing is listening at $baseUrl. The name resolved, so the address is reachable and " +
            "the port is not open. Check the scheme and the port rather than the hostname."

    is NoRouteToHostException ->
        "No route to $baseUrl. The address resolved and nothing refused the connection either, " +
            "which usually means a firewall or a network this machine is not on."

    is SocketTimeoutException ->
        "$baseUrl did not answer in time. Nothing refused the connection, so something is " +
            "dropping it silently rather than saying no: a firewall, or the wrong host entirely."

    is MalformedURLException ->
        "$baseUrl is not a URL this plugin can build a request from. It should be the " +
            "appliance's own address and nothing more, such as https://xoa.example.com."

    is IOException ->
        "Could not reach $baseUrl. ${failure.message ?: failure::class.java.simpleName}"

    // Not a transport failure. `ping()` throws IllegalStateException carrying whatever
    // xoFailureMessage produced, and that text is already the best available answer, so it is
    // passed through rather than wrapped in a second sentence about connectivity.
    else -> failure.message?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName
}
