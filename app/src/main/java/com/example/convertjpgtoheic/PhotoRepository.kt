package com.example.convertjpgtoheic

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Output naming, kept free of Android types so the awkward cases can be tested directly.
 */
object PhotoNaming {

    /**
     * Name without its extension. A leading-dot name such as `.jpg` has an empty part before the
     * dot; trimming it naively would produce the hidden file `.heic`, so the whole name is kept.
     */
    fun baseName(displayName: String): String =
        displayName.substringBeforeLast('.', displayName).ifBlank { displayName }

    fun heicName(displayName: String): String = baseName(displayName) + ".heic"

    /**
     * Where the HEIC may actually be written.
     *
     * The images collection only accepts `DCIM/` or `Pictures/` as the top-level folder — an
     * insert with anything else throws. Plenty of real photos live outside both (`Download/` from
     * a browser or messenger, `Documents/`), and letting that throw would abort the whole run, so
     * those are nested under `Pictures/` instead of being refused.
     *
     * Every place that has to find the counterpart of a JPG goes through this, or "already
     * converted" and the leftover clean-up would look in the wrong folder.
     */
    fun targetRelativePath(sourcePath: String): String {
        val normalised = sourcePath.trim('/')
        if (normalised.isEmpty()) return "Pictures/"
        val primary = normalised.substringBefore('/')
        val allowed = ALLOWED_PRIMARY_DIRS.any { it.equals(primary, ignoreCase = true) }
        return if (allowed) "$normalised/" else "Pictures/$normalised/"
    }

    /** [targetRelativePath] for the video collection, which accepts `Movies/` as well. */
    fun targetVideoPath(sourcePath: String): String {
        val normalised = sourcePath.trim('/')
        if (normalised.isEmpty()) return "Movies/"
        val primary = normalised.substringBefore('/')
        val allowed = ALLOWED_VIDEO_DIRS.any { it.equals(primary, ignoreCase = true) }
        return if (allowed) "$normalised/" else "Movies/$normalised/"
    }

    private val ALLOWED_PRIMARY_DIRS = listOf("DCIM", "Pictures")
    private val ALLOWED_VIDEO_DIRS = listOf("DCIM", "Movies", "Pictures")
}

/** One HEIC in the library, with what a repair needs to judge and fix it. */
data class HeicRow(
    val uri: Uri,
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    /** Null when MediaStore has no capture date, which is what a repair looks for. */
    val dateTakenMs: Long?,
    val orientation: Int,
    /** Filesystem path, used only to ask the scanner to re-read the file. */
    val path: String?,
)

/** What MediaStore currently says about a file's date and orientation. */
data class CaptureState(val dateTakenMs: Long?, val orientation: Int)

/** One JPG in the library, with everything we need to rebuild its MediaStore row as a HEIC. */
data class SourcePhoto(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    /**
     * Which external volume holds this photo. Carried because reads span every volume but writes
     * do not: inserting into the synthetic "external" collection always lands on internal storage,
     * which would quietly move an SD-card photo across volumes and then delete the original.
     */
    val volumeName: String,
    val bucketName: String?,
    val sizeBytes: Long,
    /** Milliseconds. Never 0 — falls back to DATE_MODIFIED when the row has no DATE_TAKEN. */
    val dateTakenMs: Long,
    /** Seconds, as MediaStore stores it. */
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
) {
    val baseName: String get() = PhotoNaming.baseName(displayName)
    val heicName: String get() = PhotoNaming.heicName(displayName)
}

/**
 * The date-range predicate, kept separate so it can be reasoned about and tested on its own.
 *
 * The awkward part is that "no capture date" has two spellings in the wild: DATE_TAKEN can be NULL,
 * but plenty of devices write 0 instead. Treating only NULL as unknown makes every 0 row invisible
 * — it fails the range test and never reaches the NULL branch — so such photos would silently
 * never be offered for conversion.
 */
internal object JpgQuery {

    private val MIME = MediaStore.Images.Media.MIME_TYPE
    private val TAKEN = MediaStore.Images.Media.DATE_TAKEN
    private val MODIFIED = MediaStore.Images.Media.DATE_MODIFIED

