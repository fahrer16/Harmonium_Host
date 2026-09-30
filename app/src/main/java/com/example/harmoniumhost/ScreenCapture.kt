package com.example.harmoniumhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screenshots of whatever is on screen, any app, through MediaProjection. Android asks once per
 * app start ("Start now"; ticking "Don't show again" makes later asks silent). The projection is
 * kept but idle between screenshots: a virtual display exists only for the moment of a capture.
 * Without it, screenshots fall back to drawing this app's own window.
 */
object ScreenCapture {

    private const val TAG = "HarmoniumHost"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var projection: MediaProjection? = null
    /** Run once the user has answered Android's dialog (e.g. the screenshot that asked for it). */
    @Volatile private var afterConsent: (() -> Unit)? = null

    val ready get() = projection != null

    /** Shows Android's capture dialog (silent if "Don't show again" was ticked), then runs [then]. */
    fun requestConsent(context: Context, then: (() -> Unit)? = null) {
        afterConsent = then
        context.startActivity(Intent(context, CaptureConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun consentAnswered(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mgr.getMediaProjection(resultCode, data)?.let { set(it) }
            HostApp.of(context).prefs.captureGranted = true
        } else {
            Log.i(TAG, "screen capture declined")
        }
        val then = afterConsent
        afterConsent = null
        // Let whatever was on screen come back in front of the dialog before capturing it.
        then?.let { main.postDelayed({ Thread { it() }.start() }, 700) }
    }

    private fun set(p: MediaProjection) {
        projection?.stop()
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === p) projection = null
                Log.i(TAG, "screen capture stopped")
            }
        }, main)
        Log.i(TAG, "screen capture ready")
    }

    /** One JPEG of the whole screen, or null (no consent, screen off, timeout). Call off the main thread. */
    fun capture(context: Context): ByteArray? {
        val p = projection ?: return null
        val m = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val thread = HandlerThread("screen-capture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val done = CountDownLatch(1)
        var jpeg: ByteArray? = null
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.let { img ->
                    try {
                        if (done.count > 0) { jpeg = encode(img, w, h); done.countDown() }
                    } finally {
                        img.close()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "screen capture frame: $e")
            }
        }, handler)
        var display: VirtualDisplay? = null
        try {
            display = p.createVirtualDisplay("harmonium-screenshot", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
            done.await(2, TimeUnit.SECONDS)          // no frame while the screen is off
        } catch (e: Exception) {
            Log.w(TAG, "screen capture failed: $e")
        } finally {
            display?.release()
            handler.post { reader.close() }          // on the thread that receives its frames
            thread.quitSafely()
        }
        return jpeg
    }

    private fun encode(img: Image, w: Int, h: Int): ByteArray {
        val plane = img.planes[0]
        // Rows can be padded: copy the padded width, then crop.
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val shot = if (bmp.width != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
        val out = ByteArrayOutputStream()
        shot.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (shot !== bmp) shot.recycle()
        bmp.recycle()
        return out.toByteArray()
    }
}

/**
 * Invisible: shows Android's "start capturing" dialog and hands the answer to [ScreenCapture].
 * Runs in its own task, so whatever app was in front returns when it finishes.
 */
class CaptureConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mgr.createScreenCaptureIntent(), 1)
        } catch (e: Exception) {
            Log.w("HarmoniumHost", "can't ask for screen capture: $e")
            ScreenCapture.consentAnswered(this, RESULT_CANCELED, null)
            finish()
        }
    }

    @Deprecated("Activity result API needs androidx.activity; this is a one-off platform dialog")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        ScreenCapture.consentAnswered(this, resultCode, data)
        finish()
    }
}
