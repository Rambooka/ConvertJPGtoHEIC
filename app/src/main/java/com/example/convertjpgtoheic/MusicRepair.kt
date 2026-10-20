package com.example.convertjpgtoheic

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Repairs Opus files the music conversion already made: adds the `YEAR` tag Android's music
 * library reads the year from ([OggOpus.withYear]), which the converter used to leave out.
 *
 * The audio is never re-encoded. The file is streamed through [OggOpus.retag] into a copy beside
 * the app, and that copy is checked — the new tags, and every audio page identical to the
 * original's ([OggOpus.sameAudio]) — before it is written over the original. Overwriting in place
 * rather than publishing a new file keeps the song's MediaStore row, and with it any playlist
 * that lists it. The result is then read back and compared with the checked copy byte for byte.
 */
class MusicRepair(private val resolver: ContentResolver, private val staging: File) {

    sealed interface Diagnosis {
        /** Already has a `YEAR`. */
        data object Fine : Diagnosis

        /** Has no `YEAR`, and no `DATE` to take one from: nothing to repair it with. */
        data object NoDate : Diagnosis

        data class Needs(val comments: List<Pair<String, String>>) : Diagnosis

        data class Cannot(val reason: String) : Diagnosis
    }

    sealed interface Outcome {
        data object Repaired : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun diagnose(uri: Uri): Diagnosis {
        val comments = try {
            resolver.openInputStream(uri)?.buffered()?.use { OggOpus.readComments(it).second }
                ?: return Diagnosis.Cannot("it could not be opened")
        } catch (e: OggOpus.FormatException) {
            return Diagnosis.Cannot("it is not an Ogg Opus file (${e.message})")
        } catch (e: IOException) {
            return Diagnosis.Cannot("it could not be read (${e.message})")
        }
        return diagnose(comments)
    }

    /**
     * Rewrites [uri] with [fix]'s tags.
     *
     * @throws SecurityException if the app may not write to [uri]. That is checked first, so
     *   nothing has been read or changed when it is thrown.
     */
    fun apply(uri: Uri, displayName: String, fix: Diagnosis.Needs): Outcome {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: return Outcome.Failed("it could not be opened for writing")
        if (!staging.isDirectory) staging.mkdirs()
        val staged = File(staging, "repair-${System.nanoTime()}.opus")
        var keepStaged = false
        try {
            pfd.use {
                val fd = it.fileDescriptor

                // The repaired copy, checked before it goes anywhere near the original.
                open(uri).use { input -> staged.outputStream().buffered().use { out -> OggOpus.retag(input, out, fix.comments) } }
                val written = staged.inputStream().buffered().use { s -> OggOpus.readComments(s).second }
                if (written != fix.comments) return Outcome.Failed("the repaired copy's tags came out wrong; left as it was")
                val same = open(uri).use { a -> staged.inputStream().buffered().use { b -> OggOpus.sameAudio(a, b) } }
                if (!same) return Outcome.Failed("the repaired copy's audio differed; left as it was")

                // Over the original, in place. A second attempt if it does not read back exactly.
                repeat(WRITE_ATTEMPTS) { attempt ->
                    overwrite(fd, staged)
                    if (open(uri).use { a -> staged.inputStream().buffered().use { b -> identical(a, b) } }) {
                        return Outcome.Repaired
                    }
                    Log.w(TAG, "$displayName did not read back as written (attempt ${attempt + 1})")
                }
                keepStaged = true
                Log.e(TAG, "$displayName may be damaged; the checked copy is at ${staged.path}")
                return Outcome.Failed("it did not read back as written; a good copy was kept at ${staged.path}")
            }
        } finally {
            if (!keepStaged) staged.delete()
        }
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri)?.buffered() ?: throw IOException("could not be opened")

    /** Writes [from] over the whole of [fd] from the start, and cuts the file to its length. */
    private fun overwrite(fd: java.io.FileDescriptor, from: File) {
        val buffer = ByteArray(COPY_BUFFER)
        var position = 0L
        from.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var done = 0
                while (done < n) {
                    val w = Os.pwrite(fd, buffer, done, n - done, position + done)
                    if (w <= 0) throw IOException("short write at ${position + done}")
                    done += w
                }
                position += n
            }
        }
        Os.ftruncate(fd, position)
        Os.fsync(fd)
    }

    private fun identical(a: InputStream, b: InputStream): Boolean {
        val bufA = ByteArray(COPY_BUFFER)
        val bufB = ByteArray(COPY_BUFFER)
        while (true) {
            val n = readFully(a, bufA)
            if (readFully(b, bufB) != n) return false
            if (n == 0) return true
            for (i in 0 until n) if (bufA[i] != bufB[i]) return false
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var done = 0
        while (done < into.size) {
            val n = input.read(into, done, into.size - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    companion object {
        private const val TAG = "MusicRepair"
        private const val WRITE_ATTEMPTS = 2
        private const val COPY_BUFFER = 256 * 1024

        /** The rules, free of Android I/O so they can be tested directly. */
        internal fun diagnose(comments: List<Pair<String, String>>): Diagnosis {
            if (comments.any { it.first.equals("YEAR", ignoreCase = true) }) return Diagnosis.Fine
            val fixed = OggOpus.withYear(comments)
            return if (fixed.size == comments.size) Diagnosis.NoDate else Diagnosis.Needs(fixed)
        }
    }
}
