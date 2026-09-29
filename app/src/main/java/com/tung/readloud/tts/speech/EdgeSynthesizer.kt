package com.tung.readloud.tts.speech

import com.tung.readloud.data.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Microsoft Edge "Read aloud" neural voices over the same WebSocket protocol as the edge-tts project.
 * Unofficial: Microsoft can change or block it at any time.
 */
class EdgeSynthesizer(private val client: OkHttpClient) : Synthesizer {

    class HttpException(val code: Int, val serverDate: String?) : IOException("Edge TTS từ chối kết nối (HTTP $code)")

    override suspend fun synthesize(text: String, voice: String): ByteArray {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            val started = System.currentTimeMillis()
            try {
                return withTimeout(TIMEOUT_MS) { requestOnce(text, voice) }.also {
                    if (attempt > 0) EventLog.log("Edge: ok on attempt ${attempt + 1} after ${System.currentTimeMillis() - started} ms")
                }
            } catch (e: TimeoutCancellationException) {
                last = IOException("Edge TTS quá thời gian chờ")
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                last = e
                e.serverDate?.let(::syncClock)
            } catch (e: IOException) {
                last = e
            }
            EventLog.log("Edge: attempt ${attempt + 1} failed after ${System.currentTimeMillis() - started} ms: ${last?.message}")
            // The server resets some connections under parallel load; back off 1 s, 2 s, 4 s.
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS shl attempt)
        }
        throw last ?: IOException("Edge TTS lỗi")
    }

    /**
     * One synthesis over a fresh WebSocket. A slow server that keeps streaming audio is waited for, up to
     * [TIMEOUT_MS] in total; one that goes quiet for [IDLE_TIMEOUT_MS] is dropped so the retry can start.
     */
    private suspend fun requestOnce(text: String, voice: String): ByteArray = coroutineScope {
        val result = CompletableDeferred<ByteArray>()
        val audio = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        val lastActivity = AtomicLong(started)
        val firstAudioAt = AtomicLong(0L)

        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val stamp = timestamp()
        val configMessage = "X-Timestamp:$stamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            CONFIG_JSON + "\r\n"
        val ssmlMessage = "X-RequestId:$connectionId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${stamp}Z\r\nPath:ssml\r\n\r\n" +
            ssml(text, voice)

        val request = Request.Builder()
            .url("$WSS_URL?TrustedClientToken=$TRUSTED_TOKEN&ConnectionId=$connectionId&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$GEC_VERSION")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)};")
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastActivity.set(System.currentTimeMillis())
                webSocket.send(configMessage)
                webSocket.send(ssmlMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivity.set(System.currentTimeMillis())
                if (!text.contains("Path:turn.end")) return
                webSocket.close(1000, null)
                val bytes = synchronized(audio) { audio.toByteArray() }
                if (bytes.isNotEmpty()) result.complete(bytes) else result.completeExceptionally(IOException("Edge TTS không trả về audio"))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val now = System.currentTimeMillis()
                lastActivity.set(now)
                if (bytes.size < 2) return
                val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                val start = 2 + headerLength
                if (start >= bytes.size) return
                val header = bytes.substring(2, start).utf8()
                if (header.contains("Path:audio")) {
                    firstAudioAt.compareAndSet(0L, now)
                    synchronized(audio) { audio.write(bytes.substring(start).toByteArray()) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = if (response != null) {
                    HttpException(response.code, response.header("Date"))
                } else {
                    IOException(t.message ?: "Edge TTS lỗi kết nối", t)
                }
                result.completeExceptionally(error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                result.completeExceptionally(IOException("Edge TTS đóng kết nối ($code)"))
            }
        })
        val watchdog = launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    result.completeExceptionally(IOException("Edge TTS ngừng gửi dữ liệu"))
                }
            }
        }
        try {
            result.await().also { bytes ->
                val total = System.currentTimeMillis() - started
                if (total > SLOW_REQUEST_MS) {
                    val first = firstAudioAt.get().takeIf { it > 0 }?.let { it - started }
                    EventLog.log("Edge: slow request, ${total} ms total, first audio after $first ms, ${bytes.size / 1024} KB")
                }
            }
        } finally {
            watchdog.cancel()
            socket.cancel()
        }
    }

    private fun ssml(text: String, voice: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${SsmlText.escape(text)}</prosody></voice></speak>"

    companion object {
        private const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR = "143"
        private const val GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
        private const val CONFIG_JSON = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        private const val WINDOWS_EPOCH_OFFSET = 11_644_473_600L
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 1_000L
        /** A 300-character piece normally takes a few seconds; the total cap only guards against a trickle. */
        private const val TIMEOUT_MS = 60_000L

        /** No bytes for this long means the connection is dead, even if the socket is still open. */
        private const val IDLE_TIMEOUT_MS = 12_000L
        private const val IDLE_CHECK_MS = 1_000L
        private const val SLOW_REQUEST_MS = 8_000L

        /** Seconds to add to the local clock, learned from the server's Date header after a rejection. */
        @Volatile
        private var clockSkewSeconds = 0L

        internal fun secMsGec(nowSeconds: Long = System.currentTimeMillis() / 1000 + clockSkewSeconds): String {
            var ticks = nowSeconds + WINDOWS_EPOCH_OFFSET
            ticks -= ticks % 300
            val value = ticks * 10_000_000L
            val digest = MessageDigest.getInstance("SHA-256").digest("$value$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it) }
        }

        private fun timestamp(): String =
            SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())

        private fun syncClock(dateHeader: String) {
            val server = runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(dateHeader)?.time
            }.getOrNull() ?: return
            clockSkewSeconds = server / 1000 - System.currentTimeMillis() / 1000
        }
    }
}
