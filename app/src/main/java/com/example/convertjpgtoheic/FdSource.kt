package com.example.convertjpgtoheic

import android.system.Os
import java.io.FileDescriptor

/** A [ByteSource] over an open file, read with `pread` so the descriptor's position is untouched. */
class FdSource(private val fd: FileDescriptor, override val size: Long) : ByteSource {
    override fun read(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = Os.pread(fd, out, done, length - done, position + done)
            if (n <= 0) throw IllegalStateException("short read at ${position + done}")
            done += n
        }
        return out
    }
}
