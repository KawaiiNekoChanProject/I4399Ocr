package net.nekocurit.i4399_ocr

import net.nekocurit.i4399_ocr.utils.GrayImage
import net.nekocurit.i4399_ocr.utils.decodeJpeg
import net.nekocurit.i4399_ocr.utils.recognize

object I4399Ocr {

    /**
     * 识别图片字节（JPG/PNG/BMP/PGM）
     */
    fun ocr(image: ByteArray): String? {
        val gray = decodeJpeg(image) ?: return null
        return ocr(gray)
    }

    /**
     * 识别已解码的 8 位灰度图。
     */
    fun ocr(gray: GrayImage): String {
        val first = recognize(Models.model1, gray)
        if (first.length == 4) return first
        return recognize(Models.model2, gray)
    }

}