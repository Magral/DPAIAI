package io.github.dovecoteescapee.byedpi.utility

import java.io.IOException

/** Bounded transport framing. Encrypted MTProto account payloads stay opaque. */
internal class MtTransportPackets(private val tag: Int) {
    private val abridged = tag == 0xefefefef.toInt()
    private val prefix = ByteArray(4)
    private var prefixSize = if (abridged) 1 else 4
    private var prefixRead = 0
    private var packet: ByteArray? = null
    private var packetRead = 0
    init { require(abridged || tag == 0xeeeeeeee.toInt() || tag == 0xdddddddd.toInt()) }
    val hasPending: Boolean get() = prefixRead > 0 || packet != null
    fun feed(chunk: ByteArray): List<ByteArray> {
        val complete = ArrayList<ByteArray>()
        var offset = 0
        while (offset < chunk.size) {
            if (packet == null) {
                val count = minOf(prefixSize-prefixRead,chunk.size-offset)
                chunk.copyInto(prefix,prefixRead,offset,offset+count)
                prefixRead += count; offset += count
                if (prefixRead < prefixSize) continue
                if (abridged && prefixSize == 1 && (prefix[0].toInt() and 0x7f) == 127) {
                    prefixSize = 4
                    continue
                }
                val body: Long = if (abridged) {
                    if (prefixSize == 1) (prefix[0].toInt() and 0x7f).toLong()*4
                    else ((prefix[1].toLong() and 255) or ((prefix[2].toLong() and 255) shl 8) or ((prefix[3].toLong() and 255) shl 16))*4
                } else {
                    ((prefix[0].toLong() and 255) or ((prefix[1].toLong() and 255) shl 8) or
                        ((prefix[2].toLong() and 255) shl 16) or ((prefix[3].toLong() and 127) shl 24))
                }
                if (body !in 1L..MAX_BODY.toLong()) throw IOException("Invalid MTProto transport packet length")
                packet = ByteArray(prefixSize+body.toInt()).also { prefix.copyInto(it,0,0,prefixSize) }
                packetRead = prefixSize
            }
            val buffer = packet ?: continue
            val count = minOf(buffer.size-packetRead,chunk.size-offset)
            chunk.copyInto(buffer,packetRead,offset,offset+count)
            packetRead += count; offset += count
            if (packetRead == buffer.size) {
                complete.add(buffer)
                packet = null; packetRead = 0; prefixRead = 0; prefixSize = if (abridged) 1 else 4
            }
        }
        return complete
    }
    companion object { const val MAX_BODY = 8*1024*1024 }
}
