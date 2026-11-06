package com.bandersong.rytmmobile

/*
 * The support note's rules (rytm-mobile-corp/out/SPEC-nag.md). No Android here: NagPolicyTest runs it on the JVM.
 * The note asks for support. It never locks, slows or gates anything.
 */

/** The whole record, as kept in the "support" prefs file (NagCodec). Times in ms since the epoch; sessions count from 1. */
data class NagState(
    val version: Int = NagPolicy.VERSION,
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val sessions: Int = 0,
    val impressions: Int = 0,
    val lastShownSession: Int = 0,
    val notBefore: Long = 0L,
    val closedSession: Int = 0,
    val openedSession: Int = 0,
    val supporter: Boolean = false,
    val supporterAt: Long = 0L,
    val thanksSession: Int = 0,
)

/** What the rest of the app is doing right now. Any of these keeps the note away and spends nothing. */
data class NagMoment(
    val enabled: Boolean = true,        // BuildConfig.SUPPORT_NAG
    val foreground: Boolean = true,     // the process is started (SupportNote fills this in)
    val busy: Boolean = false,          // AppState.busy: connect, send, split, save, zip, delete, decode
    val recording: Boolean = false,     // the recording branch's state (SupportUi.kt recordingNow)
    val updating: Boolean = false,      // AppState.updateProgress >= 0f: an update is downloading
    val failed: Boolean = false,        // AppState.failed: the status line shows an error
    val updateOffered: Boolean = false, // AppState.update != null: the update banner holds the slot
    val cramped: Boolean = false,       // Screens.kt App(): compact
)

enum class NagMode { NOTICE, AFTER_SUPPORT, THANKS }

enum class NagWhy {
    UNREADABLE, OFF, BACKGROUND, SUPPORTER, ANSWERED,
    BUSY, RECORDING, UPDATING, FAILED, UPDATE_OFFERED, CRAMPED,
    FIRST_SESSION, NOT_DUE,
}

sealed interface NagVerdict {
    data class Show(val mode: NagMode) : NagVerdict
    object Grant : NagVerdict
    data class Hide(val why: NagWhy) : NagVerdict
}

class NagPolicy(private val clock: () -> Long) {
    /** The clock, never earlier than any time already seen: a clock set back cannot bring a note early. */
    fun now(s: NagState): Long = maxOf(clock(), s.lastSeenAt)

    /** The app came to the front. A session starts on the first one ever, or after SESSION_GAP away. */
    fun foreground(s: NagState): NagState {
        val t = now(s)
        val first = s.sessions == 0
        val next = first || t - s.lastSeenAt >= SESSION_GAP
        return s.copy(
            firstSeenAt = if (first) t else s.firstSeenAt,
            lastSeenAt = t,
            sessions = if (next) minOf(s.sessions + 1, MAX_COUNT) else s.sessions,
        )
    }

    /** The app went to the back. A quick return (file picker, share sheet) is then the same session. */
    fun background(s: NagState): NagState = if (s.sessions == 0) s else s.copy(lastSeenAt = now(s))

    fun decide(s: NagState, m: NagMoment): NagVerdict {
        if (!m.enabled) return NagVerdict.Hide(NagWhy.OFF)
        if (!m.foreground) return NagVerdict.Hide(NagWhy.BACKGROUND)
        val here = s.sessions
        val thanking = s.supporter && here > 0 && s.thanksSession == here && s.closedSession != here
        if (s.supporter && !thanking) return NagVerdict.Hide(NagWhy.SUPPORTER)
        val shownHere = s.impressions > 0 && s.lastShownSession == here
        if (here > 0 && s.closedSession == here) return NagVerdict.Hide(NagWhy.ANSWERED)
        if (here > 0 && s.openedSession == here && !shownHere && !thanking) return NagVerdict.Hide(NagWhy.ANSWERED)
        quiet(m)?.let { return NagVerdict.Hide(it) }
        if (thanking) return NagVerdict.Show(NagMode.THANKS)
        if (shownHere) return NagVerdict.Show(if (s.openedSession == here) NagMode.AFTER_SUPPORT else NagMode.NOTICE)
        if (here <= 1) return NagVerdict.Hide(NagWhy.FIRST_SESSION)
        return if (due(s)) NagVerdict.Grant else NagVerdict.Hide(NagWhy.NOT_DUE)
    }

    fun due(s: NagState): Boolean {
        val t = now(s)
        return if (s.impressions == 0) s.sessions >= FIRST_MIN_SESSIONS && t - s.firstSeenAt >= FIRST_MIN_AGE
        else s.sessions - s.lastShownSession >= REPEAT_MIN_SESSIONS && t >= s.notBefore
    }

