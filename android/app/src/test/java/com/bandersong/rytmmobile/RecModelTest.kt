package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun usb(id: Int, name: String = "Scarlett 2i2 USB Audio", rates: List<Int> = emptyList(), ch: List<Int> = emptyList(), isNew: Boolean = false) =
    InputInfo(id, InputKind.USB_DEVICE, name, rates, ch, isNew)
private fun mic(id: Int) = InputInfo(id, InputKind.BUILTIN_MIC, "", emptyList(), emptyList())
private fun inp(id: Int, kind: InputKind, name: String = "") = InputInfo(id, kind, name, emptyList(), emptyList())

class InputPickerTest {
    @Test fun visibleDropsBluetoothAndHidden() {
        val all = listOf(inp(1, InputKind.BLUETOOTH, "BT"), inp(2, InputKind.HIDDEN), mic(3), usb(4))
        assertEquals(listOf(4, 3), InputPicker.visible(all).map { it.id })
    }

    @Test fun visibleCollapsesBuiltinMics() {
        val v = InputPicker.visible(listOf(mic(3), mic(1)))
        assertEquals(1, v.size)
        assertEquals(1, v[0].id)
    }

    @Test fun rankOrder() {
        val order = listOf(InputKind.USB_DEVICE, InputKind.USB_HEADSET, InputKind.LINE_IN, InputKind.WIRED_HEADSET, InputKind.BUILTIN_MIC)
        for (k in 0 until order.size - 1) assertTrue(InputPicker.rank(order[k]) < InputPicker.rank(order[k + 1]))
        assertEquals(9, InputPicker.rank(InputKind.BLUETOOTH))
        assertEquals(9, InputPicker.rank(InputKind.HIDDEN))
        // and visible() applies it, then ties by id
        val v = InputPicker.visible(listOf(mic(1), inp(8, InputKind.WIRED_HEADSET), inp(7, InputKind.LINE_IN),
            inp(6, InputKind.USB_HEADSET), usb(9), usb(5)))
        assertEquals(listOf(5, 9, 6, 7, 8, 1), v.map { it.id })
    }

    @Test fun chooseTiesLowestId() {
        assertEquals(2, InputPicker.choose(listOf(usb(9), usb(2, "Other")), null)!!.id)
    }

    @Test fun chooseSelectedKeyWins() {
        val list = listOf(usb(5), mic(1))
        assertEquals(1, InputPicker.choose(list, "BUILTIN_MIC|")!!.id)
    }

    @Test fun chooseMissingKeyFallsBack() {
        assertEquals(5, InputPicker.choose(listOf(usb(5), mic(1)), "LINE_IN|gone")!!.id)
    }

    @Test fun chooseEmptyIsNull() {
        assertNull(InputPicker.choose(emptyList(), null))
        assertNull(InputPicker.choose(emptyList(), "BUILTIN_MIC|"))
    }

    @Test fun markNewFirstListingHasNone() {
        assertTrue(InputPicker.markNew(emptyList(), listOf(mic(1), usb(5))).none { it.isNew })
    }

    @Test fun markNewFlagsAddedKeys() {
        val r = InputPicker.markNew(listOf(mic(1)), listOf(mic(1), usb(9)))
        assertFalse(r.first { it.id == 1 }.isNew)
        assertTrue(r.first { it.id == 9 }.isNew)
    }

    @Test fun labels() {
        assertEquals("Phone mic", InputPicker.label(mic(1)))
        assertEquals("Headset mic", InputPicker.label(inp(2, InputKind.WIRED_HEADSET)))
        assertEquals("Line in", InputPicker.label(inp(3, InputKind.LINE_IN)))
        assertEquals("USB · Scarlett 2i2 USB", InputPicker.label(usb(4)))
        assertEquals("USB · audio", InputPicker.label(usb(5, "")))
        assertEquals("USB · audio", InputPicker.label(usb(5, "   ")))
        assertEquals("USB · Rode", InputPicker.label(inp(6, InputKind.USB_HEADSET, "Rode")))
    }

