package com.example.convertjpgtoheic

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * An audible nudge for when a conversion run pauses to ask about deleting the originals.
 *
 * A long run finishes minutes after it was started, often with the phone in a pocket, and the
 * deletion confirmation is a dead stop — nothing else happens until someone taps the dialog. This
 * plays a short pattern of beeps so the run does not sit there unnoticed.
 *
 * Fire-and-forget on its own thread: the beeps take a couple of seconds end to end, and the run
 * must not block on them. Failure is swallowed — a device that cannot open a [ToneGenerator]
 * (they are a limited system resource and construction can throw) simply gets no sound, never a
 * crash mid-run.
 */
object DeletionAlert {

    /** Number of beeps. Five is enough to read as "come look" rather than a single stray ping. */
    private const val BEEPS = 5

    /** How long each beep sounds, in milliseconds. */
    private const val BEEP_MS = 500

    /** Silent gap between beeps, so five 500 ms tones do not merge into one long note. */
    private const val GAP_MS = 200

    /** ToneGenerator volume, 0..100. High, since the whole point is to be noticed. */
    private const val VOLUME = 90

    /**
     * Plays [BEEPS] beeps of [BEEP_MS] each, spaced by [GAP_MS], on a background thread.
     *
     * Uses the notification stream so it follows the user's notification volume rather than
     * overriding it like an alarm would.
     */
    fun beep() {
        Thread {
            val tone = runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
            }.getOrElse {
                Log.w(TAG, "No tone generator available; skipping deletion beep", it)
                return@Thread
            }
            try {
                repeat(BEEPS) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
                    // startTone returns immediately, so wait out the tone before the next one and
                    // hold the generator open until the last beep has actually finished sounding.
                    Thread.sleep((BEEP_MS + GAP_MS).toLong())
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                tone.release()
            }
        }.apply {
            name = "deletion-alert"
            isDaemon = true
        }.start()
    }

    private const val TAG = "DeletionAlert"
}
