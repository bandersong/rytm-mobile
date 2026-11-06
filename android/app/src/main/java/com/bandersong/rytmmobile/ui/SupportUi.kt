package com.bandersong.rytmmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandersong.rytmmobile.*

/** INTEGRATION with the recording branch: return its "recording now" state here. One line, this file only (§8 R1). */
private fun AppState.recordingNow(): Boolean = capturing

private const val NOTICE_LEAD = "Every feature works, today and forever: nothing is locked and nothing expires."
private const val NOTICE_ASK = "If Rytm Mobile earns a place in your setup, please support it. Once you have, tap I've supported it and this note won't come back."
private const val AFTER = "Thanks for taking a look. If you chipped in, tap I've supported it and this note won't come back."
private const val THANKS = "Thank you! This note won't come back."
private const val ABOUT = "Rytm Mobile is free software under the GPL‑3.0. Every feature works for everyone, whether you support it or not."
private const val THANKED = "Thank you for supporting Rytm Mobile."
private const val CLOSE = "Close the support note"

/** The support note's slot, under the update banner (Screens.kt App). Shows nothing unless NagPolicy says so. */
@Composable
fun SupportSlot(vm: AppState, compact: Boolean) {
    val note = SupportNote.get(LocalContext.current)
    val m = NagMoment(
        enabled = BuildConfig.SUPPORT_NAG, busy = vm.busy, recording = vm.recordingNow(),
        updating = vm.updateProgress >= 0f, failed = vm.failed, updateOffered = vm.update != null, updateChecking = vm.updateChecking, cramped = compact,
    )
    val v = note.verdict(m)
    LaunchedEffect(v) { if (v == NagVerdict.Grant) note.grant(m) }
    if (v is NagVerdict.Show) SupportCard(note, v.mode, busy = vm.busy)
}

@Composable
private fun SupportCard(note: SupportNote, mode: NagMode, busy: Boolean) {
    val ctx = LocalContext.current
    Box(Modifier.padding(start = 34.dp, end = 18.dp, bottom = 10.dp)) {
        Panel("Support", "free software", accent = P.Signal, trailing = { CloseX { note.later() } }) {
            when (mode) {
                NagMode.NOTICE -> {
                    Text(NOTICE_LEAD, style = T.Body)
                    Spacer(Modifier.height(6.dp))
                    Text(NOTICE_ASK, style = T.Small)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HardButton("Support", { note.open(ctx) }, small = true, color = P.Cv, enabled = !busy)
                        Spacer(Modifier.width(4.dp))
                        GhostButton("Later", { note.later() })
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton("I've supported it", { note.supported(fromCard = true) }, color = P.Muted)
                }
                NagMode.AFTER_SUPPORT -> {
                    Text(AFTER, style = T.Body)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HardButton("I've supported it", { note.supported(fromCard = true) }, small = true, color = P.Cv)
                        Spacer(Modifier.width(4.dp))
                        GhostButton("Later", { note.later() })
                    }
                }
                NagMode.THANKS -> Text(THANKS, style = T.Body)
            }
            note.message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.Small.copy(color = P.Signal)) }
        }
    }
}

/** The always-there way in: an About panel at the foot of the Rytm tab, connected or not. */
@Composable
fun SupportEntry(vm: AppState) {
    val ctx = LocalContext.current
    val note = SupportNote.get(ctx)
    Panel("About", "free software · GPL‑3.0", accent = P.Signal) {
        Text(ABOUT, style = T.Small)
        Spacer(Modifier.height(12.dp))
        HardButton("Support", { note.open(ctx) }, small = true, color = P.Cv, enabled = !vm.busy && !vm.recordingNow())
        Spacer(Modifier.height(8.dp))
        if (note.state?.supporter == true) Text(THANKED, style = T.Small)
        else GhostButton("I've supported it", { note.supported(fromCard = false) }, color = P.Muted)
        note.message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.Small.copy(color = P.Signal)) }
    }
}

@Composable
private fun CloseX(onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = CLOSE },
        contentAlignment = Alignment.Center,
    ) { Text("×", style = T.Title.copy(color = P.Muted, fontSize = 20.sp)) }
}
