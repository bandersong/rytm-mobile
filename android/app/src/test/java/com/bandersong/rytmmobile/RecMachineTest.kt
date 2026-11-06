package com.bandersong.rytmmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every row of the transition table (spec section 3.2) and every exact string (section 3.3). */
class RecMachineTest {
    private val none = emptyList<RecEffect>()
    private val USB5 = "USB · Scarlett 2i2 USB"

    private fun usb(id: Int, name: String = "Scarlett 2i2 USB Audio", rates: List<Int> = emptyList(), ch: List<Int> = emptyList()) =
        InputInfo(id, InputKind.USB_DEVICE, name, rates, ch)
    private fun mic(id: Int) = InputInfo(id, InputKind.BUILTIN_MIC, "", emptyList(), emptyList())
    private fun f(id: Int) = OpenFormat(Source.VOICE_RECOGNITION, 48000, 2, id, USB5, true)
    private fun take(id: String = "R1106-174233", reason: StopReason = StopReason.USER) = Take(
        id, File("$id.wav"), 0L, 48000, 2, 48000L * 3, 0, USB5, "VOICE_RECOGNITION", listOf(-12f, -12f), 0, 0, reason, null, emptyList())

    private fun m(state: RecState, inputs: List<InputInfo> = emptyList(), open: Boolean = true) =
        RecModel(state = state, open = open, inputs = inputs)
    private fun armed(i: InputInfo, inputs: List<InputInfo> = listOf(i)) = m(RecState.Armed(i, f(i.id)), inputs)
    private fun recording(i: InputInfo, inputs: List<InputInfo> = listOf(i), id: String = "R1106-174233") =
        m(RecState.Recording(i, f(i.id), id, 600), inputs)
    private fun reduce(model: RecModel, e: RecEvent) = RecMachine.reduce(model, e)

    private fun Pair<RecModel, List<RecEffect>>.state() = first.state

    // 1
    @Test fun showGrantedOpensBestInput() {
        val (n, fx) = reduce(RecModel(), RecEvent.Show(true, false, listOf(mic(1), usb(5)), null))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertTrue(n.open)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
    }

