package com.example.convertjpgtoheic

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import java.io.InputStream

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

    private val ALLOWED_PRIMARY_DIRS = listOf("DCIM", "Pictures")
}

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

    companion object {
        const val HEIC_MIME = "image/heic"

        /** EXIF stores whole seconds, so a rescanned HEIC may differ in the milliseconds. */
        private const val DATE_MATCH_TOLERANCE_MS = 1000L
        private const val TAG = "PhotoRepository"
    }
}