    fun grant(s: NagState): NagState {
        val t = now(s)
        val n = minOf(s.impressions + 1, MAX_COUNT)
        return s.copy(impressions = n, lastShownSession = s.sessions, notBefore = t + cooldownAfter(n), lastSeenAt = t)
    }

    fun later(s: NagState): NagState = s.copy(closedSession = s.sessions, lastSeenAt = now(s))

    fun openedSupport(s: NagState): NagState = s.copy(openedSession = s.sessions, lastSeenAt = now(s))

    fun supported(s: NagState, fromCard: Boolean): NagState {
        val t = now(s)
        return s.copy(
            supporter = true, supporterAt = if (s.supporter) s.supporterAt else t,
            thanksSession = if (fromCard) s.sessions else s.thanksSession, lastSeenAt = t,
        )
    }

    private fun quiet(m: NagMoment): NagWhy? = when {
        m.busy -> NagWhy.BUSY
        m.recording -> NagWhy.RECORDING
        m.updating -> NagWhy.UPDATING
        m.failed -> NagWhy.FAILED
        m.updateOffered -> NagWhy.UPDATE_OFFERED
        m.cramped -> NagWhy.CRAMPED
        else -> null
    }

    companion object {
        const val VERSION = 1
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        const val SESSION_GAP = 30 * MINUTE
        const val FIRST_MIN_SESSIONS = 4
        const val FIRST_MIN_AGE = 3 * DAY
        const val REPEAT_MIN_SESSIONS = 3
        val COOLDOWN = longArrayOf(14 * DAY, 30 * DAY, 60 * DAY, 90 * DAY)
        const val MAX_COUNT = 1_000_000
        fun cooldownAfter(impression: Int): Long = COOLDOWN[(impression - 1).coerceIn(0, COOLDOWN.lastIndex)]
    }
}

/** The record <-> the prefs map (SharedPreferences.getAll()). Strict: anything it can't vouch for reads as null. */
object NagCodec {
    val INTS = listOf("v", "sessions", "impressions", "lastShownSession", "closedSession", "openedSession", "thanksSession")
    val LONGS = listOf("firstSeenAt", "lastSeenAt", "notBefore", "supporterAt")
    const val BOOL = "supporter"
    val KEYS: Set<String> = (INTS + LONGS + BOOL).toSet()

    fun toMap(s: NagState): Map<String, Any> = mapOf(
        "v" to s.version, "firstSeenAt" to s.firstSeenAt, "lastSeenAt" to s.lastSeenAt, "sessions" to s.sessions,
        "impressions" to s.impressions, "lastShownSession" to s.lastShownSession, "notBefore" to s.notBefore,
        "closedSession" to s.closedSession, "openedSession" to s.openedSession, "supporter" to s.supporter,
        "supporterAt" to s.supporterAt, "thanksSession" to s.thanksSession,
    )

    /** Empty: a fresh record. null: unreadable (another version, a missing or extra key, a wrong type, an impossible value). */
    fun read(m: Map<String, *>): NagState? {
        if (m.isEmpty()) return NagState()
        if (m.keys != KEYS) return null
        val i = INTS.associateWith { m[it] as? Int ?: return null }
        val l = LONGS.associateWith { m[it] as? Long ?: return null }
        val b = m[BOOL] as? Boolean ?: return null
        if (i.getValue("v") != NagPolicy.VERSION) return null
        val s = NagState(
            i.getValue("v"), l.getValue("firstSeenAt"), l.getValue("lastSeenAt"), i.getValue("sessions"),
            i.getValue("impressions"), i.getValue("lastShownSession"), l.getValue("notBefore"), i.getValue("closedSession"),
            i.getValue("openedSession"), b, l.getValue("supporterAt"), i.getValue("thanksSession"),
        )
        return s.takeIf(::valid)
    }

    fun valid(s: NagState): Boolean {
        val marks = listOf(s.lastShownSession, s.closedSession, s.openedSession, s.thanksSession)
        return listOf(s.firstSeenAt, s.lastSeenAt, s.notBefore, s.supporterAt).all { it >= 0 } &&
            s.sessions in 0..NagPolicy.MAX_COUNT && s.impressions in 0..s.sessions &&
            marks.all { it in 0..s.sessions } && s.firstSeenAt <= s.lastSeenAt &&
            (s.impressions == 0) == (s.lastShownSession == 0)
    }
}