    // 2
    @Test fun showGrantedUsesSavedKey() {
        val (n, fx) = reduce(RecModel(), RecEvent.Show(true, false, listOf(mic(1), usb(5)), "BUILTIN_MIC|"))
        assertEquals(RecState.Opening(mic(1)), n.state)
        assertEquals("BUILTIN_MIC|", n.selectedKey)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(mic(1))), fx)
    }

    // 3
    @Test fun showGrantedNoInputs() {
        val (n, fx) = reduce(RecModel(), RecEvent.Show(true, false, emptyList(), null))
        assertEquals(RecState.NoInput, n.state)
        assertTrue(n.open)
        assertEquals(listOf<RecEffect>(RecEffect.CloseInput), fx)
    }

    // 4
    @Test fun showDeniedWithRationale() {
        val (n, fx) = reduce(RecModel(), RecEvent.Show(false, true, listOf(mic(1)), null))
        assertEquals(RecState.NeedsPermission(Perm.RATIONALE), n.state)
        assertTrue(n.open)
        assertEquals(none, fx)
    }

    // 5
    @Test fun showDeniedNoRationale() {
        val (n, fx) = reduce(RecModel(), RecEvent.Show(false, false, listOf(mic(1)), null))
        assertEquals(RecState.NeedsPermission(Perm.ASKING), n.state)
        assertEquals(none, fx)
    }

    @Test fun showResetsUserPickedAndMarksNothingNew() {
        val start = RecModel(userPicked = true)
        val (n, _) = reduce(start, RecEvent.Show(true, false, listOf(mic(1), usb(5)), null))
        assertFalse(n.userPicked)
        assertTrue(n.inputs.none { it.isNew })
    }

    // 6
    @Test fun showIgnoredWhenOpen() {
        val a = armed(mic(1))
        val (n, fx) = reduce(a, RecEvent.Show(true, false, listOf(usb(5)), null))
        assertEquals(a, n)
        assertEquals(none, fx)
    }

    // 7
    @Test fun permissionGrantedOpens() {
        val start = m(RecState.NeedsPermission(Perm.ASKING))
        val (n, fx) = reduce(start, RecEvent.PermissionResult(true, false, listOf(usb(5))))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertEquals(listOf(usb(5)), n.inputs)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
    }

    // 8
    @Test fun permissionDeniedOnceIsRationale() {
        val (n, fx) = reduce(m(RecState.NeedsPermission(Perm.ASKING)), RecEvent.PermissionResult(false, true, emptyList()))
        assertEquals(RecState.NeedsPermission(Perm.RATIONALE), n.state)
        assertEquals(none, fx)
    }

    // 9
    @Test fun permissionDeniedTwiceIsBlocked() {
        val (n, _) = reduce(m(RecState.NeedsPermission(Perm.RATIONALE)), RecEvent.PermissionResult(false, false, emptyList()))
        assertEquals(RecState.NeedsPermission(Perm.BLOCKED), n.state)
    }

    // 10
    @Test fun askAgainFromBlocked() {
        val (n, fx) = reduce(m(RecState.NeedsPermission(Perm.BLOCKED)), RecEvent.AskAgain)
        assertEquals(RecState.NeedsPermission(Perm.ASKING), n.state)
        assertEquals(none, fx)
        // and from RATIONALE
        assertEquals(RecState.NeedsPermission(Perm.ASKING), reduce(m(RecState.NeedsPermission(Perm.RATIONALE)), RecEvent.AskAgain).state())
    }

    // 11
    @Test fun openedArms() {
        val start = m(RecState.Opening(usb(5)), listOf(usb(5)))
        val (n, fx) = reduce(start, RecEvent.Opened(5, f(5)))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertNull(n.note)
        assertEquals(none, fx)
    }

    // 12
    @Test fun openedMismatchSetsNote() {
        val start = m(RecState.Opening(usb(5)), listOf(usb(5)))
        val fm = f(5).copy(routedMatches = false, routedName = "Phone mic")
        val (n, _) = reduce(start, RecEvent.Opened(5, fm))
        assertEquals("Android is recording from Phone mic, not USB · Scarlett 2i2 USB.", n.note)
    }

    // 13
    @Test fun staleOpenedIgnored() {
        val start = m(RecState.Opening(usb(5)), listOf(usb(5)))
        val (n, fx) = reduce(start, RecEvent.Opened(7, f(7)))
        assertEquals(start, n)
        assertEquals(none, fx)
        assertEquals(start, reduce(start, RecEvent.OpenFailed(7, "x")).first)
    }

    // 14
    @Test fun openFailedFails() {
        val start = m(RecState.Opening(usb(5)), listOf(usb(5)))
        val (n, fx) = reduce(start, RecEvent.OpenFailed(5, "boom"))
        assertEquals(RecState.Failed(usb(5), "boom"), n.state)
        assertEquals(none, fx)
    }

    // 15
    @Test fun retryReopensOrPicksBest() {
        val (n, fx) = reduce(m(RecState.Failed(usb(5), "x"), listOf(usb(5), mic(1))), RecEvent.Retry)
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
        val (n2, fx2) = reduce(m(RecState.Failed(null, "x"), listOf(mic(1))), RecEvent.Retry)
        assertEquals(RecState.Opening(mic(1)), n2.state)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(mic(1))), fx2)
        // the failed input is gone: take the best of what is left
        val (n3, _) = reduce(m(RecState.Failed(usb(5), "x"), listOf(mic(1))), RecEvent.Retry)
        assertEquals(RecState.Opening(mic(1)), n3.state)
    }

    // 16
    @Test fun recStartsTake() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.Rec(600, "R1106-174233"))
        assertEquals(RecState.Recording(usb(5), f(5), "R1106-174233", 600), n.state)
        assertEquals(listOf(RecEffect.StartTake("R1106-174233", 600), RecEffect.Say("Recording from $USB5.")), fx)
    }

    // 17
    @Test fun recCapsAt1800() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.Rec(5000, "R1106-174233"))
        assertEquals(1800, (n.state as RecState.Recording).maxSeconds)
        assertEquals(RecEffect.StartTake("R1106-174233", 1800), fx[0])
    }

    // 18
    @Test fun recRefusedUnder10s() {
        val a = armed(usb(5))
        val (n, fx) = reduce(a, RecEvent.Rec(9, "R1106-174233"))
        assertEquals(a, n)
        assertEquals(listOf<RecEffect>(RecEffect.Say("Phone storage is full. Free some space to record.")), fx)
        // exactly 10 s is allowed
        assertTrue(reduce(a, RecEvent.Rec(10, "R1")).first.state is RecState.Recording)
    }

    // 19
    @Test fun recIgnoredWhenOpening() {
        val o = m(RecState.Opening(usb(5)), listOf(usb(5)))
        val (n, fx) = reduce(o, RecEvent.Rec(600, "R1"))
        assertEquals(o, n)
        assertEquals(none, fx)
    }

    // 20
    @Test fun stopFinalizes() {
        val (n, fx) = reduce(recording(usb(5)), RecEvent.Stop)
        assertEquals(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.USER), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.EndTake(StopReason.USER)), fx)
    }

    // 21
    @Test fun finalizedUserKeepsMonitoring() {
        val fin = m(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.USER), listOf(usb(5)))
        val t = take()
        val (n, fx) = reduce(fin, RecEvent.Finalized(t))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertEquals(t, n.lastTake)
        assertEquals(listOf(RecEffect.ReloadTakes, RecEffect.Say("Recorded 0:03.0.")), fx)
    }

    // 22
    @Test fun unplugMidTakeThenNextInput() {
        val (mid, fx) = reduce(recording(usb(5), listOf(usb(5), mic(1))), RecEvent.Devices(listOf(mic(1))))
        assertEquals(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.UNPLUGGED), mid.state)
        assertEquals(listOf<RecEffect>(RecEffect.EndTake(StopReason.UNPLUGGED)), fx)
        val (n, fx2) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.UNPLUGGED)))
        assertEquals(RecState.Opening(mic(1)), n.state)
        assertEquals(listOf(
            RecEffect.ReloadTakes,
            RecEffect.Say("Stopped: USB · Scarlett 2i2 USB was unplugged. The 0:03.0 before that is kept."),
            RecEffect.OpenInput(mic(1))), fx2)
    }

    // 23
    @Test fun unplugMidTakeNoInputLeft() {
        val (mid, _) = reduce(recording(usb(5)), RecEvent.Devices(emptyList()))
        assertTrue(mid.state is RecState.Finalizing)
        val (n, fx) = reduce(mid, RecEvent.Finalized(take()))
        assertEquals(RecState.NoInput, n.state)
        assertEquals(RecEffect.CloseInput, fx.last())
    }

    // 24
    @Test fun addMidTakeNeverSwitches() {
        val r = recording(mic(1))
        val (n, fx) = reduce(r, RecEvent.Devices(listOf(mic(1), usb(9))))
        assertEquals(r.state, n.state)
        assertEquals(2, n.inputs.size)
        assertTrue(n.inputs.first { it.id == 9 }.isNew)
        assertEquals(none, fx)
    }

    // 25
    @Test fun routeChangeMidTake() {
        val r = recording(usb(5))
        val (n, fx) = reduce(r, RecEvent.RouteChanged(1, "Phone mic"))
        assertEquals(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.ROUTE_CHANGED), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.EndTake(StopReason.ROUTE_CHANGED)), fx)
        assertEquals(r, reduce(r, RecEvent.RouteChanged(5, USB5)).first)
        assertEquals(r, reduce(r, RecEvent.RouteChanged(null, "")).first)
    }

    // 26
    @Test fun routeChangeWhileArmedSetsNote() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.RouteChanged(1, "Phone mic"))
        val s = n.state as RecState.Armed
        assertEquals(1, s.open.routedId); assertEquals("Phone mic", s.open.routedName); assertFalse(s.open.routedMatches)
        assertEquals("Android is recording from Phone mic, not USB · Scarlett 2i2 USB.", n.note)
        assertEquals(none, fx)
        val (back, _) = reduce(n, RecEvent.RouteChanged(5, USB5))
        assertNull(back.note)
        assertTrue((back.state as RecState.Armed).open.routedMatches)
        // null (no routed device reported) counts as a match
        assertNull(reduce(n, RecEvent.RouteChanged(null, "")).first.note)
    }

    // 27
    @Test fun silencedThenInterrupt() {
        val r = recording(usb(5))
        val (s, fx) = reduce(r, RecEvent.Silenced(true))
        assertTrue(s.silenced); assertEquals(none, fx)
        val (mid, fx2) = reduce(s, RecEvent.Interrupt(StopReason.SILENCED))
        assertEquals(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.SILENCED), mid.state)
        assertEquals(listOf<RecEffect>(RecEffect.EndTake(StopReason.SILENCED)), fx2)
        val (n, _) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.SILENCED)))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertFalse(reduce(s, RecEvent.Silenced(false)).first.silenced)
    }

    // 28
    @Test fun readErrorMidTakeReopensSame() {
        val (mid, _) = reduce(recording(usb(5)), RecEvent.Interrupt(StopReason.READ_ERROR))
        assertEquals(StopReason.READ_ERROR, (mid.state as RecState.Finalizing).reason)
        val (n, fx) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.READ_ERROR)))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertEquals(RecEffect.OpenInput(usb(5)), fx.last())
        assertEquals("Stopped: $USB5 stopped sending audio. The 0:03.0 before that is kept.", (fx[1] as RecEffect.Say).text)
    }

    // 29
    @Test fun readErrorWhileArmedFails() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.Interrupt(StopReason.READ_ERROR))
        assertEquals(RecState.Failed(usb(5), "Lost $USB5. Tap Try again or pick another input."), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.CloseInput), fx)
    }

    // 30
    @Test fun maxLengthKeepsMonitoring() {
        val (mid, _) = reduce(recording(usb(5)), RecEvent.Interrupt(StopReason.MAX_LENGTH))
        val (n, fx) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.MAX_LENGTH)))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertEquals("Stopped at the take limit (0:03.0): 30 minutes, or less when phone storage is low.", (fx[1] as RecEffect.Say).text)
    }

    @Test fun diskFullKeepsMonitoring() {
        val (mid, _) = reduce(recording(usb(5)), RecEvent.Interrupt(StopReason.DISK_FULL))
        val (n, fx) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.DISK_FULL)))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertEquals("Stopped: phone storage is full. The 0:03.0 before that is kept.", (fx[1] as RecEffect.Say).text)
    }

    // 31
    @Test fun newUsbWhileArmedAutoSwitches() {
        val (n, fx) = reduce(armed(mic(1)), RecEvent.Devices(listOf(mic(1), usb(9))))
        assertEquals(9, (n.state as RecState.Opening).input.id)
        assertEquals(listOf(RecEffect.OpenInput(usb(9).copy(isNew = true)), RecEffect.Say("Using $USB5.")), fx)
    }

    // 32
    @Test fun newUsbWhileArmedStaysAfterPick() {
        val a = armed(mic(1)).copy(userPicked = true)
        val (n, fx) = reduce(a, RecEvent.Devices(listOf(mic(1), usb(9))))
        assertTrue(n.state is RecState.Armed)
        assertEquals(none, fx)
        assertEquals(2, n.inputs.size)
    }

    // 33
    @Test fun removedWhileArmedOpensNext() {
        val (n, fx) = reduce(armed(usb(5), listOf(usb(5), mic(1))), RecEvent.Devices(listOf(mic(1))))
        assertEquals(RecState.Opening(mic(1)), n.state)
        assertEquals(listOf(RecEffect.OpenInput(mic(1)), RecEffect.Say("$USB5 unplugged. Using Phone mic.")), fx)
    }

    // 34
    @Test fun removedWhileArmedNoneLeft() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.Devices(emptyList()))
        assertEquals(RecState.NoInput, n.state)
        assertEquals(listOf(RecEffect.CloseInput, RecEffect.Say("$USB5 unplugged.")), fx)
    }

    // 35
    @Test fun noInputThenPlugged() {
        val (n, fx) = reduce(m(RecState.NoInput), RecEvent.Devices(listOf(usb(5))))
        assertEquals(5, (n.state as RecState.Opening).input.id)
        assertEquals(5, (fx.single() as RecEffect.OpenInput).input.id)
        // still nothing: unchanged state, list kept
        val (same, fx2) = reduce(m(RecState.NoInput), RecEvent.Devices(emptyList()))
        assertEquals(RecState.NoInput, same.state); assertEquals(none, fx2)
    }

    // 36
    @Test fun pickOpensAndMarksUserPicked() {
        val a = armed(mic(1), listOf(mic(1), usb(5)))
        val (n, fx) = reduce(a, RecEvent.Pick("USB_DEVICE|Scarlett 2i2 USB Audio"))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertTrue(n.userPicked)
        assertEquals("USB_DEVICE|Scarlett 2i2 USB Audio", n.selectedKey)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
        // an unknown key changes nothing
        assertEquals(a, reduce(a, RecEvent.Pick("LINE_IN|x")).first)
    }

    // 37
    @Test fun pickIgnoredWhileRecording() {
        val r = recording(mic(1)).copy(inputs = listOf(mic(1), usb(5)))
        assertEquals(r, reduce(r, RecEvent.Pick("USB_DEVICE|Scarlett 2i2 USB Audio")).first)
        val fin = m(RecState.Finalizing(mic(1), f(1), "R1", StopReason.USER), listOf(mic(1), usb(5)))
        assertEquals(fin, reduce(fin, RecEvent.Pick("USB_DEVICE|Scarlett 2i2 USB Audio")).first)
    }

    // 38
    @Test fun closeFromArmed() {
        val start = armed(usb(5)).copy(note = "n", silenced = true)
        val (n, fx) = reduce(start, RecEvent.Close)
        assertEquals(RecState.Idle, n.state)
        assertFalse(n.open); assertNull(n.note); assertFalse(n.silenced)
        assertEquals(listOf<RecEffect>(RecEffect.CloseInput), fx)
    }

    // 39
    @Test fun closeIgnoredWhileRecording() {
        val r = recording(usb(5))
        assertEquals(r, reduce(r, RecEvent.Close).first)
        assertEquals(none, reduce(r, RecEvent.Close).second)
        val fin = m(RecState.Finalizing(usb(5), f(5), "R1", StopReason.USER))
        assertEquals(fin, reduce(fin, RecEvent.Close).first)
    }

    // 40
    @Test fun hostStoppedMidTake() {
        val (mid, fx) = reduce(recording(usb(5)), RecEvent.HostStopped)
        assertEquals(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.BACKGROUND), mid.state)
        assertFalse(mid.hostVisible)
        assertEquals(listOf(RecEffect.EndTake(StopReason.BACKGROUND), RecEffect.CloseInput), fx)
        val (n, fx2) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.BACKGROUND)))
        assertEquals(RecState.Paused, n.state)
        assertEquals(listOf(RecEffect.ReloadTakes,
            RecEffect.Say("Stopped when Rytm Mobile left the screen. The 0:03.0 before that is kept.")), fx2)
    }

    // 41
    @Test fun hostStoppedWhileArmedPauses() {
        val (n, fx) = reduce(armed(usb(5)), RecEvent.HostStopped)
        assertEquals(RecState.Paused, n.state)
        assertFalse(n.hostVisible)
        assertEquals(listOf<RecEffect>(RecEffect.CloseInput), fx)
        // Opening, Failed and NoInput pause too
        for (s in listOf(RecState.Opening(usb(5)), RecState.Failed(usb(5), "x"), RecState.NoInput)) {
            val (p, pfx) = reduce(m(s), RecEvent.HostStopped)
            assertEquals(RecState.Paused, p.state); assertEquals(listOf<RecEffect>(RecEffect.CloseInput), pfx)
        }
    }

    // 42
    @Test fun hostStoppedWhileAskingKeepsState() {
        val asking = m(RecState.NeedsPermission(Perm.ASKING))
        val (n, fx) = reduce(asking, RecEvent.HostStopped)
        assertEquals(asking.state, n.state)
        assertFalse(n.hostVisible)
        assertEquals(none, fx)
        for (s in listOf(RecState.Idle, RecState.Paused, RecState.Finalizing(usb(5), f(5), "R1", StopReason.USER))) {
            val (p, pfx) = reduce(m(s), RecEvent.HostStopped)
            assertEquals(s, p.state); assertFalse(p.hostVisible); assertEquals(none, pfx)
        }
    }

    // 43
    @Test fun hostStartedReopensPaused() {
        val paused = armed(usb(5)).copy(state = RecState.Paused, hostVisible = false)
        val (n, fx) = reduce(paused, RecEvent.HostStarted(true, false, listOf(usb(5))))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertTrue(n.hostVisible)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
    }

    // 44
    @Test fun hostStartedWithoutGrant() {
        val paused = m(RecState.Paused).copy(hostVisible = false)
        assertEquals(RecState.NeedsPermission(Perm.RATIONALE), reduce(paused, RecEvent.HostStarted(false, true, emptyList())).state())
        val (b, fx) = reduce(paused, RecEvent.HostStarted(false, false, emptyList()))
        assertEquals(RecState.NeedsPermission(Perm.BLOCKED), b.state)
        assertTrue(b.hostVisible); assertEquals(none, fx)
    }

    // 45
    @Test fun blockedThenGrantedInSettings() {
        val blocked = m(RecState.NeedsPermission(Perm.BLOCKED)).copy(hostVisible = false)
        val (n, fx) = reduce(blocked, RecEvent.HostStarted(true, false, listOf(usb(5))))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertTrue(n.hostVisible)
        assertEquals(listOf<RecEffect>(RecEffect.OpenInput(usb(5))), fx)
        // still not granted: only hostVisible changes
        val (still, fx2) = reduce(blocked, RecEvent.HostStarted(false, false, emptyList()))
        assertEquals(RecState.NeedsPermission(Perm.BLOCKED), still.state); assertTrue(still.hostVisible); assertEquals(none, fx2)
    }

    // 46
    @Test fun finalizeFailedSaysAndContinues() {
        val fin = m(RecState.Finalizing(usb(5), f(5), "R1106-174233", StopReason.USER), listOf(usb(5)))
        val (n, fx) = reduce(fin, RecEvent.FinalizeFailed("disk error"))
        assertEquals(RecState.Armed(usb(5), f(5)), n.state)
        assertEquals(listOf<RecEffect>(RecEffect.Say(
            "Couldn't finish the take: disk error. It is recovered the next time you open the recorder.")), fx)
        assertTrue((fx.single() as RecEffect.Say).text.startsWith("Couldn't finish the take: "))
        assertNull(n.lastTake)
    }

    // 47
    @Test fun takeUpdatedAndDeletedTouchLastTake() {
        val t = take()
        val base = armed(usb(5)).copy(lastTake = t)
        val updated = t.copy(savedUri = "content://x")
        assertEquals(updated, reduce(base, RecEvent.TakeUpdated(updated)).first.lastTake)
        assertEquals(t, reduce(base, RecEvent.TakeUpdated(take("R0101-000000"))).first.lastTake)
        assertNull(reduce(base, RecEvent.TakeDeleted(t.id)).first.lastTake)
        assertEquals(t, reduce(base, RecEvent.TakeDeleted("other")).first.lastTake)
    }

    // 48
    @Test fun capturingFlag() {
        val i = usb(5)
        assertTrue(m(RecState.Recording(i, f(5), "R1", 600)).capturing)
        assertTrue(m(RecState.Finalizing(i, f(5), "R1", StopReason.USER)).capturing)
        for (s in listOf(RecState.Idle, RecState.NeedsPermission(Perm.ASKING), RecState.NoInput, RecState.Opening(i),
            RecState.Armed(i, f(5)), RecState.Failed(i, "x"), RecState.Paused)) assertFalse(s.toString(), m(s).capturing)
    }

    // --- rows the spec table implies but the numbered cases do not spell out --------------------------------------

    @Test fun finalizedAfterUnplugWithPausedHostClosesInput() {
        // host left while Finalizing(UNPLUGGED): the input was never closed by HostStopped, so next() closes it
        val (fin, _) = reduce(recording(usb(5), listOf(usb(5), mic(1))), RecEvent.Devices(listOf(mic(1))))
        val (hidden, fx0) = reduce(fin, RecEvent.HostStopped)
        assertEquals(none, fx0)
        val (n, fx) = reduce(hidden, RecEvent.Finalized(take(reason = StopReason.UNPLUGGED)))
        assertEquals(RecState.Paused, n.state)
        assertEquals(RecEffect.CloseInput, fx.last())
    }

    @Test fun routeChangedFinalizedReopensSameInputWhenStillPresent() {
        val (mid, _) = reduce(recording(usb(5)), RecEvent.RouteChanged(1, "Phone mic"))
        val (n, fx) = reduce(mid, RecEvent.Finalized(take(reason = StopReason.ROUTE_CHANGED)))
        assertEquals(RecState.Opening(usb(5)), n.state)
        assertEquals("Stopped: Android moved the input away from $USB5. The 0:03.0 before that is kept.", (fx[1] as RecEffect.Say).text)
    }

    @Test fun devicesWhileFinalizingOnlyRefreshesTheList() {
        val fin = m(RecState.Finalizing(usb(5), f(5), "R1", StopReason.USER), listOf(usb(5)))
        val (n, fx) = reduce(fin, RecEvent.Devices(listOf(usb(5), mic(1))))
        assertEquals(fin.state, n.state)
        assertEquals(2, n.inputs.size)
        assertEquals(none, fx)
    }

    @Test fun devicesWhileIdleChangeNothing() {
        val (n, fx) = reduce(RecModel(), RecEvent.Devices(listOf(usb(5))))
        assertEquals(RecModel(), n); assertEquals(none, fx)
    }

    @Test fun closeFromNeedsPermission() {
        val (n, fx) = reduce(m(RecState.NeedsPermission(Perm.BLOCKED)), RecEvent.Close)
        assertEquals(RecState.Idle, n.state); assertFalse(n.open)
        assertEquals(listOf<RecEffect>(RecEffect.CloseInput), fx)
    }

    @Test fun openingAutoSwitchesLikeArmed() {
        val o = m(RecState.Opening(mic(1)), listOf(mic(1)))
        val (n, fx) = reduce(o, RecEvent.Devices(listOf(mic(1), usb(9))))
        assertEquals(9, (n.state as RecState.Opening).input.id)
        assertEquals(RecEffect.Say("Using $USB5."), fx[1])
    }

    @Test fun unknownPairsLeaveTheModelAlone() {
        val a = armed(usb(5))
        for (e in listOf(RecEvent.AskAgain, RecEvent.Retry, RecEvent.Stop, RecEvent.Finalized(take()),
            RecEvent.FinalizeFailed("x"), RecEvent.OpenFailed(5, "x"), RecEvent.PermissionResult(true, false, emptyList()),
            RecEvent.Interrupt(StopReason.USER))) {
            val (n, fx) = reduce(a, e)
            assertEquals(e.toString(), a, n); assertEquals(none, fx)
        }
    }
}
