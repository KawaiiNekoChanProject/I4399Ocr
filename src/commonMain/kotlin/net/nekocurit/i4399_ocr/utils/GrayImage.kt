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
            val output = ByteArray(width * height)
            if (components.size == 1) {
                val component = components[0]
                for (row in 0 until height) {
                    for (column in 0 until width) {
                        output[row * width + column] = sample(component, column, row).toByte()
                    }
                }
                return GrayImage(width, height, output)
            }
            if (components.size != 3) {
                throw IllegalArgumentException("jpeg: unsupported component count")
            }
            val component0 = components[0]
            val component1 = components[1]
            val component2 = components[2]
            if (adobeTransform == 0) {
                for (row in 0 until height) {
                    for (column in 0 until width) {
                        output[row * width + column] = rgbToGray(
                            sample(component0, column, row),
                            sample(component1, column, row),
                            sample(component2, column, row),
                        ).toByte()
                    }
                }
                return GrayImage(width, height, output)
            }
            for (row in 0 until height) {
                for (column in 0 until width) {
                    val luma = sample(component0, column, row).toDouble()
                    val chromaBlue = sample(component1, column, row) - 128.0
                    val chromaRed = sample(component2, column, row) - 128.0
                    val red = (luma + 1.402 * chromaRed).roundToInt().coerceIn(0, 255)
                    val green = (luma - 0.344136 * chromaBlue - 0.714136 * chromaRed)
                        .roundToInt().coerceIn(0, 255)
                    val blue = (luma + 1.772 * chromaBlue).roundToInt().coerceIn(0, 255)
                    output[row * width + column] = rgbToGray(red, green, blue).toByte()
                }
            }
            return GrayImage(width, height, output)
        }

        /** BT.601 灰度转换，常量与 C 版 Windows GDI+ 路径一致。 */
        internal fun rgbToGray(red: Int, green: Int, blue: Int): Int =
            (red * 299 + green * 587 + blue * 114) / 1000
    }
}
