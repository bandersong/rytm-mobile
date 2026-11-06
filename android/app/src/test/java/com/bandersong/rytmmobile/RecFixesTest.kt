package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the review of the recording branch. The fixtures here keep the picked input, the routed
 * device id and the routed name all different, so a field mix-up in the reducer cannot hide.
 */
class RecFixesTest {
    private val none = emptyList<RecEffect>()
    private val USB5 = "USB · Scarlett 2i2 USB"

    private fun usb(id: Int) = InputInfo(id, InputKind.USB_DEVICE, "Scarlett 2i2 USB Audio", emptyList(), emptyList())
    private fun mic(id: Int) = InputInfo(id, InputKind.BUILTIN_MIC, "", emptyList(), emptyList())
    private fun f(routedId: Int?, name: String = USB5, matches: Boolean = true) =
        OpenFormat(Source.VOICE_RECOGNITION, 48000, 2, routedId, name, matches)
    private fun take(reason: StopReason = StopReason.USER) = Take(
        "R1106-174233", File("x.wav"), 0L, 48000, 2, 48000L * 3, 0, USB5, "VOICE_RECOGNITION", listOf(-12f, -12f), 0, 0, reason, null, emptyList())
    private fun armed(i: InputInfo, inputs: List<InputInfo> = listOf(i), open: OpenFormat = f(i.id)) =
        RecModel(state = RecState.Armed(i, open), open = true, inputs = inputs)
    private fun recording(i: InputInfo, inputs: List<InputInfo> = listOf(i), open: OpenFormat = f(i.id)) =
        RecModel(state = RecState.Recording(i, open, "R1106-174233", 600), open = true, inputs = inputs)
    private fun reduce(m: RecModel, e: RecEvent) = RecMachine.reduce(m, e)
    private fun run(m0: RecModel, vararg es: RecEvent): Pair<RecModel, List<RecEffect>> {
        var m = m0; var fx: List<RecEffect> = none
        for (e in es) { val r = reduce(m, e); m = r.first; fx = r.second }
        return m to fx
    }

    // --- P1: the input died or went away while the take was being saved ----------------------------------------

    @Test fun readErrorWhileSavingReopensInsteadOfRearming() {
        val (n, fx) = run(recording(usb(5)), RecEvent.Stop, RecEvent.Interrupt(StopReason.READ_ERROR), RecEvent.Finalized(take()))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertTrue(fx.contains(RecEffect.OpenInput(usb(5))))
        assertFalse(n.inputLost)
    }

    @Test fun unplugWhileSavingOpensTheBestRemainingInput() {
        val m = recording(usb(5), listOf(usb(5), mic(1)))
        val (n, fx) = run(m, RecEvent.Stop, RecEvent.Devices(listOf(mic(1))), RecEvent.Finalized(take()))
        assertEquals(RecState.Opening(mic(1)), n.state)
        assertEquals(RecEffect.OpenInput(mic(1)), fx.last())
        val (none1, fx1) = run(recording(usb(5)), RecEvent.Stop, RecEvent.Devices(emptyList()), RecEvent.Finalized(take()))
        assertEquals(RecState.NoInput, none1.state)
        assertEquals(RecEffect.CloseInput, fx1.last())
    }

    @Test fun routeMovingWhileSavingReopens() {
        val (n, fx) = run(recording(usb(5), open = f(5)), RecEvent.Stop, RecEvent.RouteChanged(3, "Phone mic"), RecEvent.Finalized(take()))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertEquals(RecEffect.OpenInput(usb(5)), fx.last())
        // the same route repeated is not a loss
        val (ok, fxOk) = run(recording(usb(5), open = f(5)), RecEvent.Stop, RecEvent.RouteChanged(5, USB5), RecEvent.Finalized(take()))
        assertEquals(RecState.Armed(usb(5), f(5)), ok.state)
        assertFalse(fxOk.any { it is RecEffect.OpenInput })
    }

    @Test fun anUneventfulStopStillKeepsMonitoring() {
        val (n, fx) = run(recording(usb(5)), RecEvent.Stop, RecEvent.Finalized(take()))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertFalse(fx.any { it is RecEffect.OpenInput || it == RecEffect.CloseInput })
    }

    @Test fun readErrorThenInputGoneFallsBackToBestOrNone() {
        val m = recording(usb(5), listOf(usb(5), mic(1)))
        val (n, fx) = run(m, RecEvent.Interrupt(StopReason.READ_ERROR), RecEvent.Devices(listOf(mic(1))), RecEvent.Finalized(take(StopReason.READ_ERROR)))
        assertEquals(RecState.Opening(mic(1)), n.state)
        assertEquals(RecEffect.OpenInput(mic(1)), fx.last())
        val (e, fxE) = run(recording(usb(5)), RecEvent.Interrupt(StopReason.READ_ERROR), RecEvent.Devices(emptyList()), RecEvent.Finalized(take(StopReason.READ_ERROR)))
        assertEquals(RecState.NoInput, e.state)
        assertEquals(RecEffect.CloseInput, fxE.last())
    }

