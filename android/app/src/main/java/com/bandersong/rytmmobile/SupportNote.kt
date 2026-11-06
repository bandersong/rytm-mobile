package com.bandersong.rytmmobile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The support note's Android side: its own prefs file ("support", apart from AppState's "rytm"), the app's
 * foreground/background (ProcessLifecycleOwner, so MainActivity needs no hook), and the support page.
 * Every change runs on the main thread and writes the whole record, so two changes never interleave.
 * get() only from the main thread (composition): addObserver requires it.
 */
class SupportNote private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val policy = NagPolicy(System::currentTimeMillis)

    /** null: the record can't be read (NagCodec.read). The note then stays silent and the file is left as it is. */
    var state by mutableStateOf(runCatching { NagCodec.read(prefs.all) }.getOrNull())
        private set
    /** One short line under the buttons: no app opened the page, or a choice could not be saved. */
    var message by mutableStateOf<String?>(null)
        private set
    /** The app is in front. Nothing is decided before this process's first ON_START. */
    private var live by mutableStateOf(false)

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { change { policy.foreground(it) }; live = true }
                Lifecycle.Event.ON_STOP -> { live = false; change { policy.background(it) } }
                else -> Unit
            }
        })
    }

    fun verdict(m: NagMoment): NagVerdict =
        state?.let { policy.decide(it, m.copy(foreground = live)) } ?: NagVerdict.Hide(NagWhy.UNREADABLE)

    /** Spends one impression: only if still due right now, and only once the whole record is saved and read back. */
    fun grant(m: NagMoment) { if (verdict(m) == NagVerdict.Grant) change(sync = true) { policy.grant(it) } }

    fun later() { message = null; change { policy.later(it) } }

    fun supported(fromCard: Boolean) {
        message = null
        if (!change(sync = true) { policy.supported(it, fromCard) }) message = SAVE_FAILED
    }

    fun open(ctx: Context) {
        message = null
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SUPPORT_URL))
            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { message = noApp(); return }
        change { policy.openedSupport(it) }
    }

    /** Applies f and writes the whole record. sync: commit() and read back, and keep the change only if both held. */
    private fun change(sync: Boolean = false, f: (NagState) -> NagState): Boolean {
        val s = state ?: return false
        val n = f(s)
        if (n == s) return true
        val e = prefs.edit()
        for ((k, v) in NagCodec.toMap(n)) when (v) {
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        if (sync) {
            if (!e.commit()) return false
            if (runCatching { NagCodec.read(prefs.all) }.getOrNull() != n) return false
        } else e.apply()
        state = n
        return true
    }

    companion object {
        const val PREFS = "support"
        const val SAVE_FAILED = "Couldn't save that on this phone. Try again."
        fun noApp() = "No app on this phone could open the support page. It's at ${Uri.parse(BuildConfig.SUPPORT_URL).host}."

        @Volatile private var instance: SupportNote? = null
        fun get(ctx: Context): SupportNote = instance ?: synchronized(this) {
            instance ?: SupportNote(ctx.applicationContext).also { instance = it }
        }
    }
}
