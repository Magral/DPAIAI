package io.github.magral.dpipilot.tv

/** Full HTTP entity and decodable image; a JPEG prefix or a small error placeholder never passes. */
object PreviewBody {
    data class Size(val width: Int,val height: Int)
    fun accepts(response: HttpCodec.Response,decode: (ByteArray)->Size?): Boolean {
        val body = response.body
        if (response.status != 200 || response.capped || response.headers.containsKey("content-range") || body.size !in 1024..PreviewQuality.LIMIT) return false
        val type = response.headers["content-type"].orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        fun b(index: Int) = body[index].toInt() and 255
        val framed = when (type) {
            "image/jpeg" -> b(0) == 255 && b(1) == 216 && b(2) == 255 && b(body.lastIndex-1) == 255 && b(body.lastIndex) == 217
            "image/webp" -> {
                val length = (0..3).sumOf { b(4+it).toLong() shl (8*it) }
                body.copyOfRange(0,4).toString(Charsets.US_ASCII) == "RIFF" &&
                    body.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WEBP" && length+8L == body.size.toLong()
            }
            else -> false
        }
        if (!framed) return false
        val size = decode(body) ?: return false
        return size.width in 320..1920 && size.height in 180..1080
    }
}
