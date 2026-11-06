package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NagCodecTest {
    private val s = NagState(
        firstSeenAt = 1_000L, lastSeenAt = 5_000L, sessions = 11, impressions = 3, lastShownSession = 7,
        notBefore = 9_000L, closedSession = 5, openedSession = 8, supporter = true, supporterAt = 3_000L, thanksSession = 9,
    )  // every field distinct, so a positional mix-up in NagCodec.read cannot cancel out

    @Test fun emptyMapIsAFreshRecord() {
        assertEquals(NagState(), NagCodec.read(emptyMap<String, Any>()))
    }

    @Test fun roundTripKeepsEveryField() {
        assertTrue(NagCodec.valid(s))
        assertEquals(s, NagCodec.read(NagCodec.toMap(s)))
        assertEquals(NagCodec.KEYS, NagCodec.toMap(s).keys)
        assertEquals(12, NagCodec.KEYS.size)
    }

    @Test fun otherVersionIsUnreadable() {
        assertNull(NagCodec.read(NagCodec.toMap(s) + ("v" to 2)))
    }

    @Test fun missingOrExtraKeyIsUnreadable() {
        assertNull(NagCodec.read(NagCodec.toMap(s) - "sessions"))
        assertNull(NagCodec.read(NagCodec.toMap(s) + ("x" to 1)))
    }

    @Test fun wrongTypeIsUnreadable() {
        assertNull(NagCodec.read(NagCodec.toMap(s) + ("sessions" to 3L)))
        // same value, wrong type: an Int key holding a Long and a Long key holding an Int
        assertNull(NagCodec.read(NagCodec.toMap(s) + ("sessions" to s.sessions.toLong())))
        assertNull(NagCodec.read(NagCodec.toMap(s) + ("notBefore" to s.notBefore.toInt())))
        assertNull(NagCodec.read(NagCodec.toMap(s) + ("supporter" to "true")))
    }

    @Test fun impossibleValuesAreUnreadable() {
        val bad = listOf(
            s.copy(sessions = -1), s.copy(impressions = s.sessions + 1), s.copy(closedSession = s.sessions + 1),
            s.copy(firstSeenAt = s.lastSeenAt + 1), s.copy(impressions = 0, lastShownSession = 2),
            s.copy(impressions = 3, lastShownSession = 0), s.copy(notBefore = -1L), s.copy(lastSeenAt = -1L, firstSeenAt = -2L),
            s.copy(supporterAt = -1L), s.copy(lastShownSession = s.sessions + 1), s.copy(openedSession = s.sessions + 1),
            s.copy(thanksSession = s.sessions + 1), s.copy(closedSession = -1), s.copy(sessions = NagPolicy.MAX_COUNT + 1),
        )
        for (b in bad) assertNull("$b", NagCodec.read(NagCodec.toMap(b)))
    }

    @Test fun supportUrlIsHttpsWithAHost() {
        val u = java.net.URI(BuildConfig.SUPPORT_URL)
        assertEquals("https", u.scheme)
        assertTrue(!u.host.isNullOrBlank())
    }
}
