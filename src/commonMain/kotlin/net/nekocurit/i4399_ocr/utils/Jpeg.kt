package net.nekocurit.i4399_ocr.utils

import net.nekocurit.i4399_ocr.utils.GrayImage.Companion.toGray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纯 Kotlin 的基线（baseline sequential）JPEG 解码器。
 *
 * 支持：8 位精度、1/3 个分量、4:4:4 / 4:2:2 / 4:2:0 等任意采样因子、
 * Huffman 编码、DRI/RSTn 重启标记。渐进式 JPEG 不支持（4399 验证码为基线 JPEG）。
 */
internal fun decodeJpeg(data: ByteArray): GrayImage? = try {
    JpegDecoder(data).decode()
} catch (_: Exception) {
    null
}

private val ZIGZAG = intArrayOf(
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
)

private val IDCT_BASIS: Array<DoubleArray> = Array(8) { x ->
    DoubleArray(8) { u ->
        val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
        cu * cos((2 * x + 1) * u * PI / 16.0) / 2.0
    }
}

private class JpegHuffman(counts: IntArray, val symbols: IntArray) {
    private val count = IntArray(17)
    private val firstCode = IntArray(17)
    private val firstIndex = IntArray(17)

    init {
        var total = 0
        for (i in 1..16) {
            count[i] = counts[i - 1]
            total += count[i]
        }
        var code = 0
        var index = 0
        for (len in 1..16) {
            code = (code + count[len - 1]) shl 1
            firstCode[len] = code
            firstIndex[len] = index
            index += count[len]
        }
        if (index != symbols.size) throw IllegalArgumentException("jpeg: bad huffman table")
    }

    fun decode(bits: JpegBits): Int {
        var code = 0
        for (len in 1..16) {
            code = (code shl 1) or bits.bit()
            val idx = code - firstCode[len]
            if (idx in 0 until count[len]) return symbols[firstIndex[len] + idx]
        }
        throw IllegalArgumentException("jpeg: invalid huffman code")
    }
}

private class JpegBits(private val data: ByteArray, var pos: Int) {
    var buf = 0
    var cnt = 0
    var pendingMarker = -1
    var pendingMarkerPos = -1
    var pendingMarkerCodePos = -1

    fun bit(): Int {
        if (cnt == 0) {
            buf = nextByte()
            cnt = 8
        }
        cnt--
        return (buf ushr cnt) and 1
    }

    fun bits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or bit() }
        return v
    }

    fun align() {
        cnt = 0
    }

    private fun nextByte(): Int {
        if (pos >= data.size) return 0
        val start = pos
        val b = data.u8(pos++)
        if (b != 0xFF) return b
        while (pos < data.size && data.u8(pos) == 0xFF) pos++
        if (pos >= data.size) return 0
        val b2 = data.u8(pos)
        if (b2 == 0) {
            pos++
            return 0xFF
        }
        pendingMarker = b2
        pendingMarkerPos = start
        pendingMarkerCodePos = pos
        return 0
    }

    fun consumeMarker(): Int {
        if (pendingMarker >= 0) {
            val m = pendingMarker
            pendingMarker = -1
            pos = pendingMarkerCodePos + 1
            return m
        }
        var p = pos
        while (p < data.size && data.u8(p) != 0xFF) p++
        if (p >= data.size) return -1
        while (p < data.size && data.u8(p) == 0xFF) p++
        if (p >= data.size) return -1
        val m = data.u8(p)
        pos = p + 1
        return m
    }

    fun nextMarkerPos(): Int = if (pendingMarker >= 0) pendingMarkerPos else pos
}

class JpegComponent(val id: Int, val h: Int, val v: Int, val tq: Int) {
    var dcTable = 0
    var acTable = 0
    var dcPred = 0
    var blocksW = 0
    var blocksH = 0
    var stride = 0
    var plane = IntArray(0)
}

class JpegDecoder(private val data: ByteArray) {
    private val quant = Array(4) { IntArray(64) }
    private val quantSet = BooleanArray(4)
    private val dcTables = arrayOfNulls<JpegHuffman>(4)
    private val acTables = arrayOfNulls<JpegHuffman>(4)
    val comps = ArrayList<JpegComponent>()
    var width = 0
    var height = 0
    var maxH = 1
    var maxV = 1
    var mcuW = 0
    var mcuH = 0
    var restartInterval = 0
    var adobeTransform = -1

