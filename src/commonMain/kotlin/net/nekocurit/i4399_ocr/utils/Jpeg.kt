package net.nekocurit.i4399_ocr.utils

import net.nekocurit.i4399_ocr.utils.GrayImage.Companion.toGray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 纯 Kotlin 的基线（baseline sequential）JPEG 解码器。
 *
 * 支持：8 位精度、1/3 个分量、4:4:4 / 4:2:2 / 4:2:0 等任意采样因子、
 * Huffman 编码、DRI/RSTn 重启标记。渐进式 JPEG 不支持（4399 验证码为基线 JPEG）。
 */
internal fun decodeJpeg(data: ByteArray): GrayImage = JpegDecoder(data).decode()

private val ZIGZAG_ORDER = intArrayOf(
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
)

private val INVERSE_DCT_BASIS: Array<DoubleArray> = Array(8) { spatialX ->
    DoubleArray(8) { frequencyX ->
        val normalization = if (frequencyX == 0) 1.0 / sqrt(2.0) else 1.0
        normalization * cos((2 * spatialX + 1) * frequencyX * PI / 16.0) / 2.0
    }
}

private class JpegHuffman(counts: IntArray, val symbols: IntArray) {
    private val count = IntArray(17)
    private val firstCode = IntArray(17)
    private val firstIndex = IntArray(17)

    init {
        var total = 0
        for (codeLength in 1..16) {
            count[codeLength] = counts[codeLength - 1]
            total += count[codeLength]
        }
        var code = 0
        var symbolIndex = 0
        for (codeLength in 1..16) {
            code = (code + count[codeLength - 1]) shl 1
            firstCode[codeLength] = code
            firstIndex[codeLength] = symbolIndex
            symbolIndex += count[codeLength]
        }
        if (symbolIndex != symbols.size) {
            throw IllegalArgumentException("jpeg: bad huffman table")
        }
    }

    fun decode(bitReader: JpegBits): Int {
        var code = 0
        for (codeLength in 1..16) {
            code = (code shl 1) or bitReader.readBit()
            val symbolIndex = code - firstCode[codeLength]
            if (symbolIndex in 0 until count[codeLength]) {
                return symbols[firstIndex[codeLength] + symbolIndex]
            }
        }
        throw IllegalArgumentException("jpeg: invalid huffman code")
    }
}

private class JpegBits(private val data: ByteArray, var position: Int) {
    var byteBuffer = 0
    var bitCount = 0
    var pendingMarker = -1
    var pendingMarkerPosition = -1
    var pendingMarkerCodePosition = -1

    fun readBit(): Int {
        if (bitCount == 0) {
            byteBuffer = readNextByte()
            bitCount = 8
        }
        bitCount--
        return (byteBuffer ushr bitCount) and 1
    }

    fun readBits(bitCount: Int): Int {
        var value = 0
        repeat(bitCount) { value = (value shl 1) or readBit() }
        return value
    }

    fun align() {
        bitCount = 0
    }

    private fun readNextByte(): Int {
        if (position >= data.size) return 0
        val start = position
        val firstByte = data.readUByte(position++)
        if (firstByte != 0xFF) return firstByte
        while (position < data.size && data.readUByte(position) == 0xFF) position++
        if (position >= data.size) return 0
        val secondByte = data.readUByte(position)
        if (secondByte == 0) {
            position++
            return 0xFF
        }
        pendingMarker = secondByte
        pendingMarkerPosition = start
        pendingMarkerCodePosition = position
        return 0
    }

    fun consumeMarker(): Int {
        if (pendingMarker >= 0) {
            val marker = pendingMarker
            pendingMarker = -1
            position = pendingMarkerCodePosition + 1
            return marker
        }
        var scanPosition = position
        while (scanPosition < data.size && data.readUByte(scanPosition) != 0xFF) {
            scanPosition++
        }
        if (scanPosition >= data.size) return -1
        while (scanPosition < data.size && data.readUByte(scanPosition) == 0xFF) {
            scanPosition++
        }
        if (scanPosition >= data.size) return -1
        val marker = data.readUByte(scanPosition)
        position = scanPosition + 1
        return marker
    }

