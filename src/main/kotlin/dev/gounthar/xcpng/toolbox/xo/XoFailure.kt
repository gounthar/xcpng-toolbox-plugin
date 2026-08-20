package dev.gounthar.xcpng.toolbox.xo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val errorJson = Json { ignoreUnknownKeys = true }

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