    fun decode(): GrayImage? {
        var p = 2
        while (p + 1 < data.size) {
            while (p < data.size && data.u8(p) != 0xFF) p++
            while (p < data.size && data.u8(p) == 0xFF) p++
            if (p >= data.size) break
            val marker = data.u8(p++)
            if (marker == 0xD9) break
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
            if (p + 2 > data.size) break
            val len = data.u16be(p)
            if (len < 2) break
            val segEnd = p + len
            if (segEnd > data.size) break
            when (marker) {
                0xC0, 0xC1 -> parseSof(p + 2, segEnd)
                0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> return null
                0xC4 -> parseDht(p + 2, segEnd)
                0xDB -> parseDqt(p + 2, segEnd)
                0xDD -> if (segEnd >= p + 4) restartInterval = data.u16be(p + 2)
                0xDA -> {
                    sosDataStart = p + 2
                    parseSos(p + 2, segEnd)
                    val markerPos = decodeScan(segEnd)
                    p = if (markerPos >= 0) markerPos else data.size
                    continue
                }
                0xEE -> parseAdobe(p + 2, segEnd)
            }
            p = segEnd
        }
        if (comps.isEmpty() || width <= 0 || height <= 0) return null
        return this.toGray()
    }

    private fun parseSof(ds: Int, end: Int) {
        if (ds + 6 > end) throw IllegalArgumentException("jpeg: bad SOF")
        val precision = data.u8(ds)
        val h = data.u16be(ds + 1)
        val w = data.u16be(ds + 3)
        val n = data.u8(ds + 5)
        if (precision != 8 || w <= 0 || h <= 0 || n !in 1..4 || n == 2) {
            throw IllegalArgumentException("jpeg: unsupported SOF")
        }
        if (ds + 6 + n * 3 > end) throw IllegalArgumentException("jpeg: bad SOF")
        width = w
        height = h
        comps.clear()
        maxH = 1
        maxV = 1
        for (i in 0 until n) {
            val q = ds + 6 + i * 3
            val id = data.u8(q)
            val hv = data.u8(q + 1)
            val hh = hv shr 4
            val vv = hv and 15
            val tq = data.u8(q + 2)
            if (hh !in 1..4 || vv !in 1..4 || tq > 3) throw IllegalArgumentException("jpeg: bad component")
            comps.add(JpegComponent(id, hh, vv, tq))
            if (hh > maxH) maxH = hh
            if (vv > maxV) maxV = vv
        }
        mcuW = (width + 8 * maxH - 1) / (8 * maxH)
        mcuH = (height + 8 * maxV - 1) / (8 * maxV)
        for (c in comps) {
            c.blocksW = mcuW * c.h
            c.blocksH = mcuH * c.v
            c.stride = c.blocksW * 8
            c.plane = IntArray(c.stride * c.blocksH * 8)
        }
    }

    private fun parseDqt(ds: Int, end: Int) {
        var p = ds
        while (p < end) {
            val pqTq = data.u8(p++)
            val pq = pqTq shr 4
            val tq = pqTq and 15
            if (pq > 1 || tq > 3) throw IllegalArgumentException("jpeg: bad DQT")
            for (i in 0 until 64) {
                val v = if (pq == 0) {
                    if (p >= end) throw IllegalArgumentException("jpeg: truncated DQT")
                    data.u8(p++)
                } else {
                    if (p + 2 > end) throw IllegalArgumentException("jpeg: truncated DQT")
                    val x = data.u16be(p)
                    p += 2
                    x
                }
                quant[tq][ZIGZAG[i]] = v
            }
            quantSet[tq] = true
        }
    }

    private fun parseDht(ds: Int, end: Int) {
        var p = ds
        while (p < end) {
            val tcTh = data.u8(p++)
            val tc = tcTh shr 4
            val th = tcTh and 15
            if (tc > 1 || th > 3) throw IllegalArgumentException("jpeg: bad DHT")
            if (p + 16 > end) throw IllegalArgumentException("jpeg: truncated DHT")
            val counts = IntArray(16)
            var total = 0
            for (i in 0 until 16) {
                counts[i] = data.u8(p++)
                total += counts[i]
            }
            if (total > 256 || p + total > end) throw IllegalArgumentException("jpeg: bad DHT size")
            val symbols = IntArray(total) { data.u8(p++) }
            val table = JpegHuffman(counts, symbols)
            if (tc == 0) dcTables[th] = table else acTables[th] = table
        }
    }

    private fun parseAdobe(ds: Int, end: Int) {
        if (end - ds < 12) return
        if (data.u8(ds) != 'A'.code || data.u8(ds + 1) != 'd'.code ||
            data.u8(ds + 2) != 'o'.code || data.u8(ds + 3) != 'b'.code ||
            data.u8(ds + 4) != 'e'.code
        ) return
        adobeTransform = data.u8(ds + 11)
    }

    private fun parseSos(ds: Int, end: Int) {
        if (ds >= end) throw IllegalArgumentException("jpeg: bad SOS")
        val ns = data.u8(ds)
        if (ns !in 1..comps.size || ds + 1 + ns * 2 + 3 > end) {
            throw IllegalArgumentException("jpeg: bad SOS size")
        }
        for (i in 0 until ns) {
            val id = data.u8(ds + 1 + i * 2)
            val tables = data.u8(ds + 2 + i * 2)
            val c = comps.firstOrNull { it.id == id } ?: throw IllegalArgumentException("jpeg: unknown component")
            c.dcTable = tables shr 4
            c.acTable = tables and 15
            if (c.dcTable > 3 || c.acTable > 3) throw IllegalArgumentException("jpeg: bad table id")
        }
        val ss = data.u8(ds + 1 + ns * 2)
        val se = data.u8(ds + 2 + ns * 2)
        val ahAl = data.u8(ds + 3 + ns * 2)
        if (ss != 0 || se != 63 || ahAl != 0) throw IllegalArgumentException("jpeg: progressive scan unsupported")
    }

