package dev.gounthar.xcpng.toolbox.xo

/**
 * The slice of Xen Orchestra this plugin needs, named in plugin terms rather than XO terms.
 *
 * Deliberately small, and deliberately an interface, for the same two reasons the Jenkins
 * cloud plugin gives for [io.jenkins.plugins.xcpng.client.HypervisorClient]: the provider is
 * testable against a fake pool, and the transport stays swappable. XO's REST API is the
 * intended implementation; XAPI could sit beside it later.
 *
 * If this interface starts growing, the plugin is probably reaching for pool management that
 * belongs to the operator rather than to an IDE. The lifecycle verbs below are the exception
 * that earns its place: surfacing them on an environment is the reason Toolbox was chosen over
 * Gateway, since Toolbox gives a provider no start/stop of its own.
 *
 * The verbs mirror XO's own action names one-for-one rather than being collapsed into a single
 * "power on". That is forced by the API: `GET /rest/v0/vms/{id}/actions` lists `start`, `resume`
 * and `unpause` as three distinct endpoints, because a halted, a suspended and a paused VM are
 * resumed by three different XAPI calls. Deciding which applies is the caller's job, and it is
 * done in exactly one place: [XoPowerState.resumeVerb].
 */
interface XoClient : AutoCloseable {

    /** Verify connectivity and credentials. Throws on failure so a settings page can say why. */
    fun ping()

    /** Every VM the authenticated user can see. */
    suspend fun listVms(): List<XoVm>

    /**
     * One VM, re-read from the pool. Null when it is gone.
     *
     * This is what an environment calls after acting on itself, so the state it shows comes from
     * the pool rather than from an assumption about what the action did.
     */
    suspend fun getVm(uuid: String): XoVm?

    /** Power on a halted VM. */
    suspend fun start(vm: XoVm)

    /** Wake a suspended VM. Not the same call as [start]; see the note on this interface. */
    suspend fun resume(vm: XoVm)

    /** Un-pause a paused VM. Not the same call as [start]. */
    suspend fun unpause(vm: XoVm)

    /**
     * Ask the guest to shut itself down.
     *
     * **This needs a guest agent and fails without one**, which is not the rare case it sounds
     * like: on the lab pool only 1 of 4 running VMs was reporting to the host at all. That is why
     * [hardShutdown] is part of this interface rather than an advanced extra.
     */
    suspend fun cleanShutdown(vm: XoVm)

    /** Cut the power. The answer when [cleanShutdown] fails or the guest is not listening. */
    suspend fun hardShutdown(vm: XoVm)

    /**
     * Take a snapshot, returning its id when XO gives one. The reproducible-dev-environment verb,
     * and the reason a developer would want this plugin rather than the XO web UI in another
     * window.
     *
     * Nullable because the return is **measured, not specified**, and those disagree. XO's own
     * OpenAPI document (`@xen-orchestra/rest-api` 0.35.0) declares `201 Snapshot created` with no
     * response body at all; the running appliance answers 201 with `{"id": "<uuid>"}`, checked
     * against the lab pool on 2026-08-19. Reading it is worth it (it saves a [listSnapshots]
     * round trip), but an undocumented body is one an upgrade may withdraw without it being a
     * breaking change, so callers must cope with null rather than assume the id is there.
     */
    suspend fun snapshot(vm: XoVm, nameLabel: String): String?

    /** The snapshots taken of one VM, newest first. */
    suspend fun listSnapshots(vm: XoVm): List<XoSnapshot>

    /**
     * Roll a VM back to one of its snapshots.
     *
     * Note the shape, because the obvious guess is wrong and was wrong here until the spec was
     * read: the request goes to the **VM**, with the snapshot id in a required JSON body. It is
     * not a POST to the snapshot itself.
     */
    suspend fun revertSnapshot(vm: XoVm, snapshotId: String)

    /**
     * The VM's primary IP, if one is known yet.
     *
     * XO improves substantially on raw XAPI here, and it changes the design problem rather than
     * only restating it. `GET /vms` exposes `mainIpAddress` already resolved, so no walking of
     * `guest_metrics.networks`. It also exposes a guest-reporting signal, which is the piece XAPI
     * never gave us: "no guest agent installed" becomes distinguishable from "not booted yet",
     * instead of both looking like an empty address. That ambiguity is what cost the Jenkins
     * plugin a milestone (gounthar/xcpng-cloud-plugin#127).
     *
     * Still returns null for a VM whose guest is not reporting, so a template requirement or the
     * DHCP-lease path from gounthar/clawk#15 remains a real decision. It is now an informed one.
     */
    suspend fun primaryIpAddress(vm: XoVm): String?

    override fun close()
}

/**
 * An opaque handle plus the few fields the UI shows. Callers do not parse [uuid].
 *
 * Field names follow XO's own schema (`Unbrand_XoVm_` in its OpenAPI document) rather than
 * XAPI's, so `mainIpAddress` is XO's spelling.
 */
data class XoVm(
    val uuid: String,
    val nameLabel: String,
    val powerState: XoPowerState,
    /**
     * Already resolved by XO, and **only populated while the VM is running**.
     *
     * XO serves the last known address for a stopped VM, which is worse than serving nothing: it
     * looks usable. Measured on the lab pool, 4 of 6 halted VMs reported one. [XoRestClient]
     * drops it unless `power_state` is Running.
     */
    val mainIpAddress: String? = null,
    /**
     * Whether the guest is reporting to the host at all, which is the question that decides
     * whether an address will ever arrive. Null when XO does not say.
     *
     * Named for the observable rather than for guest tools, because "tools installed" and "guest
     * reporting" came apart in the lab data: `pvDriversDetected` was true on VMs with no address.
     */
    val guestIsReporting: Boolean? = null,
)

/**
 * A VM snapshot, from XO's `Unbrand_XoVmSnapshot_` schema.
 *
 * [takenAt] is XO's `snapshot_time`, seconds since the epoch. It is only used for ordering, so
 * it stays a raw number rather than becoming a date: `java.time` formatting would need a locale
 * and a zone decision that belongs to a UI this plugin does not have yet.
 */
data class XoSnapshot(
    val id: String,
    val nameLabel: String,
    val takenAt: Long,
)

/** Mirrors XO's `VM_POWER_STATE` enum: Halted, Paused, Running, Suspended. */
enum class XoPowerState { HALTED, RUNNING, PAUSED, SUSPENDED, UNKNOWN }

/**
 * Which XO verb powers a VM on from this state, or null when it is already on or unknown.
 *
 * The whole point of putting this here is that there is one such mapping in the plugin. Getting
 * it wrong is silent: XO answers a `start` on a suspended VM with an error the user reads as
 * "the plugin is broken" rather than "wrong verb".
 */
val XoPowerState.resumeVerb: (suspend (XoClient, XoVm) -> Unit)?
    get() = when (this) {
        XoPowerState.HALTED -> XoClient::start
        XoPowerState.SUSPENDED -> XoClient::resume
        XoPowerState.PAUSED -> XoClient::unpause
        XoPowerState.RUNNING, XoPowerState.UNKNOWN -> null
    }
