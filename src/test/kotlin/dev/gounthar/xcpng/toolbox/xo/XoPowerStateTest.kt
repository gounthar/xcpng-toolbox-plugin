package dev.gounthar.xcpng.toolbox.xo

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Which verb powers a VM on from each state.
 *
 * This exists because getting it wrong is silent in the worst way: XO answers a `start` on a
 * suspended VM with a 500 rather than a no-op, and the user reads that as a broken plugin. The
 * mapping lives in one place precisely so it can be checked in one place.
 */
class XoPowerStateTest {

    @Test
    fun `every state that can be powered on has a verb`() {
        assertNotNull(XoPowerState.HALTED.resumeVerb)
        assertNotNull(XoPowerState.SUSPENDED.resumeVerb)
        assertNotNull(XoPowerState.PAUSED.resumeVerb)
    }

    @Test
    fun `a running VM has no verb, so no action is offered`() {
        assertNull(XoPowerState.RUNNING.resumeVerb)
    }

    /** Unknown means XO said something this build does not recognise. Guessing would be a 500. */
    @Test
    fun `an unknown state has no verb rather than a default`() {
        assertNull(XoPowerState.UNKNOWN.resumeVerb)
    }

    /**
     * The three verbs are three distinct XO endpoints, not aliases. Asserting they are different
     * function references is what would catch a copy-paste that pointed two states at `start`.
     */
    @Test
    fun `the three verbs are distinct`() {
        val verbs = listOf(XoPowerState.HALTED, XoPowerState.SUSPENDED, XoPowerState.PAUSED)
            .map { it.resumeVerb }
        check(verbs.toSet().size == verbs.size) { "two states share a verb: $verbs" }
    }
}
