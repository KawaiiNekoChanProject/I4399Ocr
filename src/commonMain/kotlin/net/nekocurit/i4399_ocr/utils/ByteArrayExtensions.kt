package net.nekocurit.i4399_ocr.utils


internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or
            (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or
            (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)