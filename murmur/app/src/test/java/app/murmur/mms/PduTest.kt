package app.murmur.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class PduTest {

    private fun bytes(vararg parts: Any): ByteArray = ByteArrayOutputStream().apply {
        for (p in parts) when (p) {
            is Int -> write(p)
            is String -> { write(p.toByteArray()); write(0) }
            is ByteArray -> write(p)
        }
    }.toByteArray()

    @Test fun sendReqRoundTrips() {
        val jpeg = ByteArray(5000) { (it * 7).toByte() }
        val parts = listOf(
            Pdu.Part("application/smil", Pdu.smil("image.jpg", "text.txt").toByteArray(), contentId = "<smil>", contentLocation = "smil.xml", charset = Pdu.UTF8),
            Pdu.Part("image/jpeg", jpeg, name = "image.jpg", contentId = "<image>", contentLocation = "image.jpg"),
            Pdu.Part("text/plain", "see you there 🎉".toByteArray(), contentId = "<text>", contentLocation = "text.txt", charset = Pdu.UTF8),
        )
        val pdu = Pdu.sendReq("T123", listOf("+1 (555) 123-4567", "5559876543"), parts)
        val m = Pdu.parse(pdu)!!
        assertEquals(Pdu.SEND_REQ, m.type)
        assertEquals("T123", m.transactionId)
        assertNull(m.from) // insert-address-token
        assertEquals(listOf("+15551234567", "5559876543"), m.to)
        assertEquals("application/vnd.wap.multipart.related", m.contentType)
        assertEquals(3, m.parts.size)
        assertEquals("image/jpeg", m.parts[1].contentType)
        assertEquals("image.jpg", m.parts[1].name)
        assertEquals("<image>", m.parts[1].contentId)
        assertArrayEquals(jpeg, m.parts[1].data)
        assertEquals("see you there 🎉", m.parts[2].text)
        assertEquals("application/smil", m.parts[0].contentType)
    }

    @Test fun parsesNotificationInd() {
        val pdu = bytes(
            0x8C, 0x82,
            0x98, "abc-1",
            0x8D, 0x92,
            0x89, 14, 0x80, "+15551234567",
            0x8A, 0x80,
            0x8E, 2, 0x01, 0x2C, // size 300
            0x88, 5, 0x81, 3, 0x01, 0x51, 0x80, // expiry, relative
            0x83, "http://mms.example.com/get?id=42",
        )
        val m = Pdu.parse(pdu)!!
        assertEquals(Pdu.NOTIFICATION_IND, m.type)
        assertEquals("abc-1", m.transactionId)
        assertEquals("+15551234567", m.from)
        assertEquals(300L, m.size)
        assertEquals("http://mms.example.com/get?id=42", m.contentLocation)
    }

    @Test fun parsesRetrieveConfFromAGroup() {
        val img = byteArrayOf(1, 2, 3, 4, 5)
        val text = "hi all".toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(2) // two parts
            // text/plain; charset=utf-8, Content-ID "<t>"
            val tType = byteArrayOf(3, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte())
            val tHead = byteArrayOf(0xC0.toByte(), 0x22, '<'.code.toByte(), 't'.code.toByte(), '>'.code.toByte(), 0)
            write(tType.size + tHead.size); write(text.size); write(tType); write(tHead); write(text)
            // image/png as a short integer, no headers
            write(1); write(img.size); write(0x80 or 0x20); write(img)
        }.toByteArray()
        val pdu = bytes(
            0x8C, 0x84,
            0x8B, "msg-9",
            0x8D, 0x92,
            0x85, 4, 0x68, 0xF3, 0x5A, 0x00,
            0x89, 14, 0x80, "+15551112222",
            0x97, "+15553334444/TYPE=PLMN",
            0x97, "+15555556666/TYPE=PLMN",
            0x96, 9, 0xEA, "Picnic 🌭".substring(0, 7),
            0x84, 0x80 or 0x23,
            body,
        )
        val m = Pdu.parse(pdu)!!
        assertEquals(Pdu.RETRIEVE_CONF, m.type)
        assertEquals("msg-9", m.messageId)
        assertEquals("+15551112222", m.from)
        assertEquals(listOf("+15553334444", "+15555556666"), m.to)
        assertEquals(0x68F35A00L, m.date)
        assertNotNull(m.subject)
        assertEquals(2, m.parts.size)
        assertEquals("hi all", m.parts[0].text)
        assertEquals("<t>", m.parts[0].contentId)
        assertEquals("image/png", m.parts[1].contentType)
        assertArrayEquals(img, m.parts[1].data)
    }

    @Test fun parsesSendConf() {
        val m = Pdu.parse(bytes(0x8C, 0x81, 0x98, "T1", 0x8D, 0x92, 0x92, 0x80, 0x8B, "mid-7"))!!
        assertEquals(Pdu.SEND_CONF, m.type)
        assertEquals(0x80, m.responseStatus)
        assertEquals("mid-7", m.messageId)
    }

    @Test fun garbageIsNull() {
        assertNull(Pdu.parse(byteArrayOf(0x8C.toByte(), 0x84.toByte(), 0x89.toByte(), 40, 0x80.toByte())))
    }
}
