package com.avdsprint

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * Grabación en cámara lenta real para AVD Sprint.
 * Usa la sesión de alta velocidad de Camera2 (prefiere 240 fps a 1280×720, igual que AVD jump).
 * Si el teléfono no ofrece cámara lenta a otras apps, graba al máximo fps disponible y lo avisa.
 * La pantalla va en horizontal: el sprint se filma de costado. Una línea vertical arrastrable
 * ayuda a alinear la cámara con las picas o con la línea de la fotocélula.
 */
class RecordActivity : BaseActivity() {

    companion object {
        const val EX_FILE = "file"
        const val EX_FPS = "fps"
        private const val RQ_PERM = 7
    }

    override val darkSystemBars = true

    private var cfg: CamConfig? = null
    private lateinit var texture: AutoFitTextureView
    private lateinit var info: TextView
    private lateinit var clock: TextView
    private lateinit var recBtn: Button

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var recording = false
    private var starting = false
    private var outFile: File? = null
    private var startMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            clock.text = String.format(Locale.US, "●  %.1f s", (SystemClock.elapsedRealtime() - startMs) / 1000.0)
            main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = HighSpeed.find(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = AutoFitTextureView(this)
        root.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(GuideView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        clock = text("", 20f, 0xFFFFFFFF.toInt(), true).apply {
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(clock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(14))
            setBackgroundColor(0x99000000.toInt())
        }
        info = text(cfg?.describe() ?: "No se encontró una cámara trasera.", 13f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        bar.addView(info)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val cancel = button("Cancelar") { cancelAndFinish() }
        recBtn = button("●  Grabar", primary = true) { onRecordPressed() }
        row.addView(cancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        row.addView(recBtn, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(row)
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        cfg?.let { texture.setAspect(max(it.size.width, it.size.height), minOf(it.size.width, it.size.height)) }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                configureTransform(w, h)
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = configureTransform(w, h)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (cfg == null) {
            Toast.makeText(this, "Este teléfono no tiene una cámara trasera disponible.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        camThread = HandlerThread("camara").also { it.start() }
        camHandler = Handler(camThread!!.looper)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), RQ_PERM)
            return
        }
        if (texture.isAvailable) {
            configureTransform(texture.width, texture.height)
            openCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RQ_PERM) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable) { configureTransform(texture.width, texture.height); openCamera() }
        } else {
            Toast.makeText(this, "Sin permiso de cámara no se puede grabar.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        if (recording) haltRecording()?.delete()
        closeCamera()
        camThread?.quitSafely()
        camThread = null; camHandler = null
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (texture.isAvailable) configureTransform(texture.width, texture.height)
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_90 else windowManager.defaultDisplay.rotation

    /** Gira y escala la vista previa según cómo está sostenido el teléfono (método de Camera2Basic). */
    private fun configureTransform(viewW: Int, viewH: Int) {
        val c = cfg ?: return
        if (viewW == 0 || viewH == 0) return
        val rotation = displayRotation()
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val bufferRect = RectF(0f, 0f, c.size.height.toFloat(), c.size.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewH.toFloat() / c.size.height, viewW.toFloat() / c.size.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        texture.setTransform(matrix)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null) return
        val c = cfg ?: return
        val mgr = getSystemService(CameraManager::class.java) ?: return
        try {
            mgr.openCamera(c.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    main.post { startPreview() }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); camera = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); camera = null
                    toast("Error de cámara ($error). Cierra otras apps que usen la cámara.")
                    main.post { finish() }
                }
            }, camHandler)
        } catch (e: Exception) {
            toast("No se pudo abrir la cámara: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        releaseRecorder()
    }

    private fun startPreview() {
        val c = cfg ?: return
        val cam = camera ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(c.size.width, c.size.height)
        val surface = previewSurface ?: Surface(st).also { previewSurface = it }
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null) return
                    session = s
                    try {
                        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(surface)
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        s.setRepeatingRequest(b.build(), null, camHandler)
                    } catch (e: Exception) {
                        toast("Vista previa no disponible: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast("No se pudo iniciar la vista previa.")
                }
            }, camHandler)
        } catch (e: Exception) {
            toast("No se pudo iniciar la vista previa: ${e.message}")
        }
    }

    private fun onRecordPressed() {
        when {
            starting -> {}
            recording -> stopAndReturn()
            else -> startRecording()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyRecorder() = MediaRecorder()

    /** Orientación del vídeo para que quede derecho según cómo se sostiene el teléfono. */
    private fun orientationHint(c: CamConfig): Int {
        val deg = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (c.orientation - deg + 360) % 360
    }

    private fun buildRecorder(file: File): MediaRecorder? {
        val c = cfg ?: return null
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else legacyRecorder()
        return try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(file.absolutePath)
            val bitrate = (c.size.width.toLong() * c.size.height * c.fps / 8L).coerceIn(10_000_000L, 80_000_000L).toInt()
            r.setVideoEncodingBitRate(bitrate)
            r.setVideoFrameRate(c.fps)
            r.setVideoSize(c.size.width, c.size.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setOrientationHint(orientationHint(c))
            r.prepare()
            r
        } catch (e: Exception) {
            r.release()
            toast("El codificador no acepta ${c.fps} fps: ${e.message}")
            null
        }
    }

    private fun releaseRecorder() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    private fun startRecording() {
        val c = cfg ?: return
        val cam = camera ?: return
        val preview = previewSurface ?: return
        starting = true
        recBtn.isEnabled = false
        info.text = "Preparando cámara lenta…"

        try { session?.close() } catch (_: Exception) {}
        session = null

        val file = File(Videos.dir(this), "sprint_${System.currentTimeMillis()}.mp4")
        outFile = file
        val r = buildRecorder(file)
        if (r == null) {
            starting = false; recBtn.isEnabled = true
            info.text = c.describe()
            startPreview(); return
        }
        recorder = r
        val recSurface = r.surface

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(preview)
                    b.addTarget(recSurface)
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(c.fps, c.fps))
                    if (c.highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                        s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, camHandler)
                    } else {
                        s.setRepeatingRequest(b.build(), null, camHandler)
                    }
                    r.start()
                    main.postDelayed({ lockExposure(s, b) }, 600)
                    main.post {
                        recording = true; starting = false
                        startMs = SystemClock.elapsedRealtime()
                        recBtn.text = "■  Detener"
                        recBtn.isEnabled = true
                        info.text = "Grabando a ${c.fps} fps · pulsa Detener cuando termine la prueba"
                        main.post(tick)
                    }
                } catch (e: Exception) {
                    toast("No se pudo grabar: ${e.message}")
                    main.post { failBackToPreview() }
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                toast("La cámara rechazó la configuración de ${c.fps} fps.")
                main.post { failBackToPreview() }
            }
        }

        try {
            if (c.highSpeed) {
                @Suppress("DEPRECATION")
                cam.createConstrainedHighSpeedCaptureSession(listOf(preview, recSurface), callback, camHandler)
            } else {
                @Suppress("DEPRECATION")
                cam.createCaptureSession(listOf(preview, recSurface), callback, camHandler)
            }
        } catch (e: Exception) {
            toast("No se pudo iniciar la grabación: ${e.message}")
            failBackToPreview()
        }
    }

    /** Bloquea exposición y balance de blancos tras estabilizarse: la luz no cambia a mitad del sprint. */
    private fun lockExposure(s: CameraCaptureSession, b: CaptureRequest.Builder) {
        if (!recording || session !== s) return
        val c = cfg ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            if (c.highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, camHandler)
            } else {
                s.setRepeatingRequest(b.build(), null, camHandler)
            }
        } catch (_: Exception) {
            // Algunos teléfonos no aceptan el bloqueo en cámara lenta: se sigue sin él.
        }
    }

    private fun failBackToPreview() {
        starting = false
        recBtn.isEnabled = true
        recBtn.text = "●  Grabar"
        info.text = cfg?.describe() ?: ""
        releaseRecorder()
        outFile?.delete()
        try { session?.close() } catch (_: Exception) {}
        session = null
        startPreview()
    }

    /** Detiene el grabador y devuelve el vídeo si quedó bien guardado. */
    private fun haltRecording(): File? {
        recording = false
        main.removeCallbacks(tick)
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.abortCaptures() } catch (_: Exception) {}
        var ok = true
        try { recorder?.stop() } catch (_: RuntimeException) { ok = false }
        releaseRecorder()
        try { session?.close() } catch (_: Exception) {}
        session = null
        recBtn.text = "●  Grabar"
        clock.text = ""
        val f = outFile
        if (!ok || f == null || !f.exists() || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f
    }

    private fun stopAndReturn() {
        val c = cfg ?: return
        val f = haltRecording()
        if (f == null) {
            Toast.makeText(this, "La grabación fue demasiado corta. Intenta de nuevo.", Toast.LENGTH_LONG).show()
            info.text = c.describe()
            startPreview()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EX_FILE, f.absolutePath).putExtra(EX_FPS, c.fps))
        finish()
    }

    private fun cancelAndFinish() {
        if (recording) haltRecording()?.delete()
        setResult(RESULT_CANCELED)
        finish()
    }

    /** Línea vertical arrastrable para alinear la cámara con las picas o con la línea de la fotocélula. */
    private class GuideView(ctx: Context) : View(ctx) {
        private var frac = 0.5f
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B3B.toInt(); strokeWidth = 5f }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 34f; setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width * frac
            canvas.drawLine(x, 0f, x, height.toFloat(), line)
            canvas.drawText("Línea de referencia · arrástrala", x + 14f, height * 0.12f, label)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // Solo se arrastra si se toca cerca de la línea; el resto de la pantalla deja pasar los toques.
            val x = width * frac
            if (e.actionMasked == MotionEvent.ACTION_DOWN && kotlin.math.abs(e.x - x) > 90f) return false
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                frac = (e.x / width).coerceIn(0.02f, 0.98f)
                invalidate()
            }
            return true
        }
    }
}
