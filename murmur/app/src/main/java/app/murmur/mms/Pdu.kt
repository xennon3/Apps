package app.murmur.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * The slice of the MMS wire format (OMA-MMS-ENC 1.2 over WAP WSP) Murmur needs:
 * build an m-send-req, and read m-notification-ind, m-retrieve-conf and m-send-conf.
 * Android's own MMS service does the carrier HTTP part; this only turns bytes into
 * messages and back.
 */
object Pdu {

    // Message types (X-Mms-Message-Type values)
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val RETRIEVE_CONF = 0x84

    // Header field codes
    private const val BCC = 0x81
    private const val CC = 0x82
    private const val CONTENT_LOCATION = 0x83
    private const val CONTENT_TYPE = 0x84
    private const val DATE = 0x85
    private const val FROM = 0x89
    private const val MESSAGE_CLASS = 0x8A
    private const val MESSAGE_ID = 0x8B
    private const val MESSAGE_TYPE = 0x8C
    private const val MMS_VERSION = 0x8D
    private const val MESSAGE_SIZE = 0x8E
    private const val RESPONSE_STATUS = 0x92
    private const val SUBJECT = 0x96
    private const val TO = 0x97
    private const val TRANSACTION_ID = 0x98

    // Part header codes (WSP well-known headers)
    private const val P_CONTENT_LOCATION = 0x8E
    private const val P_CONTENT_ID = 0xC0

    const val UTF8 = 106

    private val wellKnownTypes = mapOf(
        0x03 to "text/plain", 0x06 to "text/x-vCalendar", 0x07 to "text/x-vCard",
        0x0C to "multipart/mixed", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff",
        0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x23 to "application/vnd.wap.multipart.mixed", 0x26 to "application/vnd.wap.multipart.alternative",
        0x27 to "application/xml", 0x28 to "text/xml", 0x33 to "application/vnd.wap.multipart.related",
    )
    private val typeCodes = wellKnownTypes.entries.associate { (k, v) -> v to k }

    data class Part(
        val contentType: String,
        val data: ByteArray,
        val name: String? = null,
        val contentId: String? = null,
        val contentLocation: String? = null,
        val charset: Int = 0,
    ) {
        val text: String? get() = if (contentType.startsWith("text/plain")) String(data, charsetOf(charset)) else null
    }

    data class Message(
        val type: Int,
        val transactionId: String? = null,
        val messageId: String? = null,
        val from: String? = null,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val subject: String? = null,
        val date: Long = 0,                 // seconds
        val contentLocation: String? = null,
        val size: Long = 0,
        val responseStatus: Int = 0,
        val contentType: String? = null,
        val parts: List<Part> = emptyList(),
    )

    // =================================================================== encoding

    fun sendReq(transactionId: String, to: List<String>, parts: List<Part>, subject: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MESSAGE_TYPE); out.write(SEND_REQ)
        out.write(TRANSACTION_ID); text(out, transactionId)
        out.write(MMS_VERSION); out.write(0x92) // 1.2
        out.write(FROM); out.write(1); out.write(0x81) // insert-address-token: the carrier fills in our number
        for (r in to) { out.write(TO); text(out, address(r)) }
        if (!subject.isNullOrBlank()) { out.write(SUBJECT); encodedString(out, subject) }
        out.write(MESSAGE_CLASS); out.write(0x80) // personal

        // Content-Type: multipart/related; type=application/smil; start=<smil>
        val smil = parts.firstOrNull { it.contentType == "application/smil" }
        out.write(CONTENT_TYPE)
        val ct = ByteArrayOutputStream().apply {
            if (smil != null) {
                write(0x80 or 0x33)
                write(0x89); text(this, "application/smil")
                write(0x8A); text(this, smil.contentId ?: "<smil>")
            } else {
                write(0x80 or 0x23)
            }
        }.toByteArray()
        valueLength(out, ct.size); out.write(ct)

