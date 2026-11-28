@file:OptIn(ExperimentalUnsignedTypes::class)
package net.nekocurit.i4399_ocr.utils

import net.nekocurit.i4399_ocr.model.Model
import net.nekocurit.i4399_ocr.model.Model.Companion.MAX_CAPACITY
import kotlin.math.abs

internal const val SOURCE_WIDTH = 160
internal const val SOURCE_HEIGHT = 48
internal const val CONTENT_HEIGHT = 31
private const val OCR_THRESHOLD = 0.50
private const val MAX_CHARACTERS = 4

private val COLUMN_SHIFT_TABLE = intArrayOf(
    2, 2, 5, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    4, 4, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2,
    0,
)

private class Candidate(
    val score: Double,
    val label: Char,
    val windowColumn: Int,
    val windowRow: Int,
)

/** 对灰度图执行一次识别；返回识别出的字符（0..4 个）。 */
internal fun recognize(model: Model, gray: GrayImage): String {
    val config = model.config
    val image = ByteArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    val sourceWidth = (gray.width + 15).coerceAtMost(SOURCE_WIDTH)
    image.fill(0xFF.toByte())

    // 二值化 + 垂直翻转 + 逐行水平剪切。
    for (outputRow in 0 until CONTENT_HEIGHT) {
        val sourceRow = 31 - outputRow
        if (sourceRow < 0 || sourceRow >= gray.height) continue
        val columnShift = COLUMN_SHIFT_TABLE[outputRow]
        for (outputColumn in 0 until sourceWidth) {
            val sourceColumn = outputColumn - columnShift
            if (sourceColumn < 0 || sourceColumn >= gray.width) continue
            if (gray.pixels.readUByte(sourceRow * gray.width + sourceColumn) <
                config.threshold
            ) {
                image[outputRow * SOURCE_WIDTH + outputColumn] = 0
            }
        }
    }

    val gridWidth = config.width
    val gridHeight = config.height
    val window = ByteArray(MAX_CAPACITY)
    val candidates = ArrayList<Candidate>(SOURCE_WIDTH * SOURCE_HEIGHT)
    val sameLabelRadius = config.nonMaxSuppressionRadius
    val differentLabelRadius =
        if (config.nonMaxSuppressionRadius > 2) config.nonMaxSuppressionRadius - 2
        else config.nonMaxSuppressionRadius

    for (windowRow in 0..20) {
        if (windowRow + gridHeight > SOURCE_HEIGHT) break
        for (windowColumn in 0..(sourceWidth - gridWidth)) {
            var backgroundCount = 0
            for (row in 0 until gridHeight) {
                val sourceRowIndex = (windowRow + row) * SOURCE_WIDTH + windowColumn
                val windowRowIndex = row * gridWidth
                for (column in 0 until gridWidth) {
                    val pixel = image[sourceRowIndex + column]
                    window[windowRowIndex + column] = pixel
                    if (pixel == 0xFF.toByte()) backgroundCount++
                }
            }
            if (backgroundCount < 200) continue

            var bestScore = 2.0
            var bestLabel = '\u0000'
            for (template in model.templates) {
                val pointCount = template.points.size
                if (pointCount < 40) continue
                var pointMatches = 0
                for (pointIndex in template.points.indices) {
                    val point = template.points[pointIndex].toInt()
                    if (point <= config.capacity && window[point - 1] == 0xFF.toByte()) {
                        pointMatches++
                    }
                }
                if (pointMatches > (0.545 * pointCount + 0.5).toInt()) continue
                var feature4Matches = 0
                if (template.feature4.isNotEmpty()) {
                    for (featureIndex in template.feature4.indices) {
                        val point = template.feature4[featureIndex].toInt()
                        if (point <= config.capacity && window[point - 1] == 0xFF.toByte()) {
                            feature4Matches++
                        }
                    }
                }
                val total = pointCount + template.feature4.size
                val score = (pointMatches + feature4Matches).toDouble() / total.toDouble() -
                    0.1 * total / 200.0
                if (score < bestScore) {
                    bestScore = score
                    bestLabel = template.label
                }
            }
            if (bestLabel != '\u0000' && bestScore < OCR_THRESHOLD) {
                candidates.add(Candidate(bestScore, bestLabel, windowColumn, windowRow))
            }
        }
    }

    candidates.sortBy { it.score }

    val selected = ArrayList<Candidate>(MAX_CHARACTERS)
    for (candidate in candidates) {
        if (selected.size >= MAX_CHARACTERS) break
        var accepted = true
        for (selectedCandidate in selected) {
            val radius =
                if (candidate.label == selectedCandidate.label) sameLabelRadius
                else differentLabelRadius
            if (abs(candidate.windowColumn - selectedCandidate.windowColumn) < radius &&
                abs(candidate.windowRow - selectedCandidate.windowRow) < radius
            ) {
                accepted = false
                break
            }
        }
        if (accepted) selected.add(candidate)
    }

    // 按窗口横坐标从左到右输出（插入排序，保持原实现语义）。
    for (index in 1 until selected.size) {
        val current = selected[index]
        var scanIndex = index
        while (scanIndex > 0 && selected[scanIndex - 1].windowColumn > current.windowColumn) {
            selected[scanIndex] = selected[scanIndex - 1]
            scanIndex--
        }
        selected[scanIndex] = current
    }
    return buildString(selected.size) {
        for (candidate in selected) append(candidate.label)
    }
}