    @Test fun details() {
        assertEquals("Built‑in · mono", InputPicker.detail(mic(1)))
        assertEquals("Headset jack · mono", InputPicker.detail(inp(2, InputKind.WIRED_HEADSET)))
        assertEquals("Line input", InputPicker.detail(inp(3, InputKind.LINE_IN)))
        assertEquals("USB · up to 2 ch", InputPicker.detail(usb(4)))
        assertEquals("USB · up to 2 ch", InputPicker.detail(usb(4, ch = listOf(1, 2))))
        assertEquals("USB · mono", InputPicker.detail(usb(4, ch = listOf(1))))
        assertEquals("USB · up to 2 ch · NEW", InputPicker.detail(usb(4, isNew = true)))
        // the hyphen in Built‑in is U+2011, as in the spec
        assertEquals(0x2011, InputPicker.detail(mic(1))[5].code)
    }

    @Test fun autoSwitchTarget() {
        val cur = mic(1)
        val newUsb = usb(9, isNew = true)
        // userPicked: never
        assertNull(InputPicker.autoSwitchTarget(cur, listOf(cur, newUsb), true))
        // not new
        assertNull(InputPicker.autoSwitchTarget(cur, listOf(cur, usb(9)), false))
        // built-in only
        assertNull(InputPicker.autoSwitchTarget(cur, listOf(cur.copy(isNew = true)), false))
        // switch
        assertEquals(9, InputPicker.autoSwitchTarget(cur, listOf(cur, newUsb), false)!!.id)
        // already on it
        assertNull(InputPicker.autoSwitchTarget(newUsb, listOf(cur, newUsb), false))
        // a new wired headset is not a fast kind
        assertNull(InputPicker.autoSwitchTarget(cur, listOf(cur, inp(7, InputKind.WIRED_HEADSET).copy(isNew = true)), false))
        // a new line-in does switch
        assertEquals(7, InputPicker.autoSwitchTarget(cur, listOf(cur, inp(7, InputKind.LINE_IN).copy(isNew = true)), false)!!.id)
    }
}

class FormatLadderTest {
    private fun r(rate: Int, ch: Int) = Rung(rate, ch)

    @Test fun sourcesWithUnprocessed() {
        assertEquals(listOf(Source.UNPROCESSED, Source.VOICE_RECOGNITION, Source.MIC), FormatLadder.sources(true))
    }
    @Test fun sourcesWithout() {
        assertEquals(listOf(Source.VOICE_RECOGNITION, Source.MIC), FormatLadder.sources(false))
        assertEquals(3, Source.values().size)
    }

    @Test fun builtinRungs() {
        val want = listOf(r(48000, 1), r(44100, 1), r(0, 1))
        assertEquals(want, FormatLadder.rungs(mic(1)))
        assertEquals(want, FormatLadder.rungs(inp(2, InputKind.WIRED_HEADSET)))
    }

    @Test fun usbAnyRatesAnyChannels() {
        assertEquals(listOf(r(48000, 2), r(48000, 1), r(44100, 2), r(44100, 1), r(0, 2), r(0, 1)), FormatLadder.rungs(usb(5)))
    }

    @Test fun usbMonoOnly() {
        assertEquals(listOf(r(48000, 1), r(44100, 1), r(0, 1)), FormatLadder.rungs(usb(5, ch = listOf(1))))
    }

    @Test fun usbListedRates() {
        assertEquals(listOf(r(44100, 2), r(44100, 1), r(0, 2), r(0, 1)),
            FormatLadder.rungs(usb(5, rates = listOf(44100, 96000), ch = listOf(2))))
    }

    @Test fun usbNeitherRate() {
        assertEquals(listOf(r(96000, 2), r(96000, 1), r(0, 2), r(0, 1)),
            FormatLadder.rungs(usb(5, rates = listOf(32000, 96000))))
    }

    @Test fun lineInUsesTheUsbLadder() {
        assertEquals(FormatLadder.rungs(usb(5)), FormatLadder.rungs(inp(3, InputKind.LINE_IN)))
    }

