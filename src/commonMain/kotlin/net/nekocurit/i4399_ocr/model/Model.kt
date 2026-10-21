package net.nekocurit.i4399_ocr.model

import net.nekocurit.i4399_ocr.utils.u8

data class Model(
    val config: Config,
    val templates: List<Template>,
) {

    companion object {
        val BLOCK_MARK = byteArrayOf(
            0xF4.toByte(), 0x5E, 0xF3.toByte(), 0x49, 0xCB.toByte(), 0x62, 0xEA.toByte(), 0x99.toByte(),
        )


        internal const val TMPL_MAX = 256
        internal const val CAP_MAX = 1400
        internal const val F4_MAX = 128

        internal fun parse(data: ByteArray): Model {
            val cfg = Config.parse(data)
            val positions = IntArray(4096)
            var np = 0
            var i = 0
            while (i + 8 <= data.size && np < positions.size) {
                var matched = true
                for (k in 0 until 8) {
                    if (data[i + k] != BLOCK_MARK[k]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    positions[np++] = i
                    i += 8
                } else {
                    i++
                }
            }

            val templates = ArrayList<Template>()
            val pending = IntArray(F4_MAX)
            var pendingN = 0
            var pendingOk = false
            var k = 0
            while (k + 4 < np && templates.size < TMPL_MAX) {
                val bLabel = positions[k + 2] + 8
                val eLabel = positions[k + 3]
                val bPoints = positions[k + 1] + 8
                val ePoints = positions[k + 2]
                if (eLabel <= bLabel || eLabel - bLabel != 1) {
                    k++
                    continue
                }

                val points = ArrayList<Int>()
                var p = bPoints
                while (p + 3 <= ePoints && points.size < CAP_MAX) {
                    val v = data.u8(p) or (data.u8(p + 1) shl 8) or (data.u8(p + 2) shl 16)
                    if (v in 1..cfg.capStore) points.add(v)
                    p += 3
                }

                val f4 = if (pendingOk) pending.copyOf(pendingN) else IntArray(0)
                pendingN = 0
                pendingOk = false

                val b4 = positions[k + 4] + 8
                val e4 = if (k + 5 < np) positions[k + 5] else data.size
                if (b4 + 12 <= e4) {
                    var lim = (e4 - (b4 + 12)) / 3
                    if (lim > F4_MAX) lim = F4_MAX
                    if (lim > cfg.capF4) lim = cfg.capF4
                    for (q in 0 until lim) {
                        val pp = b4 + 12 + q * 3
                        if (pp + 3 > e4) break
                        val v = data.u8(pp) or (data.u8(pp + 1) shl 8) or (data.u8(pp + 2) shl 16)
                        if (v in 1..cfg.capStore) pending[pendingN++] = v
                    }
                    if (pendingN > 0) pendingOk = true
                }

                val label = data.u8(bLabel).toChar()
                templates.add(Template(label, points.toIntArray(), f4))
                k += 5
            }

            val limited = if (templates.size > 148) templates.subList(0, 148) else templates
            require(limited.isNotEmpty())
            return Model(cfg, limited)
        }

    }
    data class Config(
        val w: Int,
        val h: Int,
        val nms: Int,
        val thr: Int,
        val cap: Int,
        val capStore: Int,
        val capF0: Int,
        val capF1: Int,
        val capF4: Int,
    ) {
        companion object {
            val DEFAULT = Config(
                w = 23,
                h = 27,
                nms = 9,
                thr = 200,
                cap = 23 * 27,
                capStore = 23 * 28,
                capF0 = 442,
                capF1 = 132,
                capF4 = 44,
            )

            val THRESHOLD_PATTERN = byteArrayOf(
                0xD6.toByte(), 0xB8.toByte(), 0xB6.toByte(), 0xA8.toByte(),
                0xE3.toByte(), 0xD0.toByte(), 0xD6.toByte(), 0xB5.toByte(), 0x40,
            )

            private fun tailParams(data: ByteArray, maxValues: Int): List<Int> {
                val start = if (data.size > 512) data.size - 512 else 0
                val values = ArrayList<Int>()
                var i = start
                while (i + 8 <= data.size) {
                    var matched = true
                    for (k in 0 until 8) {
                        if (data[i + k] != BLOCK_MARK[k]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) {
                        var p = i + 8
                        if (p < data.size && data.u8(p) in '0'.code..'9'.code) {
                            var x = 0L
                            while (p < data.size && data.u8(p) in '0'.code..'9'.code && x < 100_000_000L) {
                                x = x * 10 + (data.u8(p) - '0'.code)
                                p++
                            }
                            values.add(x.toInt())
                            i = p
                            continue
                        }
                    }
                    i++
                }
                return if (values.size > maxValues) values.subList(values.size - maxValues, values.size).toList() else values
            }

            private fun tailThreshold(data: ByteArray): Int {
                val start = if (data.size > 512) data.size - 512 else 0
                var i = start
                while (i + THRESHOLD_PATTERN.size <= data.size) {
                    var matched = true
                    for (k in THRESHOLD_PATTERN.indices) {
                        if (data[i + k] != THRESHOLD_PATTERN[k]) {
                            matched = false
                            break
                        }
                    }
                    if (matched) {
                        var p = i + THRESHOLD_PATTERN.size
                        var x = 0
                        while (p < data.size && data.u8(p) in '0'.code..'9'.code && x < 1000) {
                            x = x * 10 + (data.u8(p) - '0'.code)
                            p++
                        }
                        if (x in 1..255) return x
                    }
                    i++
                }
                return 200
            }

            fun parse(data: ByteArray): Config {
                val p = tailParams(data, 16)
                var c = DEFAULT.copy()
                if (p.size >= 7) {
                    val w = p[0]
                    val hh = p[1]
                    val nms = p[2]
                    val f4 = p[4]
                    val f0 = p[5]
                    val f1 = p[6]
                    if (w in 8..96 && hh in 8..96) {
                        c = c.copy(w = w, h = if (hh > 1) hh - 1 else hh)
                    }
                    if (nms in 1..32) c = c.copy(nms = nms)
                    if (f4 in 1..1024) c = c.copy(capF4 = f4)
                    if (f0 in 1..4096) c = c.copy(capF0 = f0)
                    if (f1 in 1..2048) c = c.copy(capF1 = f1)
                }
                val thr = tailThreshold(data)
                var cap = c.w * c.h
                var capStore = c.w * (c.h + 1)
                if (capStore > CAP_MAX) capStore = CAP_MAX
                if (cap > capStore) cap = capStore
                return c.copy(thr = thr, cap = cap, capStore = capStore)
            }
        }
    }

    class Template(
        val label: Char,
        val points: IntArray,
        val f4: IntArray,
    )
}