    fun nextMarkerPosition(): Int = if (pendingMarker >= 0) pendingMarkerPosition else position
}

class JpegComponent(
    val identifier: Int,
    val horizontalSampling: Int,
    val verticalSampling: Int,
    val quantizationTableId: Int,
) {
    var dcHuffmanTableIndex = 0
    var acHuffmanTableIndex = 0
    var dcPrediction = 0
    var blockColumns = 0
    var blockRows = 0
    var stride = 0
    var plane = IntArray(0)
}

class JpegDecoder(private val data: ByteArray) {
    private val quantizationTables = Array(4) { IntArray(64) }
    private val quantizationTableSet = BooleanArray(4)
    private val dcHuffmanTables = arrayOfNulls<JpegHuffman>(4)
    private val acHuffmanTables = arrayOfNulls<JpegHuffman>(4)
    val components = ArrayList<JpegComponent>()
    var width = 0
    var height = 0
    var maximumHorizontalSampling = 1
    var maximumVerticalSampling = 1
    var mcuColumns = 0
    var mcuRows = 0
    var restartInterval = 0
    var adobeTransform = -1
    var scanDataStart = 0

    fun decode(): GrayImage {
        var position = 2
        while (position + 1 < data.size) {
            while (position < data.size && data.readUByte(position) != 0xFF) position++
            while (position < data.size && data.readUByte(position) == 0xFF) position++
            if (position >= data.size) break
            val marker = data.readUByte(position++)
            if (marker == 0xD9) break
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
            if (position + 2 > data.size) break
            val segmentLength = data.readUnsignedShortBigEndian(position)
            if (segmentLength < 2) break
            val segmentEnd = position + segmentLength
            if (segmentEnd > data.size) break
            when (marker) {
                0xC0, 0xC1 -> parseStartOfFrame(position + 2, segmentEnd)
                0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                    error("jpeg: unsupported SOF marker 0x${marker.toString(16)}")
                0xC4 -> parseDefineHuffmanTable(position + 2, segmentEnd)
                0xDB -> parseDefineQuantizationTable(position + 2, segmentEnd)
                0xDD -> if (segmentEnd >= position + 4) {
                    restartInterval = data.readUnsignedShortBigEndian(position + 2)
                }
                0xDA -> {
                    scanDataStart = position + 2
                    parseStartOfScan(position + 2, segmentEnd)
                    val markerPosition = decodeScan(segmentEnd)
                    position = if (markerPosition >= 0) markerPosition else data.size
                    continue
                }
                0xEE -> parseAdobe(position + 2, segmentEnd)
            }
            position = segmentEnd
        }
        if (components.isEmpty()) error("jpeg: missing SOF")
        if (width <= 0 || height <= 0) error("jpeg: invalid dimensions ${width}x$height")
        return this.toGray()
    }