    @Test fun planIsSourceMajor() {
        val p = FormatLadder.plan(mic(1), false, null)
        assertEquals(6, p.size)
        assertTrue(p.take(3).all { it.first == Source.VOICE_RECOGNITION })
        assertTrue(p.drop(3).all { it.first == Source.MIC })
        assertEquals(listOf(r(48000, 1), r(44100, 1), r(0, 1)), p.take(3).map { it.second })
        assertEquals(9, FormatLadder.plan(mic(1), true, null).size)
    }

    @Test fun planCachedFirst() {
        val plain = FormatLadder.plan(mic(1), false, null)
        val p = FormatLadder.plan(mic(1), false, "MIC|44100|1")
        assertEquals(Source.MIC to r(44100, 1), p[0])
        assertEquals(plain.size, p.size)
        assertEquals(p.size, p.toSet().size)
    }

    @Test fun planIgnoresBadCache() {
        val plain = FormatLadder.plan(mic(1), false, null)
        assertEquals(plain, FormatLadder.plan(mic(1), false, "junk"))
        assertEquals(plain, FormatLadder.plan(mic(1), false, "MIC|22050|1"))
        // a cached UNPROCESSED rung is dropped when the source isn't supported any more
        assertEquals(plain, FormatLadder.plan(mic(1), false, "UNPROCESSED|48000|1"))
    }

    @Test fun encodeDecodeRoundTrip() {
        val s = FormatLadder.encode(Source.VOICE_RECOGNITION, r(48000, 2))
        assertEquals("VOICE_RECOGNITION|48000|2", s)
        assertEquals(Source.VOICE_RECOGNITION to r(48000, 2), FormatLadder.decode(s))
        assertNull(FormatLadder.decode("MIC|48000"))
        assertNull(FormatLadder.decode("BOGUS|48000|1"))
        assertNull(FormatLadder.decode("MIC|x|1"))
        assertNull(FormatLadder.decode("MIC|48000|3"))
        assertNotNull(FormatLadder.decode("MIC|0|1"))
    }

    @Test fun bufferBytesExamples() {
        assertEquals(48000, FormatLadder.bufferBytes(3840, 48000, 1))
        assertEquals(120000, FormatLadder.bufferBytes(30000, 48000, 2))
        assertEquals(96000, FormatLadder.bufferBytes(0, 0, 2))
        for ((m, rate, ch) in listOf(Triple(3840, 48000, 1), Triple(30000, 48000, 2), Triple(0, 0, 2), Triple(1001, 44100, 2)))
            assertEquals(0, FormatLadder.bufferBytes(m, rate, ch) % (2 * ch))
    }

    @Test fun chunkFrames() {
        assertEquals(960, FormatLadder.chunkFrames(48000))
        assertEquals(882, FormatLadder.chunkFrames(44100))
    }
}

class TakeBudgetTest {
    private val mib = 1L shl 20

    @Test fun hugeDiskIsCapped() { assertEquals(1800, TakeBudget.maxSeconds(10_000_000_000L, 192000)) }
    @Test fun seventyMibMono() { assertEquals(65, TakeBudget.maxSeconds(70 * mib, 96000)) }
    @Test fun justUnderTenSeconds() {
        assertEquals(9, TakeBudget.maxSeconds(64 * mib + 959_999, 96000))
        assertFalse(TakeBudget.allows(9))
    }
    @Test fun belowReserveIsZero() {
        assertEquals(0, TakeBudget.maxSeconds(10 * mib, 96000))
        assertEquals(0, TakeBudget.maxSeconds(64 * mib, 96000))
    }
    @Test fun allowsTen() { assertTrue(TakeBudget.allows(10)) }
}

class ChopBudgetTest {
    private val mib = 1L shl 20

    @Test fun needFiveMinutesAt48k() { assertEquals(189_577_216L, ChopBudget.need(300L * 48000, 48000)) }
    @Test fun needOneMinuteAt44k() { assertEquals(51_337_216L, ChopBudget.need(60L * 44100, 44100)) }
    @Test fun fits276SecondsIn192MibHeap() { assertTrue(ChopBudget.fits(276L * 48000, 48000, 192 * mib, 24 * mib)) }
    @Test fun doesNotFit277Seconds() { assertFalse(ChopBudget.fits(277L * 48000, 48000, 192 * mib, 24 * mib)) }
    @Test fun frames48() {
        assertEquals(48000L, ChopBudget.frames48(44100, 44100))
        assertEquals(48000L, ChopBudget.frames48(48000, 48000))
        assertEquals(2L, ChopBudget.frames48(1, 44100))     // ceil
    }
}