    // --- P2: leaving the screen and coming back before the take is saved -----------------------------------------

    @Test fun backBeforeTheTakeIsSavedReopensTheInput() {
        val (n, fx) = run(recording(usb(5)), RecEvent.HostStopped, RecEvent.HostStarted(true, false, listOf(usb(5))),
            RecEvent.Finalized(take(StopReason.BACKGROUND)))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertTrue(n.hostVisible)
        assertEquals(RecEffect.OpenInput(usb(5)), fx.last())
        assertEquals(null, n.resume)
    }

    @Test fun backBeforeSavedWithoutPermissionShowsThePermissionCard() {
        val (n, _) = run(recording(usb(5)), RecEvent.HostStopped, RecEvent.HostStarted(false, true, emptyList()),
            RecEvent.Finalized(take(StopReason.BACKGROUND)))
        assertEquals(RecState.NeedsPermission(Perm.RATIONALE), n.state)
    }

    @Test fun leavingAgainBeforeSavedStaysPaused() {
        val (n, fx) = run(recording(usb(5)), RecEvent.HostStopped, RecEvent.HostStarted(true, false, listOf(usb(5))),
            RecEvent.HostStopped, RecEvent.Finalized(take(StopReason.BACKGROUND)))
        assertEquals(RecState.Paused, n.state)
        assertFalse(fx.any { it is RecEffect.OpenInput })
    }

