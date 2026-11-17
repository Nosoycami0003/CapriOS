package com.endrunners.capri

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ScreenCapture {

    private var resultCode: Int = Activity.RESULT_CANCELED
    private var resultData: Intent? = null
    private var projection: MediaProjection? = null

    fun guardarPermiso(code: Int, data: Intent) {
        resultCode = code
        resultData = data
        projection = null
    }

    fun tienePermiso(): Boolean = resultData != null

    private fun obtenerProjection(context: Context): MediaProjection? {
        projection?.let { return it }
        val data = resultData ?: return null
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(resultCode, data)
        projection = p
        return p
    }

    fun capturarComoBase64(context: Context): String? {
        val proj = obtenerProjection(context) ?: return null

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        val handler = Handler(Looper.getMainLooper())

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                bitmap = Bitmap.createBitmap(bmp, 0, 0, width, height)
                image.close()
            }
            latch.countDown()
        }, handler)

        val virtualDisplay = proj.createVirtualDisplay(
            "CapriCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )

        latch.await(3, TimeUnit.SECONDS)
        virtualDisplay?.release()
        imageReader.close()

        val bmpFinal = bitmap ?: return null
        val out = ByteArrayOutputStream()
        bmpFinal.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
