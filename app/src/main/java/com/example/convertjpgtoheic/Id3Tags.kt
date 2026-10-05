package com.example.convertjpgtoheic

import java.nio.charset.Charset

/**
 * Reads an MP3's ID3 tags and translates them into Vorbis comments, the tag format of an Ogg file.
 *
 * Android's Ogg writer emits an empty tag block, so without this every converted song would lose
 * its title, artist, album and cover and fall out of the music library's albums. Handles ID3v2.2,
 * 2.3 and 2.4 (including whole-tag and per-frame unsynchronisation), with ID3v1 filling any field
 * the v2 tag lacks. Compressed and encrypted frames are skipped.
 *
 * Kept free of Android types so it can be tested on the JVM.
 */
object Id3Tags {

    data class Picture(val mime: String, val type: Int, val description: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Picture && mime == other.mime &&
            type == other.type && description == other.description && data.contentEquals(other.data)

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Tags(val comments: List<Pair<String, String>>, val pictures: List<Picture>) {
        fun first(key: String): String? = comments.firstOrNull { it.first == key }?.second

        val isEmpty: Boolean get() = comments.isEmpty() && pictures.isEmpty()
    }

    /** ID3v2 picture type for the front cover; listed first, as players take the first picture. */
    const val FRONT_COVER = 3

    /** Total length of the ID3v2 tag that [header] (the file's first 10 bytes) opens, or 0 if none. */
    fun v2Length(header: ByteArray): Int {
        if (header.size < 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) return 0
        if (header[3].toInt() !in 2..4) return 0
        val footer = if (header[3].toInt() == 4 && header[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + syncsafe(header, 6) + footer
    }

    /**
     * @param v2 the ID3v2 tag from the start of the file, header included, or null.
     * @param v1 the file's last 128 bytes, or null.
     */
    fun parse(v2: ByteArray?, v1: ByteArray?): Tags {
        val comments = ArrayList<Pair<String, String>>()
        val pictures = ArrayList<Picture>()
        if (v2 != null) parseV2(v2, comments, pictures)
        if (v1 != null) {
            val have = comments.mapTo(HashSet()) { it.first }
            for ((key, value) in parseV1(v1)) if (key !in have) comments += key to value
        }
        pictures.sortBy { if (it.type == FRONT_COVER) 0 else 1 }
        return Tags(comments, pictures)
    }

    // region ID3v2

    private fun parseV2(tag: ByteArray, comments: MutableList<Pair<String, String>>, pictures: MutableList<Picture>) {
        if (tag.size < 10 || v2Length(tag) == 0) return
        val major = tag[3].toInt()
        val flags = tag[5].toInt() and 0xFF
        var body = tag.copyOfRange(10, minOf(tag.size, 10 + syncsafe(tag, 6)))
        if (flags and 0x80 != 0 && major < 4) body = unsync(body)

        var pos = 0
        if (flags and 0x40 != 0) {
            when (major) {
                2 -> return // v2.2 used this bit for whole-tag compression, which was never defined
                3 -> pos = 4 + be32(body, 0)
                4 -> pos = syncsafe(body, 0)
            }
        }

        val headerLen = if (major == 2) 6 else 10
        var year: String? = null
        var dayMonth: String? = null
        var hasDate = false

        while (pos + headerLen <= body.size) {
            if (body[pos].toInt() == 0) break // padding
            val rawId = String(body, pos, if (major == 2) 3 else 4, Charsets.ISO_8859_1)
            if (!rawId.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = when (major) {
                2 -> be24(body, pos + 3)
                3 -> be32(body, pos + 4)
                else -> v24FrameSize(body, pos)
            }
            val frameFlags = if (major == 2) 0 else ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
            val start = pos + headerLen
            if (size <= 0 || start + size > body.size) break
            pos = start + size

            var data = body.copyOfRange(start, start + size)
            if (major == 3) {
                if (frameFlags and 0x00C0 != 0) continue // compressed or encrypted
                if (frameFlags and 0x0020 != 0) data = data.copyOfRange(minOf(1, data.size), data.size)
            } else if (major == 4) {
                if (frameFlags and 0x000C != 0) continue // compressed or encrypted
                var skip = 0
                if (frameFlags and 0x0040 != 0) skip += 1 // grouping identity
                if (frameFlags and 0x0001 != 0) skip += 4 // data length indicator
                if (skip > data.size) continue
                data = data.copyOfRange(skip, data.size)
                if (frameFlags and 0x0002 != 0) data = unsync(data)
            }
            if (data.isEmpty()) continue

            val id = if (major == 2) (V22_IDS[rawId] ?: continue) else rawId
            when (id) {
                "TXXX" -> userText(data)?.let { comments += it }
                "COMM" -> commentText(data)?.let { comments += "COMMENT" to it }
                "USLT" -> commentText(data, anyDescription = true)?.let { comments += "LYRICS" to it }
                "APIC" -> picture(data, v22 = false)?.let { pictures += it }
                "PIC" -> picture(data, v22 = true)?.let { pictures += it }
                "TCON" -> for (g in textValues(data).flatMap(::genres)) comments += "GENRE" to g
                "TRCK" -> numberPair(textValues(data).firstOrNull(), "TRACKNUMBER", "TRACKTOTAL", comments)
                "TPOS" -> numberPair(textValues(data).firstOrNull(), "DISCNUMBER", "DISCTOTAL", comments)
                "TYER" -> year = textValues(data).firstOrNull()
                "TDAT" -> dayMonth = textValues(data).firstOrNull()
                "TDRC" -> textValues(data).firstOrNull()?.let { comments += "DATE" to it; hasDate = true }
                else -> TEXT_KEYS[id]?.let { key -> for (v in textValues(data)) comments += key to v }
            }
        }

        // ID3v2.3 splits the date into a year and a DDMM day; Vorbis has one DATE.
        val y = year
        if (!hasDate && y != null) {
            val dm = dayMonth
            comments += "DATE" to if (y.length == 4 && dm != null && dm.length == 4 && dm.all(Char::isDigit)) {
                "$y-${dm.substring(2, 4)}-${dm.substring(0, 2)}"
            } else {
                y
            }
        }
    }

    /**
     * A v2.4 frame size is meant to be syncsafe, but iTunes long wrote plain 32-bit sizes. Use the
     * plain reading when the bytes cannot be syncsafe, or when only it lands on another frame.
     */
    private fun v24FrameSize(body: ByteArray, pos: Int): Int {
        val safe = syncsafe(body, pos + 4)
        val plain = be32(body, pos + 4)
        if (safe == plain) return safe
        if ((4..7).any { body[pos + it].toInt() and 0x80 != 0 }) return plain
        return if (!framesAt(body, pos + 10 + safe) && framesAt(body, pos + 10 + plain)) plain else safe
    }

    private fun framesAt(body: ByteArray, pos: Int): Boolean {
        if (pos == body.size) return true
        if (pos < 0 || pos > body.size) return false
        if (body[pos].toInt() == 0) return true
        if (pos + 4 > body.size) return false
        return (0 until 4).all { val c = body[pos + it].toInt().toChar(); c in 'A'..'Z' || c in '0'..'9' }
    }

    /** A text frame's values: v2.4 separates several with NULs. */
    private fun textValues(data: ByteArray): List<String> =
        decode(data, 1, data.size, data[0].toInt()).split('\u0000').map(String::trim).filter(String::isNotEmpty)

    private fun userText(data: ByteArray): Pair<String, String>? {
        val enc = data[0].toInt()
        val (descEnd, next) = terminator(data, 1, enc)
        val key = vorbisKey(decode(data, 1, descEnd, enc)) ?: return null
        val value = decode(data, next, data.size, enc).split('\u0000').map(String::trim).firstOrNull { it.isNotEmpty() }
            ?: return null
        return key to value
    }

    /**
     * COMM and USLT: encoding, language, description, text. Comments carrying a description are
     * mostly machine data (iTunNORM, iTunSMPB), so only the plain one is kept.
     */
    private fun commentText(data: ByteArray, anyDescription: Boolean = false): String? {
        if (data.size < 5) return null
        val enc = data[0].toInt()
        val (descEnd, next) = terminator(data, 4, enc)
        if (!anyDescription && decode(data, 4, descEnd, enc).isNotBlank()) return null
        return decode(data, next, data.size, enc).trim('\u0000', ' ').takeIf { it.isNotEmpty() }
    }

    private fun picture(data: ByteArray, v22: Boolean): Picture? {
        val enc = data[0].toInt()
        val mime: String
        var pos: Int
        if (v22) {
            if (data.size < 5) return null
            mime = when (String(data, 1, 3, Charsets.ISO_8859_1).uppercase()) {
                "PNG" -> "image/png"
                else -> "image/jpeg"
            }
            pos = 4
        } else {
            val end = (1 until data.size).firstOrNull { data[it].toInt() == 0 } ?: return null
            mime = String(data, 1, end - 1, Charsets.ISO_8859_1).trim().lowercase().let {
                when {
                    it.isEmpty() || it == "jpg" || it == "image/jpg" -> "image/jpeg"
                    it == "png" -> "image/png"
                    it.contains('/') -> it
                    else -> "image/$it"
                }
            }
            pos = end + 1
        }
        if (pos >= data.size) return null
        val type = data[pos].toInt() and 0xFF
        val (descEnd, next) = terminator(data, pos + 1, enc)
        val description = decode(data, pos + 1, descEnd, enc)
        if (next >= data.size) return null
        return Picture(mime, type, description, data.copyOfRange(next, data.size))
    }

    /** "3/12" into TRACKNUMBER=3 and TRACKTOTAL=12. */
    private fun numberPair(value: String?, numberKey: String, totalKey: String, out: MutableList<Pair<String, String>>) {
        value ?: return
        val number = value.substringBefore('/').trim()
        val total = value.substringAfter('/', "").trim()
        if (number.isNotEmpty()) out += numberKey to number
        if (total.isNotEmpty()) out += totalKey to total
    }

    /**
     * Resolves ID3 genre references: "(17)", "17", "(17)Rock" (text refines the number), "RX", "CR".
     */
    internal fun genres(value: String): List<String> {
        var s = value.trim()
        val found = ArrayList<String>()
        while (s.startsWith("(") && !s.startsWith("((")) {
            val close = s.indexOf(')')
            if (close < 0) break
            genreName(s.substring(1, close))?.let { found += it }
            s = s.substring(close + 1)
        }
        if (s.startsWith("((")) s = s.substring(1)
        s = s.trim()
        if (s.isNotEmpty()) return listOf(genreName(s) ?: s)
        return found
    }

    private fun genreName(token: String): String? = when {
        token == "RX" -> "Remix"
        token == "CR" -> "Cover"
        token.isNotEmpty() && token.all(Char::isDigit) -> token.toIntOrNull()?.let { GENRES.getOrNull(it) }
        else -> null
    }

    // endregion

    // region ID3v1

    private fun parseV1(tail: ByteArray): List<Pair<String, String>> {
        if (tail.size < 128) return emptyList()
        val t = tail.copyOfRange(tail.size - 128, tail.size)
        if (t[0] != 'T'.code.toByte() || t[1] != 'A'.code.toByte() || t[2] != 'G'.code.toByte()) return emptyList()
        fun field(from: Int, len: Int) =
            String(t, from, len, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        val out = ArrayList<Pair<String, String>>()
        field(3, 30).takeIf { it.isNotEmpty() }?.let { out += "TITLE" to it }
        field(33, 30).takeIf { it.isNotEmpty() }?.let { out += "ARTIST" to it }
        field(63, 30).takeIf { it.isNotEmpty() }?.let { out += "ALBUM" to it }
        field(93, 4).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { out += "DATE" to it }
        val v11 = t[125].toInt() == 0 && t[126].toInt() != 0
        field(97, if (v11) 28 else 30).takeIf { it.isNotEmpty() }?.let { out += "COMMENT" to it }
        if (v11) out += "TRACKNUMBER" to (t[126].toInt() and 0xFF).toString()
        GENRES.getOrNull(t[127].toInt() and 0xFF)?.let { out += "GENRE" to it }
        return out
    }

    // endregion

    // region bytes

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16 // BOM-led
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun decode(data: ByteArray, from: Int, to: Int, enc: Int): String {
        if (from >= to || from >= data.size) return ""
        return String(data, from, minOf(to, data.size) - from, charset(enc)).trimEnd('\u0000')
    }

    /** Where the string at [from] ends, and where what follows it begins. */
    private fun terminator(data: ByteArray, from: Int, enc: Int): Pair<Int, Int> {
        if (enc == 1 || enc == 2) {
            var i = from
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i to i + 2
                i += 2
            }
        } else {
            for (i in from until data.size) if (data[i].toInt() == 0) return i to i + 1
        }
        return data.size to data.size
    }

    /** Removes the 0x00 stuffed after every 0xFF. */
    private fun unsync(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i].toInt() and 0xFF == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncsafe(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
    }

    private fun be32(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return 0
        return ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }

    private fun be24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 16) or ((b[at + 1].toInt() and 0xFF) shl 8) or (b[at + 2].toInt() and 0xFF)

    /** A Vorbis field name: printable ASCII except '=', conventionally upper case. */
    private fun vorbisKey(description: String): String? {
        val key = description.trim().uppercase().map { if (it in ' '..'}' && it != '=') it else '_' }.joinToString("")
        return key.takeIf { it.isNotBlank() }
    }

    // endregion

    /** ID3v2 text frames to Vorbis field names, following MusicBrainz Picard's mapping. */
    private val TEXT_KEYS = mapOf(
        "TIT2" to "TITLE",
        "TPE1" to "ARTIST",
        "TALB" to "ALBUM",
        "TPE2" to "ALBUMARTIST",
        "TCOM" to "COMPOSER",
        "TPE3" to "CONDUCTOR",
        "TPE4" to "REMIXER",
        "TEXT" to "LYRICIST",
        "TIT1" to "GROUPING",
        "TIT3" to "SUBTITLE",
        "TSST" to "DISCSUBTITLE",
        "TBPM" to "BPM",
        "TKEY" to "KEY",
        "TMOO" to "MOOD",
        "TCOP" to "COPYRIGHT",
        "TPUB" to "LABEL",
        "TSRC" to "ISRC",
        "TMED" to "MEDIA",
        "TLAN" to "LANGUAGE",
        "TENC" to "ENCODEDBY",
        "TCMP" to "COMPILATION",
        "TDOR" to "ORIGINALDATE",
        "TORY" to "ORIGINALDATE",
        "TSOP" to "ARTISTSORT",
        "TSOA" to "ALBUMSORT",
        "TSOT" to "TITLESORT",
        "TSO2" to "ALBUMARTISTSORT",
        "TSOC" to "COMPOSERSORT",
    )

    /** ID3v2.2's three-letter frame ids, as their v2.3 equivalents. */
    private val V22_IDS = mapOf(
        "TT1" to "TIT1", "TT2" to "TIT2", "TT3" to "TIT3", "TP1" to "TPE1", "TP2" to "TPE2",
        "TP3" to "TPE3", "TP4" to "TPE4", "TAL" to "TALB", "TCM" to "TCOM", "TXT" to "TEXT",
        "TRK" to "TRCK", "TPA" to "TPOS", "TYE" to "TYER", "TDA" to "TDAT", "TOR" to "TORY",
        "TCO" to "TCON", "TBP" to "TBPM", "TKE" to "TKEY", "TCR" to "TCOP", "TPB" to "TPUB",
        "TRC" to "TSRC", "TMT" to "TMED", "TLA" to "TLAN", "TEN" to "TENC", "TCP" to "TCMP",
        "TS2" to "TSO2", "TSA" to "TSOA", "TSP" to "TSOP", "TST" to "TSOT", "TSC" to "TSOC",
        "TXX" to "TXXX", "COM" to "COMM", "ULT" to "USLT", "PIC" to "PIC",
    )

    /** ID3v1 genres with Winamp's extensions, by index. */
    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz",
        "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno",
        "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno",
        "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
        "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul",
        "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer",
        "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll",
        "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin",
        "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
        "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening",
        "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony",
        "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba",
        "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock",
        "Drum Solo", "A Cappella", "Euro-House", "Dance Hall",
    )
}