    val selection: String =
        "($MIME = ? OR $MIME = ?) AND " +
            "(($TAKEN IS NOT NULL AND $TAKEN > 0 AND $TAKEN BETWEEN ? AND ?) OR " +
            "(($TAKEN IS NULL OR $TAKEN <= 0) AND $MODIFIED BETWEEN ? AND ?))"

    fun args(startMs: Long, endMs: Long): Array<String> = arrayOf(
        "image/jpeg", "image/jpg",
        startMs.toString(), endMs.toString(),
        // DATE_MODIFIED is stored in seconds, DATE_TAKEN in milliseconds.
        (startMs / 1000).toString(), (endMs / 1000).toString(),
    )

    /**
     * The capture date to stamp on the HEIC. Falls back to DATE_MODIFIED whenever DATE_TAKEN is
     * absent — taking a literal 0 would date the converted photo to January 1970.
     */
    fun effectiveDateMs(dateTakenMs: Long?, dateModifiedSec: Long): Long =
        if (dateTakenMs != null && dateTakenMs > 0) dateTakenMs else dateModifiedSec * 1000
}

/**
 * A HEIC that made it into the gallery.
 *
 * [datesApplied] is reported rather than assumed: restamping the capture date is the whole point
 * of the conversion, so a build that rejects the write is something the user needs told, not
 * something to swallow.
 */
data class PublishedHeic(val uri: Uri, val datesApplied: Boolean)

