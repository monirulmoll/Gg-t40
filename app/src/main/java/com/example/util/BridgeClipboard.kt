package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred

object BridgeClipboard {

    fun copyToClipboard(context: Context, text: String): Boolean {
        return try {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("BridgeController", text)
                clipboard?.setPrimaryClip(clip)
            }
            BridgeLogger.logClipboard("Copied ${text.length} chars to clipboard")
            true
        } catch (e: Exception) {
            BridgeLogger.logError("Clipboard copy failed: ${e.message}")
            false
        }
    }

    suspend fun readClipboard(context: Context): String? {
        val deferred = CompletableDeferred<String?>()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
                        deferred.complete(text)
                        return@post
                    }
                }
                deferred.complete(null)
            } catch (e: Exception) {
                BridgeLogger.logError("Clipboard read error: ${e.message}")
                deferred.complete(null)
            }
        }
        val result = deferred.await()
        if (result != null) {
            BridgeLogger.logClipboard("Read ${result.length} chars from clipboard")
        } else {
            BridgeLogger.logClipboard("Clipboard is empty or inaccessible")
        }
        return result
    }
}
