package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun keepsHttpAndHttps() {
        assertEquals("http://10.0.2.2:8765", AppState.serverUrl("http://10.0.2.2:8765"))
        assertEquals("https://mac.tail1234.ts.net:8765", AppState.serverUrl(" https://mac.tail1234.ts.net:8765/ "))
    }
    @Test fun addsHttpWhenTypedBare() {
        assertEquals("http://100.64.1.2:8765", AppState.serverUrl("100.64.1.2:8765"))
        assertEquals("http://my-mac.local:8765", AppState.serverUrl("my-mac.local:8765"))
    }
    @Test fun rejectsOtherSchemesAndJunk() {
        assertNull(AppState.serverUrl(""))
        assertNull(AppState.serverUrl("file:///etc/hosts"))
        assertNull(AppState.serverUrl("ftp://10.0.2.2"))
        assertNull(AppState.serverUrl("javascript:alert(1)"))
        assertNull(AppState.serverUrl("http://"))
        assertNull(AppState.serverUrl("http://bad host"))
    }
}
