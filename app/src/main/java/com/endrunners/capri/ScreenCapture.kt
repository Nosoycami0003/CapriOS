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

    private const val MAX_SIDE = 1280

    private var resultCode: Int = Activity.RESULT_CANCELED
    private var resultData: Intent? = null
    private var projection: MediaProjection? = null

    fun guardarPermiso(code: Int, data: Intent) {
        liberar()
        resultCode = code
        resultData = data
    }

    fun tienePermiso(): Boolean = resultData != null

    /** Detiene la proyección y olvida el permiso (en Android 14+ el token es de un solo uso). */
    fun liberar() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        resultData = null
        resultCode = Activity.RESULT_CANCELED
    }

    private fun obtenerProjection(context: Context): MediaProjection? {
        projection?.let { return it }
        val data = resultData ?: return null
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p: MediaProjection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            // Token ya usado o el servicio aún no corre con el tipo mediaProjection
            resultData = null
            return null
        }

        // Android 14+: obligatorio registrar un callback antes de createVirtualDisplay
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                resultData = null
            }
        }, Handler(Looper.getMainLooper()))
        projection = p
        return p
    }

    /** Llamar SIEMPRE desde un hilo de fondo (bloquea hasta 3 s). */
    fun capturarComoBase64(context: Context): String? {
        val proj = obtenerProjection(context) ?: return null

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val latch = CountDownLatch(1)
        val resultado = arrayOfNulls<Bitmap>(1)
        val handler = Handler(Looper.getMainLooper())

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * width
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                resultado[0] = Bitmap.createBitmap(bmp, 0, 0, width, height)
                bmp.recycle()
            } finally {
                image.close()
            }
            latch.countDown()
        }, handler)

        val virtualDisplay = try {
            proj.createVirtualDisplay(
                "CapriCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, handler
            )
        } catch (e: Exception) {
            imageReader.close()
            return null
        }

        latch.await(3, TimeUnit.SECONDS)
        virtualDisplay?.release()
        imageReader.close()

        val original = resultado[0] ?: return null
        val escala = MAX_SIDE.toFloat() / maxOf(original.width, original.height)
        val bmpFinal = if (escala < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * escala).toInt(),
                (original.height * escala).toInt(),
                true
            )
        } else original

        val out = ByteArrayOutputStream()
        bmpFinal.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
