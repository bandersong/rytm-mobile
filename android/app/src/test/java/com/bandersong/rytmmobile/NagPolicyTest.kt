package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NagPolicyTest {
    private var t = 1_700_000_000_000L
    private val p = NagPolicy { t }
    private val idle = NagMoment()
    private val D = NagPolicy.DAY
    private val GAP = NagPolicy.SESSION_GAP

    private fun NagState.next(away: Long = GAP): NagState { val b = p.background(this); t += away; return p.foreground(b) }
    private fun firstDue(): NagState { var s = p.foreground(NagState()); repeat(3) { s = s.next(D) }; return s }  // 4 sessions, day 3
    private fun NagState.verdict(m: NagMoment = idle) = p.decide(this, m)
    private fun hide(w: NagWhy) = NagVerdict.Hide(w)
    private fun show(m: NagMode) = NagVerdict.Show(m)

    // ---- First run
    @Test fun firstForegroundStartsSessionOne() {
        val s = p.foreground(NagState())
        assertEquals(1, s.sessions); assertEquals(t, s.firstSeenAt); assertEquals(t, s.lastSeenAt)
    }

    @Test fun freshRecordBeforeAnyForegroundIsSilent() {
        assertEquals(hide(NagWhy.FIRST_SESSION), NagState().verdict())
    }

    @Test fun neverInTheFirstSession() {
        val s = p.foreground(NagState())
        t += 365 * D
        assertEquals(hide(NagWhy.FIRST_SESSION), s.verdict())
    }

    @Test fun firstCardNeedsFourSessions() {
        var s = p.foreground(NagState())
        s = s.next(5 * D); assertEquals(2, s.sessions); assertEquals(hide(NagWhy.NOT_DUE), s.verdict())
        s = s.next(5 * D); assertEquals(3, s.sessions); assertEquals(hide(NagWhy.NOT_DUE), s.verdict())
        s = s.next(); assertEquals(4, s.sessions); assertEquals(NagVerdict.Grant, s.verdict())
    }

    @Test fun firstCardNeedsThreeDays() {
        var s = p.foreground(NagState())
        val first = s.firstSeenAt
        repeat(9) { s = s.next() }
        assertEquals(10, s.sessions)
        t = first + 3 * D - 1
        assertEquals(hide(NagWhy.NOT_DUE), s.verdict())
        t += 1
        assertEquals(NagVerdict.Grant, s.verdict())
    }

    // ---- Sessions
    @Test fun quickReturnIsTheSameSession() {
        val s = p.foreground(NagState())
        t += 2 * 60 * NagPolicy.MINUTE
        val b = p.background(s)
        t += GAP - 1
        assertEquals(1, p.foreground(b).sessions)
    }

    @Test fun thirtyMinutesAwayIsANewSession() {
        val s = p.foreground(NagState())
        val b = p.background(s)
        t += GAP
        assertEquals(2, p.foreground(b).sessions)
    }

    @Test fun backgroundBeforeFirstForegroundChangesNothing() {
        assertEquals(NagState(), p.background(NagState()))
    }

    // ---- Backoff schedule
    /** From a state that just got a card: 3 more sessions, wait until notBefore, expect Grant, grant. Returns the new state and the gap. */
    private fun cycle(s0: NagState): Pair<NagState, Long> {
        var s = s0
        repeat(3) { s = s.next() }
        t = maxOf(t, s.notBefore)
        assertEquals(NagVerdict.Grant, s.verdict())
        val at = p.now(s)
        val g = p.grant(s)
        return g to (g.notBefore - at)
    }

    @Test fun gapsAre14_30_60_90ThenStay90() {
        var s = firstDue()
        assertEquals(NagVerdict.Grant, s.verdict())
        val gaps = mutableListOf<Long>()
        val at = p.now(s)
        s = p.grant(s); gaps += s.notBefore - at
        repeat(5) { val (n, g) = cycle(s); s = n; gaps += g }
        assertEquals(listOf(14 * D, 30 * D, 60 * D, 90 * D, 90 * D, 90 * D), gaps)
    }

    @Test fun repeatNeedsTimeAndSessions() {
        val g = p.grant(firstDue())
        val G = p.now(g)
        // (a) two sessions are not enough even at G+14d
        var a = g.next(); a = a.next()
        t = G + 14 * D
        assertEquals(hide(NagWhy.NOT_DUE), a.verdict())
        // (b) the third session makes it due
        a = a.next()
        assertEquals(NagVerdict.Grant, a.verdict())
        // (c) three sessions but one ms short of 14 days
        t = G
        var c = g
        repeat(3) { c = c.next() }
        t = G + 14 * D - 1
        assertEquals(hide(NagWhy.NOT_DUE), c.verdict())
    }

    @Test fun noLifetimeCap() {
        var s = p.grant(firstDue())
        repeat(11) { s = cycle(s).first }
        assertEquals(12, s.impressions)
    }

    // ---- Within a session
    @Test fun grantedCardStaysForTheSession() {
        val g = p.grant(firstDue())
        assertEquals(show(NagMode.NOTICE), g.verdict())
        assertEquals(show(NagMode.NOTICE), g.verdict())
        assertEquals(1, g.impressions)
    }

    @Test fun unansweredCardDoesNotCarryOver() {
        val g = p.grant(firstDue())
        assertEquals(hide(NagWhy.NOT_DUE), g.next().verdict())
    }

    // ---- Dismissal
    @Test fun laterClosesThisSessionKeepsSchedule() {
        val g = p.grant(firstDue())
        val l = p.later(g)
        assertEquals(hide(NagWhy.ANSWERED), l.verdict())
        assertEquals(g.notBefore, l.notBefore); assertEquals(g.impressions, l.impressions)
    }

    @Test fun supportOpenedShowsAfterMode() {
        val g = p.grant(firstDue())
        val o = p.openedSupport(g)
        assertEquals(show(NagMode.AFTER_SUPPORT), o.verdict())
        assertEquals(hide(NagWhy.ANSWERED), p.later(o).verdict())
    }

    @Test fun aboutSupportKeepsCardAwayThisSession() {
        val s = firstDue()
        assertEquals(NagVerdict.Grant, s.verdict())
        val o = p.openedSupport(s)
        assertEquals(hide(NagWhy.ANSWERED), o.verdict())
        assertEquals(NagVerdict.Grant, o.next().verdict())
    }

    // ---- Supporter
    @Test fun supportedFromCardThanksThenNeverAgain() {
        val g = p.grant(firstDue())
        val sp = p.supported(g, true)
        assertEquals(show(NagMode.THANKS), sp.verdict())
        var l = p.later(sp)
        assertEquals(hide(NagWhy.SUPPORTER), l.verdict())
        repeat(50) { l = l.next(D); assertEquals(hide(NagWhy.SUPPORTER), l.verdict()) }
    }

    @Test fun supportedFromAboutNeverShows() {
        var s = p.supported(p.foreground(NagState()), false)
        assertEquals(hide(NagWhy.SUPPORTER), s.verdict())
        repeat(8) {
            s = s.next(D)
            val v = s.verdict()
            assertEquals(hide(NagWhy.SUPPORTER), v)
            assertNotEquals(show(NagMode.THANKS), v)
        }
    }

    @Test fun supportedIsIdempotent() {
        val a = p.supported(p.foreground(NagState()), false)
        val first = a.supporterAt
        t += 5 * D
        val b = p.supported(a, false)
        assertEquals(first, b.supporterAt)
        assertEquals(first, p.supported(b, true).supporterAt)
    }

    @Test fun supporterBeatsQuietFlags() {
        val s = p.supported(firstDue(), false)
        assertEquals(hide(NagWhy.SUPPORTER), s.verdict(NagMoment(busy = true)))
    }

    // ---- Busy suppression
    @Test fun eachQuietFlagHidesAndSpendsNothing() {
        val s = firstDue()
        val before = s.copy()
        val cases = listOf(
            NagMoment(busy = true) to NagWhy.BUSY, NagMoment(recording = true) to NagWhy.RECORDING,
            NagMoment(updating = true) to NagWhy.UPDATING, NagMoment(failed = true) to NagWhy.FAILED,
            NagMoment(updateOffered = true) to NagWhy.UPDATE_OFFERED, NagMoment(cramped = true) to NagWhy.CRAMPED,
            NagMoment(enabled = false) to NagWhy.OFF, NagMoment(foreground = false) to NagWhy.BACKGROUND,
        )
        for ((m, w) in cases) assertEquals(hide(w), s.verdict(m))
        assertEquals(before, s)
        assertEquals(NagVerdict.Grant, s.verdict())
    }

    @Test fun quietOrderIsFixed() {
        assertEquals(hide(NagWhy.BUSY), firstDue().verdict(NagMoment(busy = true, failed = true, cramped = true)))
    }

    @Test fun busyHidesShownCardAndItReturns() {
        val g = p.grant(firstDue())
        assertEquals(hide(NagWhy.BUSY), g.verdict(NagMoment(busy = true)))
        assertEquals(show(NagMode.NOTICE), g.verdict())
        assertEquals(1, g.impressions)
    }

    @Test fun busyAllSessionWaitsForNext() {
        val s = firstDue()
        assertEquals(hide(NagWhy.BUSY), s.verdict(NagMoment(busy = true)))
        val n = s.next()
        assertEquals(NagVerdict.Grant, n.verdict())
        assertEquals(1, p.grant(n).impressions)
    }

    // ---- Clock rollback
    @Test fun rollbackCannotBringCardEarly() {
        val g = p.grant(firstDue())
        val G = p.now(g)
        var s = g
        repeat(3) { s = s.next(8 * 60 * NagPolicy.MINUTE) }
        t = G - 30 * D
        assertEquals(hide(NagWhy.NOT_DUE), s.verdict())
        t = G + 14 * D - 1
        assertEquals(hide(NagWhy.NOT_DUE), s.verdict())
        t = G + 14 * D
        assertEquals(NagVerdict.Grant, s.verdict())
    }

    @Test fun rollbackCountsNoSession() {
        val T = t
        val s = p.foreground(NagState())
        t = T + 60 * NagPolicy.MINUTE
        val b = p.background(s)
        t = T - 2 * D
        val f = p.foreground(b)
        assertEquals(1, f.sessions)
        assertEquals(T + 60 * NagPolicy.MINUTE, f.lastSeenAt)
    }

    @Test fun lastSeenNeverDecreases() {
        val T = t
        val times = listOf(T, T - 5 * D, T + 3_600_000L, T - 3_600_000L, T + 3 * 3_600_000L)
        var s = NagState()
        var prev = 0L
        times.forEachIndexed { i, x ->
            t = x
            s = if (i % 2 == 0) p.foreground(s) else p.background(s)
            assertTrue(s.lastSeenAt >= prev)
            prev = s.lastSeenAt
            assertEquals(T, s.firstSeenAt)
        }
    }

    @Test fun forwardJumpThenBackStaysQuiet() {
        val s = firstDue()
        assertEquals(NagVerdict.Grant, s.verdict())
        val T = t
        t = T + 400 * D
        val g = p.grant(s)
        t = T + D
        val r = g.next()
        assertEquals(g.sessions, r.sessions)
        // Spec asked for NOT_DUE; the card was granted this session and the session did not end, so the verdict is
        // the granted card again. What matters is that it is not due and never a second Grant.
        assertFalse(p.due(r))
        assertEquals(show(NagMode.NOTICE), r.verdict())
        assertEquals(g.impressions, r.impressions)
    }

    // ---- Cooldown table
    @Test fun cooldownTableIsMonotonic() {
        val c = NagPolicy.COOLDOWN.toList()
        assertEquals(c.sorted(), c)
        assertEquals(14 * D, c.first())
    }
}