    private fun parseStartOfFrame(dataStart: Int, dataEnd: Int) {
        if (dataStart + 6 > dataEnd) throw IllegalArgumentException("jpeg: bad SOF")
        val precision = data.readUByte(dataStart)
        val frameHeight = data.readUnsignedShortBigEndian(dataStart + 1)
        val frameWidth = data.readUnsignedShortBigEndian(dataStart + 3)
        val componentCount = data.readUByte(dataStart + 5)
        if (precision != 8 || frameWidth <= 0 || frameHeight <= 0 ||
            componentCount !in 1..4 || componentCount == 2
        ) {
            throw IllegalArgumentException("jpeg: unsupported SOF")
        }
        if (dataStart + 6 + componentCount * 3 > dataEnd) {
            throw IllegalArgumentException("jpeg: bad SOF")
        }
        width = frameWidth
        height = frameHeight
        components.clear()
        maximumHorizontalSampling = 1
        maximumVerticalSampling = 1
        for (componentIndex in 0 until componentCount) {
            val componentPosition = dataStart + 6 + componentIndex * 3
            val identifier = data.readUByte(componentPosition)
            val samplingFactors = data.readUByte(componentPosition + 1)
            val horizontalSampling = samplingFactors shr 4
            val verticalSampling = samplingFactors and 15
            val quantizationTableId = data.readUByte(componentPosition + 2)
            if (horizontalSampling !in 1..4 || verticalSampling !in 1..4 ||
                quantizationTableId > 3
            ) {
                throw IllegalArgumentException("jpeg: bad component")
            }
            components.add(
                JpegComponent(
                    identifier,
                    horizontalSampling,
                    verticalSampling,
                    quantizationTableId,
                )
            )
            if (horizontalSampling > maximumHorizontalSampling) {
                maximumHorizontalSampling = horizontalSampling
            }
            if (verticalSampling > maximumVerticalSampling) {
                maximumVerticalSampling = verticalSampling
            }
        }
        mcuColumns = (width + 8 * maximumHorizontalSampling - 1) /
            (8 * maximumHorizontalSampling)
        mcuRows = (height + 8 * maximumVerticalSampling - 1) /
            (8 * maximumVerticalSampling)
        for (component in components) {
            component.blockColumns = mcuColumns * component.horizontalSampling
            component.blockRows = mcuRows * component.verticalSampling
            component.stride = component.blockColumns * 8
            component.plane = IntArray(component.stride * component.blockRows * 8)
        }
    }

    private fun parseDefineQuantizationTable(dataStart: Int, dataEnd: Int) {
        var position = dataStart
        while (position < dataEnd) {
            val precisionAndTableId = data.readUByte(position++)
            val precision = precisionAndTableId shr 4
            val tableId = precisionAndTableId and 15
            if (precision > 1 || tableId > 3) {
                throw IllegalArgumentException("jpeg: bad DQT")
            }
            for (index in 0 until 64) {
                val value = if (precision == 0) {
                    if (position >= dataEnd) {
                        throw IllegalArgumentException("jpeg: truncated DQT")
                    }
                    data.readUByte(position++)
                } else {
                    if (position + 2 > dataEnd) {
                        throw IllegalArgumentException("jpeg: truncated DQT")
                    }
                    val quantizationValue = data.readUnsignedShortBigEndian(position)
                    position += 2
                    quantizationValue
                }
                quantizationTables[tableId][ZIGZAG_ORDER[index]] = value
            }
            quantizationTableSet[tableId] = true
        }
    }

    private fun parseDefineHuffmanTable(dataStart: Int, dataEnd: Int) {
        var position = dataStart
        while (position < dataEnd) {
            val tableClassAndId = data.readUByte(position++)
            val tableClass = tableClassAndId shr 4
            val tableId = tableClassAndId and 15
            if (tableClass > 1 || tableId > 3) {
                throw IllegalArgumentException("jpeg: bad DHT")
            }
            if (position + 16 > dataEnd) {
                throw IllegalArgumentException("jpeg: truncated DHT")
            }
            val counts = IntArray(16)
            var total = 0
            for (index in 0 until 16) {
                counts[index] = data.readUByte(position++)
                total += counts[index]
            }
            if (total > 256 || position + total > dataEnd) {
                throw IllegalArgumentException("jpeg: bad DHT size")
            }
            val symbols = IntArray(total) { data.readUByte(position++) }
            val table = JpegHuffman(counts, symbols)
            if (tableClass == 0) dcHuffmanTables[tableId] = table
            else acHuffmanTables[tableId] = table
        }
    }

    private fun parseAdobe(dataStart: Int, dataEnd: Int) {
        if (dataEnd - dataStart < 12) return
        if (data.readUByte(dataStart) != 'A'.code ||
            data.readUByte(dataStart + 1) != 'd'.code ||
            data.readUByte(dataStart + 2) != 'o'.code ||
            data.readUByte(dataStart + 3) != 'b'.code ||
            data.readUByte(dataStart + 4) != 'e'.code
        ) return
        adobeTransform = data.readUByte(dataStart + 11)
    }