        uintvar(out, parts.size.toLong())
        for (p in parts) {
            val type = partContentType(p)
            val headers = ByteArrayOutputStream().apply {
                p.contentId?.let { write(P_CONTENT_ID); write(0x22); write(it.toByteArray()); write(0) }
                p.contentLocation?.let { write(P_CONTENT_LOCATION); text(this, it) }
            }.toByteArray()
            uintvar(out, (type.size + headers.size).toLong())
            uintvar(out, p.data.size.toLong())
            out.write(type); out.write(headers); out.write(p.data)
        }
        return out.toByteArray()
    }

    private fun partContentType(p: Part): ByteArray {
        val params = ByteArrayOutputStream().apply {
            if (p.charset != 0) { write(0x81); integer(this, p.charset.toLong()) }
            p.name?.let { write(0x85); text(this, it) }
        }.toByteArray()
        val media = ByteArrayOutputStream().apply {
            val code = typeCodes[p.contentType]
            if (code != null) write(0x80 or code) else text(this, p.contentType)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            if (params.isEmpty() && media.size == 1) { write(media); return@apply }
            valueLength(this, media.size + params.size); write(media); write(params)
        }.toByteArray()
    }

    /** Phone numbers need the PLMN suffix; email addresses go as they are. */
    private fun address(a: String) = if ('@' in a) a else a.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN"

    private fun text(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) out.write(0x7F)
        out.write(b); out.write(0)
    }

    private fun encodedString(out: ByteArrayOutputStream, s: String) {
        if (s.all { it.code < 0x80 }) { text(out, s); return }
        val b = s.toByteArray(Charsets.UTF_8)
        valueLength(out, 1 + b.size + 1)
        out.write(0x80 or UTF8); out.write(b); out.write(0)
    }

    private fun integer(out: ByteArrayOutputStream, v: Long) {
        if (v < 0x80) { out.write(0x80 or v.toInt()); return }
        var n = v; val bytes = ArrayList<Int>()
        while (n > 0) { bytes.add(0, (n and 0xFF).toInt()); n = n shr 8 }
        out.write(bytes.size); bytes.forEach(out::write)
    }

    private fun valueLength(out: ByteArrayOutputStream, n: Int) {
        if (n <= 30) out.write(n) else { out.write(31); uintvar(out, n.toLong()) }
    }

    private fun uintvar(out: ByteArrayOutputStream, value: Long) {
        var v = value
        val tmp = ArrayList<Int>()
        tmp.add((v and 0x7F).toInt()); v = v shr 7
        while (v > 0) { tmp.add(0, ((v and 0x7F) or 0x80).toInt()); v = v shr 7 }
        tmp.forEach(out::write)
    }

    // =================================================================== decoding

    fun parse(bytes: ByteArray): Message? = runCatching { Reader(bytes).message() }.getOrNull()

    private class Reader(val b: ByteArray) {
        var i = 0
        fun u8(): Int = b[i++].toInt() and 0xFF
        fun peek(): Int = b[i].toInt() and 0xFF
        fun more() = i < b.size

        fun uintvar(): Long {
            var v = 0L
            while (true) {
                val x = u8()
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) return v
            }
        }

        fun valueLength(): Int {
            val x = u8()
            return when {
                x <= 30 -> x
                x == 31 -> uintvar().toInt()
                else -> error("bad value-length $x")
            }
        }

        fun textString(): String {
            if (more() && peek() == 0x7F) i++
            val start = i
            while (b[i].toInt() != 0) i++
            val s = String(b, start, i - start, Charsets.UTF_8)
            i++
            return s
        }

        fun longInteger(): Long {
            val n = u8()
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun integer(): Long = if (peek() >= 0x80) (u8() and 0x7F).toLong() else longInteger()

        fun encodedString(): String {
            val x = peek()
            if (x > 31) return textString()
            val len = valueLength()
            val end = i + len
            val charset = integer().toInt()
            if (more() && peek() == 0x7F) i++
            var stop = i
            while (stop < end && b[stop].toInt() != 0) stop++
            val s = String(b, i, stop - i, charsetOf(charset))
            i = end
            return s
        }

        /** Skip any header value we don't care about. */
        fun skipValue() {
            val x = peek()
            when {
                x <= 30 -> { i++; i += x }
                x == 31 -> { i++; i += uintvar().toInt() }
                x < 0x80 -> textString()
                else -> i++
            }
        }

        fun address(): String = cleanAddress(encodedString())

        fun from(): String? {
            val len = valueLength()
            val end = i + len
            val token = u8()
            val s = if (token == 0x80) address() else null // 0x81 = insert-address-token
            i = end
            return s
        }

        /** Content type value → (media type, params). */
        fun contentType(): Pair<String, Map<Int, Any>> {
            val x = peek()
            if (x >= 0x80) return (wellKnownTypes[u8() and 0x7F] ?: "application/octet-stream") to emptyMap()
            if (x > 31) return textString() to emptyMap()
            val len = valueLength()
            val end = i + len
            val media = when {
                peek() >= 0x80 -> wellKnownTypes[u8() and 0x7F] ?: "application/octet-stream"
                peek() <= 30 -> { // Well-known-media as long integer
                    val v = longInteger().toInt(); wellKnownTypes[v] ?: "application/octet-stream"
                }
                else -> textString()
            }
            val params = HashMap<Int, Any>()
            while (i < end) {
                val p = peek()
                if (p >= 0x80) {
                    val code = u8() and 0x7F
                    params[code] = paramValue()
                } else {
                    textString() // untyped parameter name
                    paramValue()
                }
            }
            i = end
            return media to params
        }

        fun paramValue(): Any {
            val x = peek()
            return when {
                x >= 0x80 -> (u8() and 0x7F).toLong()
                x <= 30 -> longInteger()
                x == 0x22 -> { i++; textString() }
                else -> textString()
            }
        }

        fun message(): Message {
            var m = Message(type = 0)
            val to = ArrayList<String>(); val cc = ArrayList<String>()
            while (more()) {
                val field = u8()
                when (field) {
                    MESSAGE_TYPE -> m = m.copy(type = u8())
                    TRANSACTION_ID -> m = m.copy(transactionId = textString())
                    MESSAGE_ID -> m = m.copy(messageId = textString())
                    FROM -> m = m.copy(from = from())
                    TO -> to += address()
                    CC -> cc += address()
                    BCC -> address()
                    SUBJECT -> m = m.copy(subject = encodedString())
                    DATE -> m = m.copy(date = longInteger())
                    CONTENT_LOCATION -> m = m.copy(contentLocation = textString())
                    MESSAGE_SIZE -> m = m.copy(size = integer())
                    RESPONSE_STATUS -> m = m.copy(responseStatus = u8())
                    MMS_VERSION -> u8()
                    CONTENT_TYPE -> {
                        val (type, _) = contentType()
                        m = m.copy(contentType = type)
                        // Content-Type is always the last header; the body follows.
                        if (type.contains("multipart")) return m.copy(to = to, cc = cc, parts = multipart())
                        val data = b.copyOfRange(i, b.size)
                        return m.copy(to = to, cc = cc, parts = listOf(Part(type, data)))
                    }
                    else -> if (field >= 0x80) skipValue() else { i--; textString(); skipValue() }
                }
            }
            return m.copy(to = to, cc = cc)
        }

        fun multipart(): List<Part> {
            val n = uintvar().toInt()
            val parts = ArrayList<Part>()
            repeat(n) {
                val headersLen = uintvar().toInt()
                val dataLen = uintvar().toInt()
                val headersEnd = i + headersLen
                val (type, params) = contentType()
                var cid: String? = null
                var cl: String? = null
                while (i < headersEnd) {
                    val h = u8()
                    when (h) {
                        P_CONTENT_ID -> { if (peek() == 0x22) i++; cid = textString() }
                        P_CONTENT_LOCATION -> cl = textString()
                        else -> if (h >= 0x80) skipValue() else {
                            // Application header: name, then value, both text.
                            i--
                            val name = textString()
                            val value = textString()
                            if (name.equals("Content-ID", true)) cid = value
                            if (name.equals("Content-Location", true)) cl = value
                        }
                    }
                }
                i = headersEnd
                val data = b.copyOfRange(i, i + dataLen)
                i += dataLen
                parts += Part(
                    contentType = type,
                    data = data,
                    name = (params[0x05] ?: params[0x17]) as? String,
                    contentId = cid,
                    contentLocation = cl,
                    charset = (params[0x01] as? Long)?.toInt() ?: 0,
                )
            }
            return parts
        }
    }

    private fun cleanAddress(a: String) = a.substringBefore("/TYPE=").trim()

    fun charsetOf(code: Int): Charset = when (code) {
        3 -> Charsets.US_ASCII
        4 -> Charsets.ISO_8859_1
        1000 -> Charsets.UTF_16BE
        1015 -> Charsets.UTF_16
        else -> Charsets.UTF_8
    }

    /** A SMIL layout that puts the picture above the text, which every phone understands. */
    fun smil(imageName: String?, textName: String?, audioName: String? = null): String = buildString {
        append("<smil><head><layout><root-layout/>")
        if (imageName != null) append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
        if (textName != null) append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\" fit=\"scroll\"/>")
        append("</layout></head><body><par dur=\"5000ms\">")
        if (imageName != null) append("<img src=\"$imageName\" region=\"Image\"/>")
        if (textName != null) append("<text src=\"$textName\" region=\"Text\"/>")
        if (audioName != null) append("<audio src=\"$audioName\"/>")
        append("</par></body></smil>")
    }
}
