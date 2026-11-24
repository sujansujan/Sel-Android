package io.github.selclip

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * A single selectable word on screen with exact pixel coordinates (Google Lens model).
 */
data class LensWord(
    val index: Int,
    val text: String,
    val rect: RectF,
    val lineIndex: Int,
    val blockIndex: Int
)

/**
 * A visual line of words on screen (used for smooth continuous per-line highlights).
 */
data class LensLine(
    val lineIndex: Int,
    val blockIndex: Int,
    val bounds: RectF,
    val wordStartIndex: Int,
    val wordEndIndex: Int
)

/**
 * Complete recognized screen text snapshot for the Google Lens overlay.
 */
data class LensSnapshot(
    val words: List<LensWord>,
    val lines: List<LensLine>,
    val quickEntities: List<ExtractedEntity>,
    val isFromOcr: Boolean
)

/**
 * Lazy-initialized on-device OCR engine powered by Google Play Services ML Kit.
 *
 * Extracts pixel-accurate word-level elements (TextBlock -> Line -> Element) in natural
 * reading order so LensOverlayView behaves identically to Google Lens.
 */
class ScreenOcrEngine {

    private var recognizer: TextRecognizer? = null

    private fun getOrCreateClient(): TextRecognizer {
        return recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
            recognizer = it
        }
    }

    fun recognizeBitmap(
        bitmap: Bitmap,
        density: Float,
        onSuccess: (LensSnapshot) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            onFailure(e)
            return
        }

        val client = try {
            getOrCreateClient()
        } catch (e: Exception) {
            onFailure(e)
            return
        }

        client.process(image)
            .addOnSuccessListener { visionText ->
                onSuccess(convertToLensSnapshot(visionText, bitmap.width, bitmap.height, density))
            }
            .addOnFailureListener { err ->
                onFailure(err)
            }
    }

    private fun convertToLensSnapshot(
        visionText: Text,
        imgW: Int,
        imgH: Int,
        density: Float
    ): LensSnapshot {
        val bandPx = (24 * density).toInt().coerceAtLeast(20)
        val sortedBlocks = visionText.textBlocks
            .filter { it.boundingBox != null && it.text.isNotBlank() }
            .sortedWith(
                compareBy<Text.TextBlock> { (it.boundingBox?.top ?: 0) / bandPx }
                    .thenBy { it.boundingBox?.left ?: 0 }
                    .thenBy { it.boundingBox?.top ?: 0 }
            )

        val words = ArrayList<LensWord>(128)
        val lines = ArrayList<LensLine>(48)
        val allTextBuilder = StringBuilder()

        var blockIdx = 0
        var lineIdx = 0

        for (block in sortedBlocks) {
            val sortedLines = block.lines
                .filter { it.boundingBox != null && it.text.isNotBlank() }
                .sortedWith(
                    compareBy<Text.Line> { it.boundingBox?.top ?: 0 }
                        .thenBy { it.boundingBox?.left ?: 0 }
                )

            var blockHadWords = false
            for (line in sortedLines) {
                val lineStartWord = words.size
                val sortedElements = line.elements
                    .filter { it.boundingBox != null && it.text.isNotBlank() }
                    .sortedBy { it.boundingBox?.left ?: 0 }

                if (sortedElements.isNotEmpty()) {
                    for (el in sortedElements) {
                        val b = el.boundingBox ?: continue
                        val rf = clipRect(b, imgW, imgH) ?: continue
                        val wordText = el.text.trim()
                        if (wordText.isEmpty()) continue
                        words.add(
                            LensWord(
                                index = words.size,
                                text = wordText,
                                rect = rf,
                                lineIndex = lineIdx,
                                blockIndex = blockIdx
                            )
                        )
                    }
                } else {
                    val lb = line.boundingBox?.let { clipRect(it, imgW, imgH) }
                    if (lb != null) {
                        appendEstimatedWordsForLine(line.text.trim(), lb, lineIdx, blockIdx, words)
                    }
                }

                val lineEndWord = words.size - 1
                if (lineEndWord >= lineStartWord) {
                    val lb = line.boundingBox?.let { clipRect(it, imgW, imgH) }
                        ?: unionWordRects(words, lineStartWord, lineEndWord)
                    lines.add(
                        LensLine(
                            lineIndex = lineIdx,
                            blockIndex = blockIdx,
                            bounds = lb,
                            wordStartIndex = lineStartWord,
                            wordEndIndex = lineEndWord
                        )
                    )
                    lineIdx++
                    blockHadWords = true
                }
            }

            if (blockHadWords) {
                if (allTextBuilder.isNotEmpty()) allTextBuilder.append('\n')
                allTextBuilder.append(block.text.trim())
                blockIdx++
            }
        }

        val fullText = allTextBuilder.toString()
        val entities = extractQuickEntities(fullText)

        return LensSnapshot(
            words = words,
            lines = lines,
            quickEntities = entities,
            isFromOcr = true
        )
    }

    private fun clipRect(r: Rect, imgW: Int, imgH: Int): RectF? {
        val l = r.left.coerceIn(0, imgW).toFloat()
        val t = r.top.coerceIn(0, imgH).toFloat()
        val ri = r.right.coerceIn(0, imgW).toFloat()
        val b = r.bottom.coerceIn(0, imgH).toFloat()
        if (ri - l < 3f || b - t < 3f) return null
        return RectF(l, t, ri, b)
    }

    private fun unionWordRects(words: List<LensWord>, start: Int, end: Int): RectF {
        val out = RectF(words[start].rect)
        for (i in (start + 1)..end) {
            out.union(words[i].rect)
        }
        return out
    }

    companion object {
        fun appendEstimatedWordsForLine(
            lineText: String,
            lineRect: RectF,
            lineIndex: Int,
            blockIndex: Int,
            out: MutableList<LensWord>
        ) {
            val tokens = lineText.split(TextTransforms.whitespaceRegex).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return
            val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
            val gap = (lineRect.height() * 0.22f).coerceIn(3f, 12f)
            val totalGaps = gap * (tokens.size - 1).coerceAtLeast(0)
            val availW = (lineRect.width() - totalGaps).coerceAtLeast(12f)
            var curX = lineRect.left
            tokens.forEachIndexed { idx, tok ->
                val w = if (idx == tokens.lastIndex) {
                    (lineRect.right - curX).coerceAtLeast(8f)
                } else {
                    (availW * (tok.length.toFloat() / totalChars.toFloat())).coerceAtLeast(8f)
                }
                val r = RectF(curX, lineRect.top, (curX + w).coerceAtMost(lineRect.right), lineRect.bottom)
                out.add(LensWord(out.size, tok, r, lineIndex, blockIndex))
                curX += w + gap
            }
        }

        fun extractQuickEntities(allText: String): List<ExtractedEntity> {
            val entities = mutableListOf<ExtractedEntity>()
            val seen = HashSet<String>()
            TextTransforms.extractNepalPhone(allText)?.let { ph ->
                if (seen.add(ph.dialNumber)) entities.add(ExtractedEntity("TEL", ph.display))
            }
            TextTransforms.extractTorrentHash(allText)?.let { h ->
                if (seen.add(h)) entities.add(ExtractedEntity("HASH", h))
            }
            TextTransforms.extractEntities(allText, limit = 8).forEach { e ->
                if (seen.add(e.value)) entities.add(e)
            }
            return entities.take(6)
        }
    }

    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
    }
}
