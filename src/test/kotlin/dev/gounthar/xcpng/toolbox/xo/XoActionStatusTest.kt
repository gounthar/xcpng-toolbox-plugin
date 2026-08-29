package dev.gounthar.xcpng.toolbox.xo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which HTTP statuses an action is allowed to answer with.
 *
 * This exists to record a decision rather than to guard an algorithm. The rule was previously the
 * range `200..204`, which is the shape a reader cannot interrogate: it does not say whether 202
 * was thought about, and 202 is the one status here that does not mean the work is done.
 */
class XoActionStatusTest {

    /** 204 is what XO sends for a completed action under `?sync=true`. */
    @Test
    fun `the done answers succeed`() {
        assertTrue(actionSucceeded(200))
        assertTrue(actionSucceeded(201))
        assertTrue(actionSucceeded(204))
    }

    /**
     * The decision this test exists for.
     *
     * 202 means XO queued the work despite `?sync=true`, so the verb is accepted and not finished.
     * It is not a failure here because every action is followed by `refreshSelf()`, which re-reads
     * the pool and republishes the real power state. Change this to `assertFalse` only along with
     * that guarantee, because on its own it turns an accepted action into a popup saying it broke.
     */
    @Test
    fun `202 is accepted deliberately, because refreshSelf reconciles the real state`() {
        assertTrue(actionSucceeded(202), "202 is queued, not failed: refreshSelf re-reads the pool")
    }

    /** Accepted by the old range without anybody deciding to. XO does not send it. */
    @Test
    fun `203 is not pre-approved`() {
        assertFalse(actionSucceeded(203))
    }

    /**
     * `VM_BAD_POWER_STATE` arrives as a 500, and a power verb in the wrong state is an ordinary
     * race rather than a fault: the environment list is only as fresh as its last refresh.
     */
    @Test
    fun `the failure statuses this plugin actually sees are failures`() {
        assertFalse(actionSucceeded(404))
        assertFalse(actionSucceeded(403))
        assertFalse(actionSucceeded(422))
        assertFalse(actionSucceeded(500))
    }

    @Test
    fun `nothing outside 2xx succeeds`() {
        for (status in 100..199) assertFalse(actionSucceeded(status), "$status")
        for (status in 300..599) assertFalse(actionSucceeded(status), "$status")
    }

    /** 205 through 299 are not answers XO gives, and none of them is pre-approved either. */
    @Test
    fun `the rest of 2xx is not accepted`() {
        assertFalse(actionSucceeded(205))
        assertFalse(actionSucceeded(206))
        assertFalse(actionSucceeded(299))
    }
}
