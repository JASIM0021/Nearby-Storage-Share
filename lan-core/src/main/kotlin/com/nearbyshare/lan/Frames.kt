package com.nearbyshare.lan

import com.nearbyshare.core.network.protocol.Message
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * One message on the encrypted stream. Control messages are the same JSON messages used over Nearby; file bytes travel as
 * DATA frames tagged with a stream id so control messages can interleave between chunks.
 */
sealed interface Frame {
    /** [message] is null when the payload was not a valid message. */
    class Control(val message: Message?) : Frame
    class Data(val streamId: Int, val bytes: ByteArray) : Frame
    class End(val streamId: Int) : Frame
    class Abort(val streamId: Int, val reason: String) : Frame
}

/** Frame layout: type (1 byte) | length (4 bytes, big endian) | payload. DATA/END/ABORT payloads start with the stream id. */
class FrameIO(input: InputStream, output: OutputStream) {
    private val inp = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val out = BufferedOutputStream(output, 64 * 1024)
    private val writeLock = Any()

    /** Returns null at a clean end of stream. Throws on a malformed or oversized frame. */
    fun readFrame(): Frame? {
        val type = inp.read()
        if (type < 0) return null
        val length = inp.readInt()
        if (length < 0 || length > LanConstants.MAX_FRAME_BYTES) throw IOException("frame too large: $length")
        val payload = ByteArray(length)
        inp.readFully(payload)
        return when (type) {
            TYPE_CONTROL -> Frame.Control(Message.decode(payload))
            TYPE_DATA -> {
                if (length < 4) throw IOException("short data frame")
                val buf = ByteBuffer.wrap(payload)
                val id = buf.int
                Frame.Data(id, payload.copyOfRange(4, length))
            }
            TYPE_END -> {
                if (length < 4) throw IOException("short end frame")
                Frame.End(ByteBuffer.wrap(payload).int)
            }
            TYPE_ABORT -> {
                if (length < 4) throw IOException("short abort frame")
                val buf = ByteBuffer.wrap(payload)
                Frame.Abort(buf.int, String(payload, 4, length - 4, Charsets.UTF_8))
            }
            else -> throw IOException("unknown frame type $type")
        }
    }

    fun writeControl(message: Message) {
        val bytes = message.encode()
        write(TYPE_CONTROL, null, bytes, 0, bytes.size)
    }

    fun writeData(streamId: Int, buf: ByteArray, off: Int, len: Int) = write(TYPE_DATA, streamId, buf, off, len)

    fun writeEnd(streamId: Int) = write(TYPE_END, streamId, EMPTY, 0, 0)

    fun writeAbort(streamId: Int, reason: String) {
        val r = reason.toByteArray(Charsets.UTF_8).let { if (it.size > 200) it.copyOf(200) else it }
        write(TYPE_ABORT, streamId, r, 0, r.size)
    }

    private fun write(type: Int, streamId: Int?, buf: ByteArray, off: Int, len: Int) {
        val idBytes = if (streamId != null) 4 else 0
        val total = idBytes + len
        require(total <= LanConstants.MAX_FRAME_BYTES) { "frame too large: $total" }
        // One frame at a time: concurrent senders (control replies, file data) never interleave inside a frame.
        synchronized(writeLock) {
            out.write(type)
            out.write(ByteBuffer.allocate(4).putInt(total).array())
            if (streamId != null) out.write(ByteBuffer.allocate(4).putInt(streamId).array())
            out.write(buf, off, len)
            out.flush()
        }
    }

    companion object {
        const val TYPE_CONTROL = 1
        const val TYPE_DATA = 2
        const val TYPE_END = 3
        const val TYPE_ABORT = 4
        private val EMPTY = ByteArray(0)
    }
}
