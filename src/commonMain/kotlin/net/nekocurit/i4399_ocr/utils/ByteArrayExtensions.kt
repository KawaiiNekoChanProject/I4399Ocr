package net.nekocurit.i4399_ocr.utils


internal fun ByteArray.readUByte(position: Int): Int = this[position].toInt() and 0xFF

internal fun ByteArray.readUnsignedShortBigEndian(position: Int): Int =
    (readUByte(position) shl 8) or readUByte(position + 1)