class ClockTest {
    @Test fun of() {
        assertEquals("0:00.0", Clock.of(0.0))
        assertEquals("0:42.3", Clock.of(42.34))
        assertEquals("12:05.0", Clock.of(725.0))
        assertEquals("0:59.9", Clock.of(59.96))
        assertEquals("0:03.0", Clock.of(3.0))
    }
    @Test fun khz() {
        assertEquals("48 kHz", Clock.khz(48000))
        assertEquals("44.1 kHz", Clock.khz(44100))
    }
    @Test fun spoken() {
        assertEquals("Recorded 1 minutes 12 seconds", Clock.spoken(72.9))
        assertEquals("Recorded 0 minutes 12 seconds", Clock.spoken(12.99))
    }
}

class TakeTextTest {
    private fun take(inputName: String = "USB · Scarlett 2i2 USB") = Take(
        "R1106-174233", File("R1106-174233.wav"), 0L, 48000, 2, 48000L * 3, 0, inputName, "MIC", listOf(-12f), 0, 0,
        StopReason.USER, null, emptyList())

    @Test fun everyStopReasonVerbatim() {
        val t = take()
        val n = "USB · Scarlett 2i2 USB"
        val c = "0:03.0"
        assertEquals("Recorded $c.", TakeText.stopLine(StopReason.USER, t))
        assertEquals("Stopped: $n was unplugged. The $c before that is kept.", TakeText.stopLine(StopReason.UNPLUGGED, t))
        assertEquals("Stopped: Android moved the input away from $n. The $c before that is kept.", TakeText.stopLine(StopReason.ROUTE_CHANGED, t))
        assertEquals("Stopped: a call or another app took the microphone. The $c before that is kept.", TakeText.stopLine(StopReason.SILENCED, t))
        assertEquals("Stopped: $n stopped sending audio. The $c before that is kept.", TakeText.stopLine(StopReason.READ_ERROR, t))
        assertEquals("Stopped when Rytm Mobile left the screen. The $c before that is kept.", TakeText.stopLine(StopReason.BACKGROUND, t))
        assertEquals("Stopped at the take limit ($c).", TakeText.stopLine(StopReason.MAX_LENGTH, t))
        assertEquals("Stopped: phone storage is full. The $c before that is kept.", TakeText.stopLine(StopReason.DISK_FULL, t))
        assertEquals("Recovered after the app closed during a take ($c).", TakeText.stopLine(StopReason.RECOVERED, t))
        // there is a row for every reason
        assertEquals(StopReason.values().size, 9)
    }

    @Test fun recoveredCounts() {
        assertEquals("Recovered 1 unfinished take.", TakeText.recovered(1))
        assertEquals("Recovered 3 unfinished takes.", TakeText.recovered(3))
    }
}

class FakeSpecTest {
    @Test fun parseAllTokens() {
        val s = FakeSpec.parse("sine1000,level-6,clip@2,unplug@5,dead@3,silence@1,stereo,deadright")
        assertEquals(FakeSpec(hz = 1000f, levelDb = -6f, clipAt = 2f, unplugAt = 5f, deadAt = 3f, silenceAt = 1f, stereo = true, deadRight = true), s)
    }

    @Test fun parseEmptyIsDefaults() {
        val s = FakeSpec.parse("")
        assertEquals(440f, s.hz); assertEquals(-12f, s.levelDb); assertFalse(s.stereo)
        assertEquals(FakeSpec(), s)
    }

    @Test fun unknownTokensIgnored() {
        assertEquals(FakeSpec(stereo = true), FakeSpec.parse("bogus, stereo ,sine,level,clip@x,wat@1"))
    }

    @Test fun deadRightIsNotDead() {
        val s = FakeSpec.parse("deadright")
        assertTrue(s.deadRight); assertNull(s.deadAt)
    }
}
