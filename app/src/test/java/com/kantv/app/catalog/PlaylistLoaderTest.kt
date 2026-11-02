package com.kantv.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.ServerSocket
import java.net.SocketTimeoutException

class PlaylistLoaderTest {
    @Test fun readsAnHttpPlaylist() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val thread = Thread {
                server.accept().use { socket ->
                    readUntilHeaders(socket)
                    val body = "#EXTM3U\n"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
            }
            thread.start()
            try {
                val text = PlaylistLoader().fromUrl("http://127.0.0.1:${server.localPort}/list.m3u")
                assertEquals("#EXTM3U\n", text)
            } finally {
                thread.join(5_000)
            }
        }
    }

    @Test fun controlCharactersAndLocalSchemesNeverOpenAConnection() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 300
            val hostile = "http://127.0.0.1:${server.localPort}/list.m3u?x=1\nX-Injected: yes"
            assertThrows(MalformedURLException::class.java) { PlaylistLoader().fromUrl(hostile) }
            assertThrows(MalformedURLException::class.java) { PlaylistLoader().fromUrl("file:///etc/passwd") }
            assertThrows(MalformedURLException::class.java) { PlaylistLoader().fromUrl("javascript:alert(1)") }
            assertThrows(SocketTimeoutException::class.java) { server.accept() }
        }
        assertEquals(
            PlaylistRefreshStatus.URL_INVALID,
            PlaylistRefreshStatus.from(MalformedURLException("invalid url")),
        )
    }

    private fun readUntilHeaders(socket: java.net.Socket) {
        val input = socket.getInputStream()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            if (value < 0) break
            val expected = if (matched % 2 == 0) '\r'.code else '\n'.code
            matched = if (value == expected) matched + 1 else 0
        }
    }
}
