package com.example.convertjpgtoheic

/**
 * Estimates how long a step of a run has left, from its pace so far.
 *
 * The average since the step began, rather than a recent window: the steps this serves — checking
 * files, rewriting them, waiting on the media scanner — run at a steady per-file cost, so the long
 * average is both accurate and free of the jitter a short window would show. Nothing is offered
 * until there is enough to judge by, so the first reading is not a wild guess.
 */
class Eta(private val total: Int, private val now: () -> Long = System::currentTimeMillis) {

    private val startedAt = now()

    /** Milliseconds left after [done] of the total, or null while it is too early to tell. */
    fun remainingMs(done: Int): Long? {
        if (done < MIN_DONE || done >= total) return null
        val elapsed = now() - startedAt
        if (elapsed < MIN_ELAPSED_MS) return null
        return elapsed * (total - done) / done
    }

    companion object {
        private const val MIN_DONE = 20
        private const val MIN_ELAPSED_MS = 5_000L
    }
}