    private fun decodeScan(entropyStart: Int): Int {
        // parseSos 已校验头部；这里重新读取分量列表。
        val ds = sosDataStart
        val ns = data.u8(ds)
        val scanComps = ArrayList<JpegComponent>(ns)
        for (i in 0 until ns) {
            val id = data.u8(ds + 1 + i * 2)
            scanComps.add(comps.first { it.id == id })
        }
        val bits = JpegBits(data, entropyStart)
        for (c in comps) c.dcPred = 0

        var mcuCount = 0
        val coeff = IntArray(64)

        fun afterMcu(): Boolean {
            mcuCount++
            if (restartInterval > 0 && mcuCount % restartInterval == 0) {
                bits.align()
                val m = bits.consumeMarker()
                if (m !in 0xD0..0xD7) return false
                for (c in comps) c.dcPred = 0
            }
            return true
        }

        if (ns == 1 && comps.size > 1) {
            val c = scanComps[0]
            val actualW = (width * c.h + maxH - 1) / maxH
            val actualH = (height * c.v + maxV - 1) / maxV
            val bw = (actualW + 7) / 8
            val bh = (actualH + 7) / 8
            for (by in 0 until bh) {
                for (bx in 0 until bw) {
                    decodeBlock(c, bx, by, bits, coeff)
                    if (!afterMcu()) return bits.nextMarkerPos()
                }
            }
        } else {
            for (my in 0 until mcuH) {
                for (mx in 0 until mcuW) {
                    for (c in scanComps) {
                        for (v in 0 until c.v) {
                            for (h in 0 until c.h) {
                                decodeBlock(c, mx * c.h + h, my * c.v + v, bits, coeff)
                            }
                        }
                    }
                    if (!afterMcu()) return bits.nextMarkerPos()
                }
            }
        }
        return bits.nextMarkerPos()
    }

    var sosDataStart = 0

    private fun decodeBlock(c: JpegComponent, bx: Int, by: Int, bits: JpegBits, coeff: IntArray) {
        coeff.fill(0)
        val dc = dcTables[c.dcTable] ?: throw IllegalArgumentException("jpeg: missing DC table")
        val ac = acTables[c.acTable] ?: throw IllegalArgumentException("jpeg: missing AC table")
        val q = quant[c.tq]
        if (!quantSet[c.tq]) throw IllegalArgumentException("jpeg: missing quant table")

        val dcCat = dc.decode(bits)
        if (dcCat > 11) throw IllegalArgumentException("jpeg: bad DC category")
        val diff = if (dcCat == 0) 0 else extend(bits.bits(dcCat), dcCat)
        c.dcPred += diff
        coeff[0] = c.dcPred * q[0]

        var k = 1
        while (k < 64) {
            val rs = ac.decode(bits)
            val r = rs shr 4
            val s = rs and 15
            if (s == 0) {
                if (r == 15) {
                    k += 16
                    continue
                }
                break
            }
            if (s > 10) throw IllegalArgumentException("jpeg: bad AC size")
            k += r
            if (k > 63) break
            val v = extend(bits.bits(s), s)
            val z = ZIGZAG[k]
            coeff[z] = v * q[z]
            k++
        }
        idct(coeff, c, bx * 8, by * 8)
    }

    private fun idct(coeff: IntArray, c: JpegComponent, ox: Int, oy: Int) {
        val tmp = DoubleArray(64)
        for (y in 0 until 8) {
            val row = y * 8
            for (x in 0 until 8) {
                var s = 0.0
                val basis = IDCT_BASIS[x]
                for (u in 0 until 8) s += basis[u] * coeff[row + u]
                tmp[row + x] = s
            }
        }
        for (x in 0 until 8) {
            for (y in 0 until 8) {
                var s = 0.0
                val basis = IDCT_BASIS[y]
                for (v in 0 until 8) s += basis[v] * tmp[v * 8 + x]
                val value = (s.roundToInt() + 128).coerceIn(0, 255)
                c.plane[(oy + y) * c.stride + ox + x] = value
            }
        }
    }

    private fun extend(v: Int, n: Int): Int =
        if (n == 0) 0 else if (v < (1 shl (n - 1))) v - (1 shl n) + 1 else v

    fun sample(c: JpegComponent, x: Int, y: Int): Int {
        val sx = (x * c.h / maxH).coerceIn(0, c.blocksW * 8 - 1)
        val sy = (y * c.v / maxV).coerceIn(0, c.blocksH * 8 - 1)
        return c.plane[sy * c.stride + sx]
    }
}