/** Every MediaStore read and write lives here, so the conversion logic stays free of them. */
class PhotoRepository(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /**
     * JPGs whose capture date falls inside [startMs]..[endMs], both ends inclusive.
     *
     * Screenshots and downloaded images routinely have a null DATE_TAKEN, so for those rows the
     * range is matched against DATE_MODIFIED instead — a plain `DATE_TAKEN BETWEEN ...` would
     * silently drop every one of them.
     */
    fun queryJpgs(startMs: Long, endMs: Long): List<SourcePhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
        )

        val taken = MediaStore.Images.Media.DATE_TAKEN
        val modified = MediaStore.Images.Media.DATE_MODIFIED

        val photos = mutableListOf<SourcePhoto>()
        resolver.query(
            collection,
            projection,
            JpgQuery.selection,
            JpgQuery.args(startMs, endMs),
            "$taken ASC, $modified ASC",
        )
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val volumeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val modifiedSec = cursor.getLong(modCol)
                    photos += SourcePhoto(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                        displayName = name,
                        relativePath = cursor.getString(pathCol) ?: "Pictures/",
                        volumeName = cursor.getString(volumeCol)
                            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        bucketName = cursor.getString(bucketCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateTakenMs = JpgQuery.effectiveDateMs(cursor.getLongOrNull(takenCol), modifiedSec),
                        dateModifiedSec = modifiedSec,
                        dateAddedSec = cursor.getLong(addedCol),
                    )
                }
            }
        return photos
    }

    /** True if a HEIC with this base name already sits in the same folder — makes re-runs safe. */
    fun heicAlreadyExists(photo: SourcePhoto): Boolean {
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(photo.heicName, PhotoNaming.targetRelativePath(photo.relativePath))
        // Scoped to the photo's own volume: a same-named HEIC on a different card is not this
        // photo's counterpart.
        val scoped = MediaStore.Images.Media.getContentUri(photo.volumeName)
        return resolver.query(scoped, arrayOf(MediaStore.Images.Media._ID), selection, args, null)
            ?.use { it.count > 0 } ?: false
    }

    /**
     * JPGs in this range that already have a matching HEIC beside them — the leftovers from a run
     * that was cancelled, killed, or whose delete prompt was declined.
     *
     * Without this the duplicates are unrecoverable in-app: "skip already converted" skips exactly
     * these, so a re-run would never clear the stranded originals.
     */
    fun findStrandedOriginals(range: DateRange): List<SourcePhoto> {
        val jpgs = queryJpgs(range.startMs, range.endMs)
        if (jpgs.isEmpty()) return emptyList()

        // One query for every HEIC in the library, then match in memory — far cheaper than a
        // per-photo existence check across a few hundred candidates.
        //
        // A name match alone is not enough to justify deleting the JPG: a zero-byte file left by a
        // crashed run would qualify, and so would an unrelated HEIC that happens to share the
        // name. The counterpart must also be non-empty and carry the same capture date.
        val heics = mutableMapOf<String, Long>()
        val selection = "${MediaStore.Images.Media.MIME_TYPE} IN (?, ?)"
        val args = arrayOf(HEIC_MIME, "image/heif")
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val volumeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (cursor.getLong(sizeCol) <= 0L) continue
                val taken = JpgQuery.effectiveDateMs(
                    cursor.getLongOrNull(takenCol),
                    cursor.getLong(modCol),
                )
                val volume = cursor.getString(volumeCol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                heics[key(volume, cursor.getString(pathCol) ?: "", name)] = taken
            }
        }

        return jpgs.filter { jpg ->
            val expected = key(
                jpg.volumeName,
                PhotoNaming.targetRelativePath(jpg.relativePath),
                jpg.heicName,
            )
            val counterpart = heics[expected] ?: return@filter false
            // Allow a second of slack: DATE_TAKEN is milliseconds but EXIF only records whole
            // seconds, so a rescanned HEIC can differ from its source in the sub-second digits.
            kotlin.math.abs(counterpart - jpg.dateTakenMs) <= DATE_MATCH_TOLERANCE_MS
        }
    }

    private fun key(volumeName: String, relativePath: String, displayName: String) =
        "$volumeName:${relativePath.trimEnd('/')}/${displayName.lowercase()}"

    /**
     * Reads the last [length] bytes of the file, for spotting a motion-photo trailer without
     * pulling a multi-megabyte JPEG through memory.
     */
    fun readTail(uri: Uri, length: Int): ByteArray? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val size = descriptor.statSize
            if (size <= 0) return@use null
            val take = minOf(length.toLong(), size).toInt()
            val buffer = ByteArray(take)
            // pread, rather than a FileInputStream over the same descriptor: that stream owns and
            // closes the fd, so the enclosing ParcelFileDescriptor.close() then failed and the
            // whole read came back null.
            var read = 0
            while (read < take) {
                val r = Os.pread(descriptor.fileDescriptor, buffer, read, take - read, size - take + read)
                if (r <= 0) break
                read += r
            }
            if (read == take) buffer else buffer.copyOf(read)
        }
    }.getOrNull()

    /**
     * Parses the Samsung SEF trailer, telling us whether the photo is a motion photo and where its
     * trailer begins. Reads only the tail and the file size, so it costs a few kilobytes.
     */
    fun readSefInfo(uri: Uri): SefInfo? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val size = descriptor.statSize
            if (size <= 0) return@use null
            val take = minOf(SefTrailer.TAIL_BYTES.toLong(), size).toInt()
            val buffer = ByteArray(take)
            var read = 0
            while (read < take) {
                val r = Os.pread(descriptor.fileDescriptor, buffer, read, take - read, size - take + read)
                if (r <= 0) break
                read += r
            }
            if (read < take) null else SefTrailer.parse(buffer, size)
        }
    }.getOrNull()

    /**
     * Streams the source file from [start] to its end into [out], returning the number of bytes
     * copied. Used to lift a motion photo's SEF trailer (its video) onto the finished HEIC.
     */
    fun copyRange(uri: Uri, start: Long, out: OutputStream): Long = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val fd = descriptor.fileDescriptor
            Os.lseek(fd, start, OsConstants.SEEK_SET)
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val r = Os.read(fd, buffer, 0, buffer.size)
                if (r <= 0) break
                out.write(buffer, 0, r)
                total += r
            }
            total
        } ?: 0L
    }.getOrElse {
        Log.w(TAG, "Could not copy the trailer of $uri", it)
        0L
    }

    /**
     * Opens the original bytes. With ACCESS_MEDIA_LOCATION granted this asks for the *unredacted*
     * file; without it MediaStore strips the GPS tags out of the stream it hands back, and we would
     * copy a location-less EXIF block into the HEIC without ever noticing.
     */
    fun openOriginal(uri: Uri, requireOriginal: Boolean): InputStream? {
        if (requireOriginal) {
            try {
                return resolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (e: Exception) {
                Log.w(TAG, "setRequireOriginal failed for $uri; falling back to redacted copy", e)
            }
        }
        return resolver.openInputStream(uri)
    }

    /**
     * Creates the pending HEIC row, lets [writeBytes] fill it, then publishes it and stamps the
     * original's dates back on.
     *
     * The dates are re-applied *after* IS_PENDING clears: publishing kicks off a metadata scan that
     * would otherwise overwrite DATE_TAKEN with whatever it derived. The EXIF block copied into the
     * file carries the capture time as well, so the date also survives a later full rescan — this
     * column write just makes the gallery show it correctly straight away.
     *
     * @param writeBytes fills the given URI and returns the number of bytes written.
     */
    /**
      * @param writeBytes fills the row's file and returns how many bytes it wrote. Zero or less
      *   means the write failed, and the half-made row is removed rather than published.
      */
    fun publishHeic(
        photo: SourcePhoto,
        writeBytes: (Uri) -> Long,
    ): PublishedHeic? {
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.heicName)
            put(MediaStore.Images.Media.MIME_TYPE, HEIC_MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, PhotoNaming.targetRelativePath(photo.relativePath))
            put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTakenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        // insert rejects an unusable name or folder by throwing; that must fail this one photo,
        // not tear down the run.
        // Insert into the photo's own volume. The synthetic "external" collection used for
        // reads always writes to internal storage.
        val target = MediaStore.Images.Media.getContentUri(photo.volumeName)
        val uri = try {
            resolver.insert(target, pending)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create a row for ${photo.heicName}", e)
            null
        } ?: return null
        val written = try {
            writeBytes(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Writing ${photo.heicName} failed", e)
            discard(uri)
            return null
        }
        // Nothing written means nothing to publish. Refusing here is what stops the caller
        // from deleting an original whose replacement was never made.
        if (written <= 0L) {
            Log.e(TAG, "Wrote no bytes for ${photo.heicName}; discarding")
            discard(uri)
            return null
        }

        try {
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            // One unpublishable row must not take the whole run down with it.
            Log.e(TAG, "Could not publish ${photo.heicName}", e)
            discard(uri)
            return null
        }

        // Read the row back as a cross-check. Treated as advisory: a row that reports 0 or -1 is
        // MediaStore not having caught up, not evidence of a bad file, and rejecting on that would
        // fail every conversion. Only a positive, contradictory size means real corruption.
        val storedSize = sizeOf(uri)
        if (storedSize > 0 && storedSize != written) {
            Log.e(TAG, "${photo.heicName} reports $storedSize bytes, expected $written")
            discard(uri)
            return null
        }

        val datesApplied = applyCaptureDate(uri, photo)
        if (!datesApplied) {
            Log.w(TAG, "Could not restamp the capture date on ${photo.heicName}")
        }
        return PublishedHeic(uri, datesApplied)
    }

    /**
     * Makes sure the row carries the original capture date, and reports whether it does.
     *
     * Judged by reading the row back rather than by what update() returns. MediaProvider maintains
     * DATE_MODIFIED and DATE_ADDED itself and quietly filters them out; with nothing writable left
     * the call reports zero rows changed, which looks like failure even though DATE_TAKEN — the
     * column galleries actually sort on — was already set correctly at insert.
     */
    private fun applyCaptureDate(uri: Uri, photo: SourcePhoto): Boolean {
        if (captureDateMatches(uri, photo.dateTakenMs)) return true

        runCatching {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTakenMs)
                },
                null,
                null,
            )
        }.onFailure { Log.w(TAG, "DATE_TAKEN rejected for ${photo.heicName}", it) }

        // Separately, and best effort: bundling this with DATE_TAKEN is what caused the whole
        // update to be filtered away in the first place.
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_MODIFIED, photo.dateModifiedSec)
                },
                null,
                null,
            )
        }

        return captureDateMatches(uri, photo.dateTakenMs)
    }

    /** EXIF records whole seconds, so a value re-derived from the file can differ in the millis. */
    private fun captureDateMatches(uri: Uri, expectedMs: Long): Boolean {
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        }.getOrNull() ?: return false
        return kotlin.math.abs(actual - expectedMs) <= DATE_MATCH_TOLERANCE_MS
    }

    /**
      * Opens a row's file for writing, seekable, so the encoder can muxer straight into it.
      * "rw" rather than "w": MediaMuxer needs to seek back and patch the container header.
      */
    fun openForWrite(uri: Uri): android.os.ParcelFileDescriptor? = runCatching {
        resolver.openFileDescriptor(uri, "rw")
    }.getOrElse {
        Log.w(TAG, "Could not open $uri for writing", it)
        null
    }

    /** Size MediaStore reports for a row, or -1 when it cannot say. */
    fun sizeOf(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }
    }.getOrNull() ?: -1L

    /** The name a row actually ended up with — MediaStore appends " (1)" on a collision. */
    fun displayNameOf(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    /** Removes a HEIC we created ourselves — our own rows need no user prompt. */
    fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    // region videos

    private val videoCollection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** Videos captured within the range, oldest first — the same date rule as [queryJpgs]. */
    fun queryVideos(startMs: Long, endMs: Long): List<SourceVideo> {
        val taken = MediaStore.Video.Media.DATE_TAKEN
        val modified = MediaStore.Video.Media.DATE_MODIFIED
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.VOLUME_NAME,
            MediaStore.Video.Media.SIZE,
            taken,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BITRATE,
            MediaStore.Video.Media.CAPTURE_FRAMERATE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
        )
        val selection = "(($taken IS NOT NULL AND $taken > 0 AND $taken BETWEEN ? AND ?) OR " +
            "(($taken IS NULL OR $taken <= 0) AND $modified BETWEEN ? AND ?))"
        val args = arrayOf(startMs.toString(), endMs.toString(), (startMs / 1000).toString(), (endMs / 1000).toString())
        val out = ArrayList<SourceVideo>()
        resolver.query(videoCollection, projection, selection, args, "$taken ASC, $modified ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += SourceVideo(
                    uri = ContentUris.withAppendedId(videoCollection, id),
                    displayName = c.getString(1) ?: continue,
                    relativePath = c.getString(2) ?: "Movies/",
                    volumeName = c.getString(3) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    sizeBytes = c.getLong(4),
                    dateTakenMs = c.getLongOrNull(5)?.takeIf { it > 0 },
                    durationMs = c.getLong(6),
                    bitrate = c.getLongOrNull(7),
                    captureFramerate = if (c.isNull(8)) null else c.getDouble(8),
                    width = c.getInt(9),
                    height = c.getInt(10),
                    mimeType = c.getString(11),
                )
            }
        }
        return out
    }

    /** Whether a video called [name] already sits in the folder [video]'s copy would go to. */
    fun videoExistsBeside(video: SourceVideo, name: String): Boolean =
        resolver.query(
            MediaStore.Video.Media.getContentUri(video.volumeName),
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf(PhotoNaming.targetVideoPath(video.relativePath), name),
            null,
        )?.use { it.count > 0 } ?: false

    /**
     * Publishes [staged] as [SourceVideo.convertedName] beside the original, and returns its URI.
     *
     * Staged-then-copied rather than encoded in place: Transformer writes to a file path, and a
     * half-written row must never be what the gallery shows.
     */
    fun publishVideo(video: SourceVideo, staged: java.io.File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, video.convertedName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, PhotoNaming.targetVideoPath(video.relativePath))
            video.dateTakenMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Video.Media.getContentUri(video.volumeName), values)
        } catch (e: Exception) {
            Log.e(TAG, "Could not create a row for ${video.convertedName}", e)
            null
        } ?: return null
        return try {
            val copied = resolver.openOutputStream(uri, "w")?.use { out ->
                staged.inputStream().use { it.copyTo(out, COPY_BUFFER) }
            } ?: 0L
            if (copied != staged.length()) throw IllegalStateException("copied $copied of ${staged.length()} bytes")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Could not publish ${video.convertedName}", e)
            discard(uri)
            null
        }
    }

    /** Gives a converted video its original's name once the original is gone. */
    fun renameVideo(uri: Uri, name: String): Boolean = runCatching {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name) }, null, null) > 0
    }.getOrElse {
        Log.w(TAG, "Could not rename $uri to $name", it)
        false
    }

    // endregion

    // region repair

    /** Every HEIC in the library, whatever its date — a repair looks at the lot. */
    fun queryHeics(): List<HeicRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION,
            @Suppress("DEPRECATION") MediaStore.Images.Media.DATA,
        )
        val rows = ArrayList<HeicRow>()
        resolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.MIME_TYPE} IN (?, ?)",
            arrayOf(HEIC_MIME, "image/heif"),
            "${MediaStore.Images.Media._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                rows += HeicRow(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    displayName = c.getString(1) ?: continue,
                    sizeBytes = c.getLong(2),
                    dateTakenMs = c.getLongOrNull(3)?.takeIf { it > 0 },
                    orientation = c.getInt(4),
                    path = c.getString(5),
                )
            }
        }
        return rows
    }

    /**
     * Capture instants of the JPGs still in the library, keyed by [nameKey]. A HEIC whose original
     * survives can then be given its exact timezone instead of an assumed one. Names that two JPGs
     * share with different dates are dropped rather than guessed between.
     */
    fun jpgCaptureTimesByName(): Map<String, Long> {
        val out = HashMap<String, Long>()
        val ambiguous = HashSet<String>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.MIME_TYPE} IN (?, ?) AND ${MediaStore.Images.Media.DATE_TAKEN} > 0",
            arrayOf("image/jpeg", "image/jpg"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = nameKey(c.getString(0) ?: continue)
                val taken = c.getLong(1)
                val previous = out.put(key, taken)
                if (previous != null && kotlin.math.abs(previous - taken) > DATE_MATCH_TOLERANCE_MS) ambiguous += key
            }
        }
        ambiguous.forEach { out.remove(it) }
        return out
    }

    /** MediaStore's current date and orientation for each of [ids]. */
    fun captureStates(ids: Collection<Long>): Map<Long, CaptureState> {
        val out = HashMap<Long, CaptureState>()
        // Chunked: SQLite caps the number of bound parameters in one statement.
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            resolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.ORIENTATION),
                "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",") { "?" }})",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out[c.getLong(0)] = CaptureState(c.getLongOrNull(1)?.takeIf { it > 0 }, c.getInt(2))
                }
            }
        }
        return out
    }

    /**
     * Asks the media scanner to re-read [paths] and waits for it, so MediaStore reflects edits made
     * to files in place. Returns how many were scanned.
     *
     * Waits as long as the scanner keeps making progress, rather than for a fixed time: its pace
     * varies with the device and the file, and a fixed budget cut the wait short while it was still
     * working steadily. Gives up only if nothing completes for [SCAN_STALL_TIMEOUT_MS], or when
     * [onProgress] returns false (the run was cancelled).
     */
    fun scanFiles(paths: List<String>, onProgress: (scanned: Int) -> Boolean = { true }): Int {
        if (paths.isEmpty()) return 0
        val scanned = java.util.concurrent.atomic.AtomicInteger()
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ -> scanned.incrementAndGet() }

        var last = 0
        var lastProgressAt = System.currentTimeMillis()
        while (true) {
            Thread.sleep(SCAN_POLL_MS)
            val now = scanned.get()
            if (now != last) {
                last = now
                lastProgressAt = System.currentTimeMillis()
                if (!onProgress(now)) break
            }
            if (now >= paths.size) break
            if (System.currentTimeMillis() - lastProgressAt > SCAN_STALL_TIMEOUT_MS) {
                Log.w(TAG, "Media scanner stalled at $now of ${paths.size}")
                break
            }
        }
        return scanned.get()
    }

    // endregion

    companion object {
        const val HEIC_MIME = "image/heic"

        /** Matches a HEIC to the JPG it came from, whichever folder either ended up in. */
        fun nameKey(displayName: String): String = PhotoNaming.baseName(displayName).lowercase()

        private const val QUERY_CHUNK = 500
        private const val COPY_BUFFER = 1024 * 1024
        private const val SCAN_POLL_MS = 1_000L
        private const val SCAN_STALL_TIMEOUT_MS = 120_000L

        /** EXIF stores whole seconds, so a rescanned HEIC may differ in the milliseconds. */
        private const val DATE_MATCH_TOLERANCE_MS = 1000L
        private const val TAG = "PhotoRepository"
    }
}
