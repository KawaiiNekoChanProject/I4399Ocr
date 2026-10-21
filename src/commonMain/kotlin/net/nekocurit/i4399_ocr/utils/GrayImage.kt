package net.nekocurit.i4399_ocr.utils

import kotlin.math.roundToInt

/** 8 位灰度图。pixels 按行优先存放，0 = 黑，255 = 白。 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width >= 0 && height >= 0 && pixels.size == width * height) {
            "invalid gray image $width x $height (${pixels.size} bytes)"
        }
    }

    companion object {
        fun JpegDecoder.toGray(): GrayImage {
            val out = ByteArray(width * height)
            if (comps.size == 1) {
                val c = comps[0]
                for (y in 0 until height) for (x in 0 until width) {
                    out[y * width + x] = sample(c, x, y).toByte()
                }
                return GrayImage(width, height, out)
            }
            if (comps.size != 3) throw IllegalArgumentException("jpeg: unsupported component count")
            val c0 = comps[0]
            val c1 = comps[1]
            val c2 = comps[2]
            if (adobeTransform == 0) {
                for (y in 0 until height) for (x in 0 until width) {
                    out[y * width + x] = rgbToGray(sample(c0, x, y), sample(c1, x, y), sample(c2, x, y)).toByte()
                }
                return GrayImage(width, height, out)
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yy = sample(c0, x, y).toDouble()
                    val cb = sample(c1, x, y) - 128.0
                    val cr = sample(c2, x, y) - 128.0
                    val r = (yy + 1.402 * cr).roundToInt().coerceIn(0, 255)
                    val g = (yy - 0.344136 * cb - 0.714136 * cr).roundToInt().coerceIn(0, 255)
                    val b = (yy + 1.772 * cb).roundToInt().coerceIn(0, 255)
                    out[y * width + x] = rgbToGray(r, g, b).toByte()
                }
            }
            return GrayImage(width, height, out)
        }

        /** BT.601 灰度转换，常量与 C 版 Windows GDI+ 路径一致。 */
        internal fun rgbToGray(r: Int, g: Int, b: Int): Int =
            (r * 299 + g * 587 + b * 114) / 1000
    }
}
