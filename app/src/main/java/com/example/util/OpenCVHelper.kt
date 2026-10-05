package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import com.example.R
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean

object OpenCVHelper {

    private val initialized = AtomicBoolean(false)

    fun init(): Boolean {
        if (initialized.get()) return true
        return try {
            if (OpenCVLoader.initDebug()) {
                initialized.set(true)
                BridgeLogger.logSystem("OpenCV initialized successfully: ${Core.VERSION}")
                true
            } else {
                BridgeLogger.logError("OpenCVLoader.initDebug() returned false")
                false
            }
        } catch (e: Throwable) {
            BridgeLogger.logError("OpenCV initialization exception: ${e.message}")
            false
        }
    }

    fun isReady(): Boolean = initialized.get()

    /**
     * Converts an Android Bitmap to an OpenCV Mat.
     * Ensures ARGB_8888 software bitmap format.
     */
    fun bitmapToMat(bitmap: Bitmap): Mat {
        init()
        val isHw = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        val softwareBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888 || isHw) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val mat = Mat()
        Utils.bitmapToMat(softwareBitmap, mat)
        if (softwareBitmap != bitmap) {
            softwareBitmap.recycle()
        }
        return mat
    }

    /**
     * Renders a vector drawable resource to an OpenCV Mat at the specified dimensions.
     */
    fun drawableToMat(context: Context, drawableResId: Int, widthPx: Int, heightPx: Int): Mat? {
        init()
        val drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        bitmap.recycle()
        return mat
    }

    /**
     * Obtains the ChatGPT Copy icon Mat at the device's display density.
     */
    fun getChatGptCopyTemplateMat(context: Context, targetDpSize: Int = 24): Mat? {
        val density = context.resources.displayMetrics.density
        val sizePx = (targetDpSize * density).toInt().coerceAtLeast(16)
        return drawableToMat(context, R.drawable.ic_chatgpt_copy, sizePx, sizePx)
    }
}