    @Test fun pausedResumeUsesTheFreshInputList() {
        val paused = armed(usb(5)).copy(state = RecState.Paused, hostVisible = false)
        val (n, fx) = reduce(paused, RecEvent.HostStarted(true, false, listOf(mic(1))))
        assertEquals(RecState.Opening(mic(1)), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(mic(1))), fx)
    }

    // --- debug fake: a new rec_fake spec must really reopen ---------------------------------------------------------

    @Test fun devicesAloneDoNotReopenTheSameInput() {
        val a = armed(usb(5))
        val (n, fx) = reduce(a, RecEvent.Devices(listOf(usb(5))))
        assertEquals(a.state, n.state); assertEquals(none, fx)
    }

    @Test fun reopenRebuildsTheCurrentInput() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.Reopen)
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
        val (o, fxo) = reduce(RecModel(state = RecState.Opening(usb(5)), open = true, inputs = listOf(usb(5))), RecEvent.Reopen)
        assertEquals(RecState.Opening(usb(5)), o.state); assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fxo)
        val (fl, fxf) = reduce(RecModel(state = RecState.Failed(usb(5), "x"), open = true, inputs = listOf(usb(5))), RecEvent.Reopen)
        assertEquals(RecState.Opening(usb(5)), fl.state); assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fxf)
    }

    @Test fun reopenNeverTouchesATakeOrAnIdleRecorder() {
        val r = recording(usb(5))
        assertEquals(r to none, reduce(r, RecEvent.Reopen))
        assertEquals(RecModel() to none, reduce(RecModel(), RecEvent.Reopen))
    }

    // --- phone-mic route ids -----------------------------------------------------------------------------------------

    @Test fun routeIdNormalisation() {
        assertEquals(3, RouteId.normalize(5, InputKind.BUILTIN_MIC, mic(3)))          // another built-in entry is still the phone mic
        assertEquals(7, RouteId.normalize(7, InputKind.USB_DEVICE, mic(3)))           // a real move away stays visible
        assertEquals(5, RouteId.normalize(5, InputKind.BUILTIN_MIC, usb(5)))          // only the collapsed phone mic is normalised
        assertEquals(null, RouteId.normalize(null, null, mic(3)))
    }

    @Test fun anOpenedPhoneMicSurvivesARepeatedRouteCallback() {
        // Opened carries the normalised id, so a callback for the same mic (normalised the same way) is not a move
        val opening = RecModel(state = RecState.Opening(mic(3)), open = true, inputs = listOf(mic(3)))
        val opened = reduce(opening, RecEvent.Opened(3, f(RouteId.normalize(5, InputKind.BUILTIN_MIC, mic(3)), "Phone mic")))
        val (rec, _) = reduce(opened.first, RecEvent.Rec(600, "R1"))
        val (n, fx) = reduce(rec, RecEvent.RouteChanged(RouteId.normalize(5, InputKind.BUILTIN_MIC, mic(3)), "Phone mic"))
        assertTrue(n.state is RecState.Recording); assertEquals(none, fx)
    }

    // --- fixtures that keep input id, routed id and routed name apart ------------------------------------------------

    @Test fun recAnnouncesTheRoutedNameNotThePickedInput() {
        val a = armed(usb(5), open = f(1, "Phone mic", matches = false))
        val (_, fx) = reduce(a, RecEvent.Rec(600, "R1"))
        assertTrue(fx.contains(RecEffect.Say("Recording from Phone mic.")))
        assertNotEquals("USB · Scarlett 2i2 USB", "Phone mic")
    }

    @Test fun routeChangeIsComparedWithTheRoutedIdNotTheInputId() {
        val r = recording(usb(5), open = f(1, "Phone mic", matches = false))
        val (same, fx) = reduce(r, RecEvent.RouteChanged(1, "Phone mic"))
        assertTrue(same.state is RecState.Recording); assertEquals(none, fx)
        val (moved, fx2) = reduce(r, RecEvent.RouteChanged(5, USB5))
        assertTrue(moved.state is RecState.Finalizing)
        assertEquals(listOf<RecEffect>(RecEffect.EndTake(StopReason.ROUTE_CHANGED)), fx2)
    }

    // --- spec rows with no test before ---------------------------------------------------------------------------------

    @Test fun pickWorksFromFailedAndNoInput() {
        val inputs = listOf(usb(5), mic(1))
        val failed = RecModel(state = RecState.Failed(usb(5), "Couldn't open"), open = true, inputs = inputs)
        val (n, fx) = reduce(failed, RecEvent.Pick(mic(1).key))
        assertEquals(RecState.Opening(mic(1)), n.state); assertTrue(n.userPicked)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(mic(1))), fx)
        val noInput = RecModel(state = RecState.NoInput, open = true, inputs = inputs)
        val (p, fxp) = reduce(noInput, RecEvent.Pick(usb(5).key))
        assertEquals(RecState.Opening(usb(5)), p.state)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fxp)
    }

    // --- text -----------------------------------------------------------------------------------------------------------

    @Test fun openFailureNamesOnlyWhatWasTriedAndTheUacLimit() {
        val u = OpenText.failed(usb(5), 12)
        assertTrue(u, u.startsWith("Couldn't open USB · Scarlett 2i2 USB. Tried 12 ways, at 48 and 44.1 kHz, stereo and mono."))
        assertTrue(u, "USB Audio Class 2-only interfaces may not work" in u)
        assertTrue(u, "unplug it and plug it back in" in u)
        val p = OpenText.failed(mic(1), 3)
        assertTrue(p, "mono" in p && "stereo" !in p && "unplug" !in p && "Audio Class" !in p)
        assertTrue(OpenText.failed(mic(1), 1), "Tried 1 way," in OpenText.failed(mic(1), 1))
    }

    @Test fun transportSpeaksTheVisibleState() {
        val i = usb(5); val o = f(5)
        assertEquals("Recorded 1 minutes 1 seconds", TransportText.spoken(RecState.Recording(i, o, "R", 600), 61.5))
        for (st in listOf(RecState.Armed(i, o), RecState.Opening(i), RecState.Finalizing(i, o, "R", StopReason.USER),
            RecState.Failed(i, "x"), RecState.Paused, RecState.NoInput)) {
            val spoken = TransportText.spoken(st, 0.0)
            assertFalse("$st says $spoken", spoken.startsWith("Recorded"))
        }
        assertEquals("Ready to record", TransportText.spoken(RecState.Armed(i, o), 0.0))
        assertEquals("Saving the take", TransportText.spoken(RecState.Finalizing(i, o, "R", StopReason.USER), 0.0))
        assertEquals("SAVING…", TransportText.timer(RecState.Finalizing(i, o, "R", StopReason.USER), 0.0))
        assertEquals("0:01.5", TransportText.timer(RecState.Recording(i, o, "R", 600), 1.5))
    }

    @Test fun recButtonStateFollowsTheRealState() {
        val i = usb(5); val o = f(5)
        assertEquals("Stop recording" to "Recording", TransportText.recButton(RecState.Recording(i, o, "R", 600)))
        assertEquals("Start recording" to "Ready", TransportText.recButton(RecState.Armed(i, o)))
        assertEquals("Start recording" to "Opening the input", TransportText.recButton(RecState.Opening(i)))
        assertEquals("Start recording" to "Saving the take", TransportText.recButton(RecState.Finalizing(i, o, "R", StopReason.USER)))
        assertEquals("Start recording" to "Input unavailable", TransportText.recButton(RecState.Failed(i, "x")))
        assertEquals("Start recording" to "Paused", TransportText.recButton(RecState.Paused))
    }
}