    private fun parseStartOfScan(dataStart: Int, dataEnd: Int) {
        if (dataStart >= dataEnd) throw IllegalArgumentException("jpeg: bad SOS")
        val componentCount = data.readUByte(dataStart)
        if (componentCount !in 1..components.size ||
            dataStart + 1 + componentCount * 2 + 3 > dataEnd
        ) {
            throw IllegalArgumentException("jpeg: bad SOS size")
        }
        for (componentIndex in 0 until componentCount) {
            val identifier = data.readUByte(dataStart + 1 + componentIndex * 2)
            val tableSelectors = data.readUByte(dataStart + 2 + componentIndex * 2)
            val component = components.firstOrNull { it.identifier == identifier }
                ?: throw IllegalArgumentException("jpeg: unknown component")
            component.dcHuffmanTableIndex = tableSelectors shr 4
            component.acHuffmanTableIndex = tableSelectors and 15
            if (component.dcHuffmanTableIndex > 3 || component.acHuffmanTableIndex > 3) {
                throw IllegalArgumentException("jpeg: bad table id")
            }
        }
        val spectralStart = data.readUByte(dataStart + 1 + componentCount * 2)
        val spectralEnd = data.readUByte(dataStart + 2 + componentCount * 2)
        val successiveApproximation =
            data.readUByte(dataStart + 3 + componentCount * 2)
        if (spectralStart != 0 || spectralEnd != 63 || successiveApproximation != 0) {
            throw IllegalArgumentException("jpeg: progressive scan unsupported")
        }
    }

    private fun decodeScan(entropyStart: Int): Int {
        // parseStartOfScan 已校验头部；这里重新读取分量列表。
        val dataStart = scanDataStart
        val componentCount = data.readUByte(dataStart)
        val scanComponents = ArrayList<JpegComponent>(componentCount)
        for (componentIndex in 0 until componentCount) {
            val identifier = data.readUByte(dataStart + 1 + componentIndex * 2)
            scanComponents.add(components.first { it.identifier == identifier })
        }
        val bitReader = JpegBits(data, entropyStart)
        for (component in components) component.dcPrediction = 0

        var mcuCount = 0
        val coefficients = IntArray(64)

        fun afterMinimumCodedUnit(): Boolean {
            mcuCount++
            if (restartInterval > 0 && mcuCount % restartInterval == 0) {
                bitReader.align()
                val marker = bitReader.consumeMarker()
                if (marker !in 0xD0..0xD7) return false
                for (component in components) component.dcPrediction = 0
            }
            return true
        }

        if (componentCount == 1 && components.size > 1) {
            val component = scanComponents[0]
            val actualWidth =
                (width * component.horizontalSampling + maximumHorizontalSampling - 1) /
                    maximumHorizontalSampling
            val actualHeight =
                (height * component.verticalSampling + maximumVerticalSampling - 1) /
                    maximumVerticalSampling
            val blockColumns = (actualWidth + 7) / 8
            val blockRows = (actualHeight + 7) / 8
            for (blockRow in 0 until blockRows) {
                for (blockColumn in 0 until blockColumns) {
                    decodeBlock(component, blockColumn, blockRow, bitReader, coefficients)
                    if (!afterMinimumCodedUnit()) return bitReader.nextMarkerPosition()
                }
            }
        } else {
            for (mcuRow in 0 until mcuRows) {
                for (mcuColumn in 0 until mcuColumns) {
                    for (component in scanComponents) {
                        for (verticalIndex in 0 until component.verticalSampling) {
                            for (horizontalIndex in 0 until component.horizontalSampling) {
                                decodeBlock(
                                    component,
                                    mcuColumn * component.horizontalSampling + horizontalIndex,
                                    mcuRow * component.verticalSampling + verticalIndex,
                                    bitReader,
                                    coefficients,
                                )
                            }
                        }
                    }
                    if (!afterMinimumCodedUnit()) return bitReader.nextMarkerPosition()
                }
            }
        }
        return bitReader.nextMarkerPosition()
    }

