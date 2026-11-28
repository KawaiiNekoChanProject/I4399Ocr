@file:OptIn(ExperimentalUnsignedTypes::class)
package net.nekocurit.i4399_ocr.utils

/** Little-endian reader over an in-memory model blob. */
class SimpleReader(
    val data: ByteArray,
    var position: Int,
) {
    fun readUByte() = data[position++].toUByte()

    fun readUShort(): UShort {
        val value = (data[position].toInt() and 0xFF) or
            ((data[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value.toUShort()
    }

    fun readUShortArray() = UShortArray(readUShort().toInt()) { readUShort() }
}
