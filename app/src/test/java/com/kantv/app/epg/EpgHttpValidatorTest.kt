package com.kantv.app.epg

import com.kantv.app.util.cacheValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class EpgHttpValidatorTest {
    @Test fun cacheValidatorDropsBlankAndControlCharacters() {
        assertNull(cacheValidator(null))
        assertNull(cacheValidator("  "))
        assertNull(cacheValidator("bad\r\nX-Injected: 1"))
        assertNull(cacheValidator("bad\u0000"))
        assertEquals("\"good\"", cacheValidator("  \"good\"  "))
    }

    @Test fun poisonedValidatorsAreNotSentAndGoodOnesStillAre() {
        val requests = mutableListOf<String>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val thread = Thread {
                repeat(2) {
                    server.accept().use { socket ->
                        requests += readHeaders(socket)
                        writeXml(socket)
                    }
                }
            }
            thread.start()
            try {
                val client = UrlConnectionEpgHttpClient()
                val url = "http://127.0.0.1:${server.localPort}/guide.xml"
                val poisoned = client.fetch(
                    url,
                    "evil\r\nX-Injected: yes",
                    "Wed, 21 Oct 2015 07:28:00 GMT\nX: 1",
                )
                val clean = client.fetch(url, "\"good\"", "Wed, 21 Oct 2015 07:28:00 GMT")
                assertTrue(poisoned is EpgHttpResult.Modified)
                assertEquals("\"good\"", (poisoned as EpgHttpResult.Modified).etag)
                assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", poisoned.lastModified)
                assertTrue(clean is EpgHttpResult.Modified)
                thread.join(5_000)
            } finally {
                thread.join(5_000)
            }
        }
        assertEquals(2, requests.size)
        assertFalse(requests[0].contains("If-None-Match"))
        assertFalse(requests[0].contains("If-Modified-Since"))
        assertFalse(requests[0].contains("X-Injected"))
        assertTrue(requests[1].contains("If-None-Match: \"good\""))
        assertTrue(requests[1].contains("If-Modified-Since: Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ArrayList<Byte>(512)
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) break
            bytes += value.toByte()
            val expected = if (matched % 2 == 0) '\r'.code else '\n'.code
            matched = if (value == expected) matched + 1 else 0
            if (bytes.size > 16 * 1024) break
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun writeXml(socket: Socket) {
        val body = "<tv/>"
        val response = "HTTP/1.1 200 OK\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "ETag: \"good\"\r\n" +
            "Last-Modified: Wed, 21 Oct 2015 07:28:00 GMT\r\n" +
            "Connection: close\r\n\r\n" +
            body
        socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }
}
