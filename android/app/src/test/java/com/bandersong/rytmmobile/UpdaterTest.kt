package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdaterTest {
    @Test fun relativeApkResolvesNextToVersionJson() {
        val r = Updater.parse("""{"versionCode":6,"versionName":"1.2.0","apk":"RytmMobile-1.2.0.apk","sha256":"ABCD","notes":"hi"}""",
            "https://rytm-mobile.pages.dev/version.json")
        assertEquals(6, r.code)
        assertEquals("https://rytm-mobile.pages.dev/RytmMobile-1.2.0.apk", r.apk)
        assertEquals("abcd", r.sha256)
        assertEquals("hi", r.notes)
    }

    @Test fun absoluteApkUrlIsKept() {
        val r = Updater.parse("""{"versionCode":7,"versionName":"1.2.1","apk":"https://github.com/x/y/releases/download/v1.2.1/a.apk","sha256":"ff"}""",
            "https://rytm-mobile.pages.dev/version.json")
        assertEquals("https://github.com/x/y/releases/download/v1.2.1/a.apk", r.apk)
        assertEquals("", r.notes)
    }
}
