package net.nekocurit.i4399_ocr

import net.nekocurit.i4399_ocr.utils.GrayImage
import net.nekocurit.i4399_ocr.utils.decodeJpeg
import net.nekocurit.i4399_ocr.utils.recognize

object I4399Ocr {

    /**
     * 识别图片字节
     *
     * @param image 图片字节, 仅支持 `jpeg`
     */
    fun ocr(image: ByteArray): String = ocr(decodeJpeg(image))

    /**
     * 识别已解码的 8 位灰度图
     *
     * @param gray 灰度图数据
     */
    fun ocr(gray: GrayImage) = recognize(Models.model1, gray).takeIf { it.length == 4 }
        ?: recognize(Models.model2, gray).takeIf { it.length == 4 }
        ?: error("识别失败")

}