    private fun decodeBlock(
        component: JpegComponent,
        blockColumn: Int,
        blockRow: Int,
        bitReader: JpegBits,
        coefficients: IntArray,
    ) {
        coefficients.fill(0)
        val dcTable = dcHuffmanTables[component.dcHuffmanTableIndex]
            ?: throw IllegalArgumentException("jpeg: missing DC table")
        val acTable = acHuffmanTables[component.acHuffmanTableIndex]
            ?: throw IllegalArgumentException("jpeg: missing AC table")
        val quantizationTable = quantizationTables[component.quantizationTableId]
        if (!quantizationTableSet[component.quantizationTableId]) {
            throw IllegalArgumentException("jpeg: missing quant table")
        }

        val dcCategory = dcTable.decode(bitReader)
        if (dcCategory > 11) throw IllegalArgumentException("jpeg: bad DC category")
        val difference =
            if (dcCategory == 0) 0 else extend(bitReader.readBits(dcCategory), dcCategory)
        component.dcPrediction += difference
        coefficients[0] = component.dcPrediction * quantizationTable[0]

        var coefficientIndex = 1
        while (coefficientIndex < 64) {
            val runAndSize = acTable.decode(bitReader)
            val runLength = runAndSize shr 4
            val size = runAndSize and 15
            if (size == 0) {
                if (runLength == 15) {
                    coefficientIndex += 16
                    continue
                }
                break
            }
            if (size > 10) throw IllegalArgumentException("jpeg: bad AC size")
            coefficientIndex += runLength
            if (coefficientIndex > 63) break
            val value = extend(bitReader.readBits(size), size)
            val zigzagIndex = ZIGZAG_ORDER[coefficientIndex]
            coefficients[zigzagIndex] = value * quantizationTable[zigzagIndex]
            coefficientIndex++
        }
        idct(coefficients, component, blockColumn * 8, blockRow * 8)
    }

    private fun idct(
        coefficients: IntArray,
        component: JpegComponent,
        outputX: Int,
        outputY: Int,
    ) {
        val temporary = DoubleArray(64)
        for (row in 0 until 8) {
            val rowOffset = row * 8
            for (column in 0 until 8) {
                var sum = 0.0
                val basis = INVERSE_DCT_BASIS[column]
                for (frequencyIndex in 0 until 8) {
                    sum += basis[frequencyIndex] * coefficients[rowOffset + frequencyIndex]
                }
                temporary[rowOffset + column] = sum
            }
        }
        for (column in 0 until 8) {
            for (row in 0 until 8) {
                var sum = 0.0
                val basis = INVERSE_DCT_BASIS[row]
                for (frequencyIndex in 0 until 8) {
                    sum += basis[frequencyIndex] * temporary[frequencyIndex * 8 + column]
                }
                val value = (sum.roundToInt() + 128).coerceIn(0, 255)
                component.plane[(outputY + row) * component.stride + outputX + column] = value
            }
        }
    }

    private fun extend(value: Int, bitCount: Int): Int =
        if (bitCount == 0) 0
        else if (value < (1 shl (bitCount - 1))) value - (1 shl bitCount) + 1
        else value

    fun sample(component: JpegComponent, sampleX: Int, sampleY: Int): Int {
        val sourceX =
            (sampleX * component.horizontalSampling / maximumHorizontalSampling)
                .coerceIn(0, component.blockColumns * 8 - 1)
        val sourceY =
            (sampleY * component.verticalSampling / maximumVerticalSampling)
                .coerceIn(0, component.blockRows * 8 - 1)
        return component.plane[sourceY * component.stride + sourceX]
    }
}
