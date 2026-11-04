package it.spagnuolo.docverso.engine

/**
 * PNG minimi scritti in Kotlin puro (niente librerie di piattaforma): servono per i riquadri
 * colorati di un pixel e per i test. Dati compressi in blocchi zlib "memorizzati" (senza compressione).
 */
object Png {

    private val crcTable = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    fun crc32(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = -1
        for (i in from until to) c = crcTable[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    /** Flusso zlib con blocchi non compressi (massimo 65535 byte ciascuno). */
    fun zlibStored(raw: ByteArray): ByteArray {
        val out = Bytes()
        out.add(0x78); out.add(0x01)
        var pos = 0
        do {
            val n = minOf(65535, raw.size - pos)
            val last = pos + n >= raw.size
            out.add(if (last) 1 else 0)
            out.add(n and 0xFF); out.add(n ushr 8)
            out.add(n.inv() and 0xFF); out.add((n.inv() ushr 8) and 0xFF)
            out.addAll(raw, pos, pos + n)
            pos += n
        } while (!last)
        var a = 1; var b = 0
        for (x in raw) { a = (a + (x.toInt() and 0xFF)) % 65521; b = (b + a) % 65521 }
        out.int32((b shl 16) or a)
        return out.toByteArray()
    }

    /** PNG da righe di pixel già pronte. [colorType] 0 = grigi, 2 = RGB. */
    fun encode(w: Int, h: Int, colorType: Int, pixels: ByteArray): ByteArray {
        val bpp = if (colorType == 2) 3 else 1
        val raw = ByteArray(h * (1 + w * bpp))
        var k = 0
        for (y in 0 until h) {
            raw[k++] = 0
            pixels.copyInto(raw, k, y * w * bpp, (y + 1) * w * bpp)
            k += w * bpp
        }
        val out = Bytes()
        out.addAll(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = Bytes().apply { int32(w); int32(h); add(8); add(colorType); add(0); add(0); add(0) }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", zlibStored(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun solid(color: Int): ByteArray =
        encode(1, 1, 2, byteArrayOf((color shr 16).toByte(), (color shr 8).toByte(), color.toByte()))

    private fun chunk(out: Bytes, type: String, data: ByteArray) {
        out.int32(data.size)
        val td = type.encodeToByteArray() + data
        out.addAll(td)
        out.int32(crc32(td))
    }

    /** Buffer di byte crescente (al posto di ByteArrayOutputStream). */
    class Bytes {
        private var buf = ByteArray(256)
        var size = 0; private set
        private fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
        fun add(b: Int) { ensure(1); buf[size++] = b.toByte() }
        fun addAll(b: ByteArray, from: Int = 0, to: Int = b.size) { ensure(to - from); b.copyInto(buf, size, from, to); size += to - from }
        fun int32(v: Int) { add(v ushr 24); add(v ushr 16); add(v ushr 8); add(v) }
        fun toByteArray() = buf.copyOf(size)
    }
}
