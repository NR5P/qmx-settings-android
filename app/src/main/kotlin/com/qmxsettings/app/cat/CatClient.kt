package com.qmxsettings.app.cat

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A CAT reply: either a frame (without its trailing ';'), the radio's "?" error, or no answer. */
sealed class CatReply {
    data class Ok(val frame: String) : CatReply()
    data object Error : CatReply()
    data object Timeout : CatReply()
}

data class CatLogLine(val outgoing: Boolean, val text: String, val timeMs: Long = System.currentTimeMillis())

/**
 * Semicolon-framed request/response client for QRP Labs (Kenwood-style) CAT.
 *
 * - Commands are sent one at a time (a mutex serialises them).
 * - Never sends CR or LF: QMX treats a carriage return as "switch this port to terminal mode".
 * - Unsolicited frames (e.g. IF; when Auto Info is on) that don't match the expected reply
 *   prefix are dropped while waiting.
 */
class CatClient(private val transport: ByteTransport) {
    private val frames = Channel<String>(Channel.UNLIMITED)
    private val buf = StringBuilder()
    private val lock = Mutex()
    private val _log = MutableSharedFlow<CatLogLine>(extraBufferCapacity = 256)
    val log: SharedFlow<CatLogLine> = _log

    var timeoutMs: Long = 1200

    init {
        transport.onData = { bytes -> feed(bytes) }
    }

    private fun feed(bytes: ByteArray) {
        synchronized(buf) {
            for (b in bytes) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c == '\r' || c == '\n') continue
                if (c == ';') {
                    val f = buf.toString()
                    buf.setLength(0)
                    _log.tryEmit(CatLogLine(false, "$f;"))
                    frames.trySend(f)
                } else {
                    buf.append(c)
                    if (buf.length > 4096) buf.setLength(0) // runaway junk (e.g. terminal-mode output)
                }
            }
        }
    }

    private fun drain() {
        while (frames.tryReceive().isSuccess) { /* discard stale frames */ }
    }

    private fun sanitize(cmd: String): String {
        require(cmd.none { it == '\r' || it == '\n' }) { "CAT commands must not contain CR/LF" }
        return if (cmd.endsWith(";")) cmd else "$cmd;"
    }

    private fun send(cmd: String) {
        _log.tryEmit(CatLogLine(true, cmd))
        transport.write(cmd.toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * Sends [command] and waits for a reply starting with [expectPrefix] (default: the
     * command's first two letters), or the "?" error.
     */
    suspend fun query(command: String, expectPrefix: String? = null, timeout: Long = timeoutMs): CatReply =
        lock.withLock {
            val cmd = sanitize(command)
            val prefix = expectPrefix ?: cmd.take(2).uppercase()
            drain()
            send(cmd)
            awaitReply(prefix, timeout)
        }

    private suspend fun awaitReply(prefix: String, timeout: Long): CatReply =
        withTimeoutOrNull(timeout) {
            while (true) {
                val f = frames.receive()
                if (f == "?") return@withTimeoutOrNull CatReply.Error
                if (f.uppercase().startsWith(prefix)) return@withTimeoutOrNull CatReply.Ok(f)
            }
            @Suppress("UNREACHABLE_CODE") CatReply.Timeout
        } ?: CatReply.Timeout

    /**
     * Sends a Set command that may or may not produce a reply. Waits [settleMs] and reports
     * a "?" error if the radio sent one; any other frames are discarded.
     */
    suspend fun command(command: String, settleMs: Long = 150): CatReply =
        lock.withLock {
            val cmd = sanitize(command)
            drain()
            send(cmd)
            delay(settleMs)
            var result: CatReply = CatReply.Ok("")
            while (true) {
                val f = frames.tryReceive().getOrNull() ?: break
                if (f == "?") result = CatReply.Error
            }
            result
        }

    /** Raw console send: writes text exactly (semicolon-terminated) and returns. */
    suspend fun raw(command: String) = lock.withLock { send(sanitize(command)) }
}
