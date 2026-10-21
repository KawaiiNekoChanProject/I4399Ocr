package net.nekocurit.i4399_ocr.utils

import net.nekocurit.i4399_ocr.model.Model
import net.nekocurit.i4399_ocr.model.Model.Companion.CAP_MAX
import kotlin.math.abs

internal const val SRC_W = 160
internal const val SRC_H = 48
internal const val CONTENT_H = 31
private const val OCR_THR = 0.50
private const val MAX_CHARS = 4

private val DX_TAB = intArrayOf(
    2, 2, 5, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    4, 4, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2,
    0,
)

private class Candidate(val score: Double, val label: Char, val i: Int, val j: Int)

/** 对灰度图执行一次识别；返回识别出的字符（0..4 个）。 */
internal fun recognize(model: Model, gray: GrayImage): String {
    val cfg = model.config
    val img = ByteArray(SRC_W * SRC_H)
    val srcW = (gray.width + 15).coerceAtMost(SRC_W)
    img.fill(0xFF.toByte())

    // 二值化 + 垂直翻转 + 逐行水平剪切。
    for (y in 0 until CONTENT_H) {
        val sy = 31 - y
        if (sy < 0 || sy >= gray.height) continue
        val dx = DX_TAB[y]
        for (x in 0 until srcW) {
            val sx = x - dx
            if (sx < 0 || sx >= gray.width) continue
            if (gray.pixels.u8(sy * gray.width + sx) < cfg.thr) img[y * SRC_W + x] = 0
        }
    }

    val gw = cfg.w
    val gh = cfg.h
    val win = ByteArray(CAP_MAX)
    val candidates = ArrayList<Candidate>(SRC_W * SRC_H)
    val radSame = cfg.nms
    val radDiff = if (cfg.nms > 2) cfg.nms - 2 else cfg.nms

    for (j in 0..20) {
        if (j + gh > SRC_H) break
        for (i in 0..(srcW - gw)) {
            var bg = 0
            for (y in 0 until gh) {
                val row = (j + y) * SRC_W + i
                val wrow = y * gw
                for (x in 0 until gw) {
                    val v = img[row + x]
                    win[wrow + x] = v
                    if (v == 0xFF.toByte()) bg++
                }
            }
            if (bg < 200) continue

            var best = 2.0
            var bestLabel = '\u0000'
            for (t in model.templates) {
                val n1 = t.points.size
                if (n1 < 40) continue
                var m1 = 0
                for (q in t.points.indices) {
                    val pt = t.points[q]
                    if (pt <= cfg.cap && win[pt - 1] == 0xFF.toByte()) m1++
                }
                if (m1 > (0.545 * n1 + 0.5).toInt()) continue
                var m4 = 0
                if (t.f4.isNotEmpty()) {
                    for (q in t.f4.indices) {
                        val pt = t.f4[q]
                        if (pt <= cfg.cap && win[pt - 1] == 0xFF.toByte()) m4++
                    }
                }
                val total = n1 + t.f4.size
                val r = (m1 + m4).toDouble() / total.toDouble() - 0.1 * total / 200.0
                if (r < best) {
                    best = r
                    bestLabel = t.label
                }
            }
            if (bestLabel != '\u0000' && best < OCR_THR) {
                candidates.add(Candidate(best, bestLabel, i, j))
            }
        }
    }

    candidates.sortBy { it.score }

    val selected = ArrayList<Candidate>(MAX_CHARS)
    for (c in candidates) {
        if (selected.size >= MAX_CHARS) break
        var ok = true
        for (s in selected) {
            val rad = if (c.label == s.label) radSame else radDiff
            if (abs(c.i - s.i) < rad && abs(c.j - s.j) < rad) {
                ok = false
                break
            }
        }
        if (ok) selected.add(c)
    }

    // 按窗口横坐标从左到右输出（插入排序，保持原实现语义）。
    for (i in 1 until selected.size) {
        val t = selected[i]
        var j = i
        while (j > 0 && selected[j - 1].i > t.i) {
            selected[j] = selected[j - 1]
            j--
        }
        selected[j] = t
    }
    return buildString(selected.size) { for (c in selected) append(c.label) }
}
