package io.github.selclip.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.github.selclip.core.PopupMode
import io.github.selclip.core.TextTransforms
import io.github.selclip.data.PopAction
import io.github.selclip.data.PrefsStore
import io.github.selclip.data.ToolSpec

/**
 * Executes popup actions cleanly and manages lazy handlers (network, TTS) + editable Undo state.
 * (Clipboard history & scratchpad notes removed.)
 */
class ActionExecutor(
    private val context: Context,
    private val clipboardManager: ClipboardManager,
    private val onSwitchMode: (PopupMode) -> Unit,
    private val onDismiss: () -> Unit
) {

    val networkClient = LazyNetworkClient()
    private val ttsSpeaker = LazyTtsSpeaker(context)

    private var undoOriginalText: String? = null
    private var undoNode: AccessibilityNodeInfo? = null
    private var undoTimestampMs: Long = 0L

    fun hasValidUndo(nowMs: Long): Boolean =
        undoOriginalText != null && undoNode != null && (nowMs - undoTimestampMs) < 45_000L

    fun shutdown() {
        networkClient.shutdown()
        ttsSpeaker.shutdown()
        undoOriginalText = null
        undoNode = null
    }

    fun executePopAction(act: PopAction, selectedText: String, editableNode: AccessibilityNodeInfo?) {
        if (act.customTool != null) {
            PrefsStore.recordUse(context, act.customTool.id)
            executeToolSpec(act.customTool, selectedText, editableNode)
            return
        }

        val trimmed = selectedText.trim()
        val encoded = Uri.encode(trimmed)

        when (act.id) {
            "undo" -> performUndo()
            "default_search" -> {
                PrefsStore.recordUse(context, "web_search_folder")
                val engine = PrefsStore.resolveDefaultSearchEngine(context)
                val target = engine.customTarget ?: "https://www.google.com/search?q={text}"
                openUrl(target.replace("{text}", encoded))
            }
            "youtube_direct" -> {
                PrefsStore.recordUse(context, "youtube_folder")
                openUrl("https://www.youtube.com/results?search_query=$encoded")
            }
            "phone_call" -> {
                val num = act.payload ?: trimmed
                launchIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(num)}")))
                onDismiss()
            }
            "phone_sms" -> {
                val num = act.payload ?: trimmed
                val ok = launchIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(num)}")))
                if (!ok) launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse("sms:${Uri.encode(num)}")))
                onDismiss()
            }
            "phone_wa" -> openWhatsApp(act.payload ?: "", isBusiness = false)
            "phone_w4b" -> openWhatsApp(act.payload ?: "", isBusiness = true)
            "open_url" -> {
                val raw = act.payload ?: trimmed
                val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
                openUrl(url)
            }
            "email_send" -> {
                val mail = act.payload ?: trimmed
                launchIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(mail)}")))
                onDismiss()
            }
            "open_magnet" -> {
                val mag = act.payload ?: trimmed
                if (!launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(mag)))) {
                    copyPlain(mag, "No torrent app — magnet copied")
                }
                onDismiss()
            }
            "apply_computed" -> act.payload?.let { applyResult(it, editableNode) }
            "pipe_json_pretty" -> applyResult(TextTransforms.applyPipeTool("pipe_json_pretty", selectedText), editableNode)
            "pipe_strip_html" -> applyResult(TextTransforms.applyPipeTool("pipe_strip_html", selectedText), editableNode)
            "clean_url" -> {
                PrefsStore.recordUse(context, "clean_url")
                applyResult(TextTransforms.cleanUrl(selectedText), editableNode)
            }
            "clean_text" -> {
                PrefsStore.recordUse(context, "clean_text")
                applyResult(TextTransforms.cleanText(selectedText), editableNode)
            }
            "sort" -> {
                PrefsStore.recordUse(context, "sort")
                applyResult(TextTransforms.sortUniqueLines(selectedText), editableNode)
            }
            "case" -> {
                PrefsStore.recordUse(context, "case")
                applyResult(TextTransforms.cycleCase(selectedText), editableNode)
            }
            "speak" -> {
                PrefsStore.recordUse(context, "speak")
                ttsSpeaker.speak(trimmed)
            }
            "define" -> {
                PrefsStore.recordUse(context, "define")
                onSwitchMode(PopupMode.Define)
            }
            "translate" -> {
                PrefsStore.recordUse(context, "translate")
                onSwitchMode(PopupMode.Translate)
            }
            "web_search_folder" -> onSwitchMode(PopupMode.WebSearch)
            "youtube_folder" -> onSwitchMode(PopupMode.YouTube)
            "qr" -> {
                PrefsStore.recordUse(context, "qr")
                onSwitchMode(PopupMode.QrCode)
            }
            "magnet" -> {
                PrefsStore.recordUse(context, "magnet")
                onSwitchMode(PopupMode.Magnet)
            }
            "extract" -> {
                PrefsStore.recordUse(context, "extract")
                onSwitchMode(PopupMode.Extract)
            }
            "regex_pipe" -> {
                PrefsStore.recordUse(context, "regex_pipe")
                onSwitchMode(PopupMode.RegexPipe)
            }
            else -> onSwitchMode(PopupMode.MoreTools)
        }
    }

    fun executePopActionLongPress(act: PopAction, selectedText: String, editableNode: AccessibilityNodeInfo?) {
        when (act.id) {
            "default_search", "web_search_folder" -> onSwitchMode(PopupMode.WebSearch)
            "youtube_direct", "youtube_folder" -> onSwitchMode(PopupMode.YouTube)
            else -> executePopAction(act, selectedText, editableNode)
        }
    }

    fun executeToolSpec(tool: ToolSpec, selectedText: String, editableNode: AccessibilityNodeInfo?) {
        val trimmed = selectedText.trim()
        val encoded = Uri.encode(trimmed)

        if (tool.folderName != null && tool.customTarget == null) {
            onSwitchMode(PopupMode.CustomFolder(tool.folderName))
            return
        }
        if (tool.customTarget != null) {
            val target = tool.customTarget.trim()
            if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                openUrl(target.replace("{text}", encoded))
            } else {
                applyResult(TextTransforms.executeCustomTargetTransform(target, selectedText), editableNode)
            }
            return
        }

        when (tool.id) {
            "web_search_folder" -> onSwitchMode(PopupMode.WebSearch)
            "youtube_folder" -> onSwitchMode(PopupMode.YouTube)
            "markdown_folder" -> onSwitchMode(PopupMode.Markdown)
            "regex_pipe" -> onSwitchMode(PopupMode.RegexPipe)
            "define" -> onSwitchMode(PopupMode.Define)
            "translate" -> onSwitchMode(PopupMode.Translate)
            "qr" -> onSwitchMode(PopupMode.QrCode)
            "magnet" -> onSwitchMode(PopupMode.Magnet)
            "extract" -> onSwitchMode(PopupMode.Extract)
            "clean_url" -> applyResult(TextTransforms.cleanUrl(selectedText), editableNode)
            "clean_text" -> applyResult(TextTransforms.cleanText(selectedText), editableNode)
            "case" -> applyResult(TextTransforms.cycleCase(selectedText), editableNode)
            "sort" -> applyResult(TextTransforms.sortUniqueLines(selectedText), editableNode)
            "speak" -> ttsSpeaker.speak(trimmed)
        }
    }

    fun copyPlain(text: String, toastMsg: String? = null) {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", text))
        val preview = text.replace("\n", " ").let { if (it.length > 28) it.take(27) + "…" else it }
        Toast.makeText(context, toastMsg ?: "Copied: $preview", Toast.LENGTH_SHORT).show()
    }

    fun applyResult(newText: String, editableNode: AccessibilityNodeInfo?) {
        var replaced = false
        if (editableNode != null && editableNode.isEditable) {
            runCatching { editableNode.refresh() }
            val full = editableNode.text?.toString() ?: ""
            val start = minOf(editableNode.textSelectionStart, editableNode.textSelectionEnd)
            val end = maxOf(editableNode.textSelectionStart, editableNode.textSelectionEnd)
            if (start >= 0 && end > start && end <= full.length) {
                undoOriginalText = full
                undoNode = editableNode
                undoTimestampMs = System.currentTimeMillis()
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        full.replaceRange(start, end, newText)
                    )
                }
                replaced = runCatching {
                    editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }.getOrDefault(false)
            }
            if (!replaced) {
                // Fallback to paste only when direct in-place ACTION_SET_TEXT is unsupported
                clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", newText))
                replaced = runCatching {
                    editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }.getOrDefault(false)
            }
        } else {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("sel", newText))
        }

        if (!replaced) {
            val preview = newText.replace("\n", " ").let { if (it.length > 28) it.take(27) + "…" else it }
            Toast.makeText(context, "Copied: $preview", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }

    fun openUrl(url: String) {
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        onDismiss()
    }

    private fun performUndo() {
        val prev = undoOriginalText
        val node = undoNode
        if (prev != null && node != null) {
            runCatching { node.refresh() }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prev)
            }
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            undoOriginalText = null
            undoNode = null
            if (ok) {
                Toast.makeText(context, "Restored original text", Toast.LENGTH_SHORT).show()
            } else {
                copyPlain(prev, "Copied original text")
            }
        }
        onDismiss()
    }

    private fun openWhatsApp(waDigits: String, isBusiness: Boolean) {
        val pkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        val native = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$waDigits")).setPackage(pkg)
        if (launchIntent(native)) {
            onDismiss()
            return
        }
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$waDigits")).setPackage(pkg)
        if (launchIntent(web)) {
            onDismiss()
            return
        }
        openUrl("https://wa.me/$waDigits")
    }

    private fun launchIntent(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
