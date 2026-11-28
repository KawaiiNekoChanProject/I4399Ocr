@file:OptIn(ExperimentalUnsignedTypes::class)
package net.nekocurit.i4399_ocr.model

import net.nekocurit.i4399_ocr.utils.SimpleReader

class Model(
    val config: Config,
    val templates: List<Template>
) {

    companion object {
        internal const val MAX_CAPACITY = 1400
        fun parse(data: ByteArray) = SimpleReader(data, 6)
            .let { reader ->
                val config = Config(reader)
                val templates = List(reader.readUShort().toInt()) { Template(reader) }

                Model(config, templates)
            }
    }

    class Config(
        val width: Int,
        val height: Int,
        val nonMaxSuppressionRadius: Int,
        val threshold: Int,
        val capacity: Int
    ) {
        constructor(reader: SimpleReader) : this(
            width = reader.readUByte().toInt(),
            height = reader.readUByte().toInt(),
            nonMaxSuppressionRadius = reader.readUByte().toInt(),
            threshold = reader.readUByte().toInt(),
            capacity = reader.readUShort().toInt(),
        )
    }

    class Template(
        val label: Char,
        val points: UShortArray,
        val feature4: UShortArray
    ) {
        constructor(reader: SimpleReader): this(reader.readUByte().toInt().toChar(), reader.readUShortArray(), reader.readUShortArray())
    }
}
