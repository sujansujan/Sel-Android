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
 * Lazy-initialized on-device OCR engine powered by Google Play Services ML Kit.
 *
 * Extracts pixel-accurate text blocks and per-line bounding boxes from screen captures
 * (images, videos, memes, PDFs, games, canvas apps) and outputs the exact same
 * ScreenGrabSnapshot model consumed by LensOverlayView.
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
        onSuccess: (ScreenGrabSnapshot) -> Unit,
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
                onSuccess(convertToSnapshot(visionText, bitmap.width, bitmap.height, density))
            }
            .addOnFailureListener { err ->
                onFailure(err)
            }
    }

    private fun convertToSnapshot(
        visionText: Text,
        imgW: Int,
        imgH: Int,
        density: Float
    ): ScreenGrabSnapshot {
        val raw = mutableListOf<Triple<String, Rect, List<RectF>>>()

        for (block in visionText.textBlocks) {
            val blockText = block.text.trim()
            if (blockText.isEmpty()) continue
            val blockBox = block.boundingBox ?: continue
            val clippedBlock = Rect(
                blockBox.left.coerceIn(0, imgW),
                blockBox.top.coerceIn(0, imgH),
                blockBox.right.coerceIn(0, imgW),
                blockBox.bottom.coerceIn(0, imgH)
            )
            if (clippedBlock.width() < 6 || clippedBlock.height() < 6) continue

            val lineRects = block.lines.mapNotNull { line ->
                line.boundingBox?.let { r ->
                    RectF(
                        r.left.coerceIn(0, imgW).toFloat(),
                        r.top.coerceIn(0, imgH).toFloat(),
                        r.right.coerceIn(0, imgW).toFloat(),
                        r.bottom.coerceIn(0, imgH).toFloat()
                    )
                }
            }
            raw.add(Triple(blockText, clippedBlock, lineRects))
        }

        val bandPx = (18 * density).toInt().coerceAtLeast(16)
        val sorted = raw.sortedWith(
            compareBy<Triple<String, Rect, List<RectF>>> { it.second.top / bandPx }
                .thenBy { it.second.left }
                .thenBy { it.second.top }
        )

        val blocks = sorted.mapIndexed { idx, (text, rect, lines) ->
            GrabbedBlock(
                id = idx,
                text = text,
                bounds = rect,
                lineRects = lines,
                isContentDescription = false
            )
        }

        val allText = blocks.joinToString("\n") { it.text }
        val entities = mutableListOf<ExtractedEntity>()
        val seen = HashSet<String>()
        TextTransforms.extractTorrentHash(allText)?.let { h ->
            if (seen.add(h)) entities.add(ExtractedEntity("HASH", h))
        }
        TextTransforms.extractEntities(allText, limit = 8).forEach { e ->
            if (seen.add(e.value)) entities.add(e)
        }

        return ScreenGrabSnapshot(blocks = blocks, quickEntities = entities.take(6))
    }

    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
    }
}
