package com.example.procam

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs

data class VChoice(val label: String, val quality: Quality, val fps: Int, val bitrate: Int)

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var exposure: SeekBar
    private lateinit var zoom: SeekBar
    private lateinit var focus: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var thumb: ImageView
    private lateinit var ring: View
    private lateinit var btnCapture: Button
    private lateinit var btnMode: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnLock: Button
    private lateinit var btnEnhance: Button
    private lateinit var btnQ: Button
    private lateinit var btnVfmt: Button
    private lateinit var btnMic: Button
    private lateinit var btnDenoise: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cleanAudio: CleanAudio? = null

    private var lens = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var flashIdx = 0
    private var filterIdx = 0
    private var afIdx = 0            // 0 Auto, 1 Macro, 2 Manual
    private var denoiseLevel = 1     // 0 off, 1 medium, 2 high
    private var enhanceOn = true
    private var qualityBest = true
    private var micMode = 0          // 0 clean, 1 standard, 2 off
    private var micMuted = false
    private var locked = false
    private var aeLockOn = false
    private var focusDiopter = 0f
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"

    private var vChoices: List<VChoice> = emptyList()
    private var vIdx = 0

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var startEv = 0
    private var dragging = false
    private var lastTapX = -1f
    private var lastTapY = -1f
    private var lastTapTime = 0L

    private val flashNames = listOf("OFF", "AUTO", "ON", "TORCH")
    private val afNames = listOf("Auto", "Macro", "Manual")
    private val dnNames = listOf("OFF", "MED", "HIGH")
    private val io = Executors.newSingleThreadExecutor()
    private val proc = Executors.newSingleThreadExecutor()
    private val hideRing = Runnable { ring.visibility = View.GONE }
    private val relock = Runnable { if (locked) { aeLockOn = true; applyCamera2Options() } }

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
    private fun scale(r: Float, g: Float, b: Float) =
        ColorMatrix(floatArrayOf(r,0f,0f,0f,0f, 0f,g,0f,0f,0f, 0f,0f,b,0f,0f, 0f,0f,0f,1f,0f))

    private val filters: List<Pair<String, ColorMatrix>> by lazy {
        listOf(
            "Normal" to ColorMatrix(),
            "B&W" to sat(0f),
            "Sepia" to sat(0f).apply { postConcat(scale(1.07f, 0.74f, 0.43f)) },
            "Warm" to scale(1.12f, 1.0f, 0.85f),
            "Cool" to scale(0.88f, 1.0f, 1.14f),
            "Vivid" to sat(1.7f),
            "Faded" to ColorMatrix(floatArrayOf(
                0.85f,0f,0f,0f,30f, 0f,0.85f,0f,0f,30f, 0f,0f,0.85f,0f,30f, 0f,0f,0f,1f,0f)).apply { preConcat(sat(0.7f)) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER   // what you see is what gets saved
        info = findViewById(R.id.info)
        exposure = findViewById(R.id.exposure)
        zoom = findViewById(R.id.zoom)
        focus = findViewById(R.id.focus)
        focusLabel = findViewById(R.id.focusLabel)
        thumb = findViewById(R.id.thumb)
        ring = findViewById(R.id.focusRing)
        btnCapture = findViewById(R.id.btnCapture)
        btnMode = findViewById(R.id.btnMode)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnLock = findViewById(R.id.btnLock)
        btnEnhance = findViewById(R.id.btnEnhance)
        btnQ = findViewById(R.id.btnQ)
        btnVfmt = findViewById(R.id.btnVfmt)
        btnMic = findViewById(R.id.btnMic)
        btnDenoise = findViewById(R.id.btnNr)

        ring.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((3 * resources.displayMetrics.density).toInt(), Color.YELLOW)
        }

        findViewById<Button>(R.id.btnFlash).setOnClickListener { b ->
            flashIdx = (flashIdx + 1) % flashNames.size
            (b as Button).text = "Flash: ${flashNames[flashIdx]}"
            applyFlash()
        }
        findViewById<Button>(R.id.btnFilter).setOnClickListener { b ->
            filterIdx = (filterIdx + 1) % filters.size
            (b as Button).text = filters[filterIdx].first
            applyPreviewFilter()
        }
        btnLock.setOnClickListener {
            if (locked) setLock(false, 0f, 0f)
            else setLock(true, previewView.width / 2f, previewView.height / 2f)
        }
        findViewById<Button>(R.id.btnAf).setOnClickListener { b ->
            val next = (afIdx + 1) % afNames.size
            if (next == 2 && minFocusDistance() <= 0f) {
                toast("Manual focus isn't supported on this camera")
                afIdx = 0
            } else afIdx = next
            (b as Button).text = "AF: ${afNames[afIdx]}"
            val manual = afIdx == 2
            focus.visibility = if (manual) View.VISIBLE else View.GONE
            focusLabel.visibility = if (manual) View.VISIBLE else View.GONE
            applyCamera2Options()
        }
        btnDenoise.setOnClickListener {
            denoiseLevel = (denoiseLevel + 1) % dnNames.size
            btnDenoise.text = "Denoise: ${dnNames[denoiseLevel]}"
            applyCamera2Options()
        }
        btnEnhance.setOnClickListener {
            enhanceOn = !enhanceOn
            btnEnhance.text = "Enhance: ${if (enhanceOn) "ON" else "OFF"}"
        }
        btnQ.setOnClickListener {
            qualityBest = !qualityBest
            btnQ.text = "Photo: ${if (qualityBest) "BEST" else "FAST"}"
            startCamera()
        }
        btnVfmt.setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (vChoices.size > 1) vIdx = (vIdx + 1) % vChoices.size
            startCamera()
        }
        btnMic.setOnClickListener {
            if (recording != null) {
                micMuted = !micMuted
                cleanAudio?.muted = micMuted
                try { recording?.mute(micMuted) } catch (e: Exception) {}
            } else {
                micMode = (micMode + 1) % 3
            }
            updateMicLabel()
        }
        btnSwitch.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                   else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        btnMode.setOnClickListener {
            if (recording != null) return@setOnClickListener
            videoMode = !videoMode
            btnMode.text = if (videoMode) "VIDEO" else "PHOTO"
            btnCapture.text = if (videoMode) "⏺" else "●"
            info.text = ""
            startCamera()
        }
        btnCapture.setOnClickListener { if (videoMode) toggleRecording() else capture() }
        thumb.setOnClickListener { openLast() }

        zoom.setOnSeekBarChangeListener(simpleListener { p -> camera?.cameraControl?.setLinearZoom(p / 100f) })
        exposure.setOnSeekBarChangeListener(simpleListener { p ->
            val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return@simpleListener
            setEv(range.lower + p)
        })
        focus.setOnSeekBarChangeListener(simpleListener { p ->
            focusDiopter = minFocusDistance() * (p / 100f)
            applyCamera2Options()
        })

        // Tap = focus. Long press = lock focus + exposure. Drag up/down = exposure.
        previewView.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; downTime = e.eventTime; dragging = false
                    startEv = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = downY - e.y
                    if (dragging || abs(dy) > 40f) {
                        dragging = true
                        val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                        if (range != null) setEv((startEv + (dy / 45f).toInt()).coerceIn(range.lower, range.upper))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        val isLong = e.eventTime - downTime > 500
                        if (locked && !isLong) setLock(false, 0f, 0f)
                        lastTapX = e.x; lastTapY = e.y; lastTapTime = System.currentTimeMillis()
                        if (isLong) setLock(true, e.x, e.y) else tapFocus(e.x, e.y)
                    }
                    v.performClick()
                }
            }
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else toast("Camera permission needed")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateMicLabel() {
        btnMic.text = if (recording != null) "Mic: ${if (micMuted) "MUTED" else "LIVE"}"
        else "Mic: ${listOf("CLEAN", "STD", "OFF")[micMode]}"
    }

    private fun simpleListener(cb: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) cb(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ---------- tap focus, exposure, lock ----------
    private fun showRing(x: Float, y: Float, lock: Boolean) {
        val d = 72 * resources.displayMetrics.density
        (ring.background as GradientDrawable).setStroke(
            (3 * resources.displayMetrics.density).toInt(), if (lock) 0xFFFF9500.toInt() else Color.YELLOW)
        ring.x = x - d / 2
        ring.y = y - d / 2
        ring.scaleX = 1.3f
        ring.scaleY = 1.3f
        ring.visibility = View.VISIBLE
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        ring.removeCallbacks(hideRing)
        if (!lock) ring.postDelayed(hideRing, 1500)
    }

    private fun startFocus(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        if (afIdx == 2) return
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (lock) b.disableAutoCancel()
        cam.cameraControl.startFocusAndMetering(b.build())
    }

    private fun tapFocus(x: Float, y: Float) {
        startFocus(x, y, false)
        showRing(x, y, false)
    }

    private fun setLock(on: Boolean, x: Float, y: Float) {
        val cam = camera ?: return
        if (on) {
            startFocus(x, y, true)
            locked = true
            showRing(x, y, true)
            btnLock.text = "AE/AF Lock: ON"
            if (recording == null) info.text = "AE/AF LOCKED (tap to unlock)"
            previewView.removeCallbacks(relock)
            previewView.postDelayed(relock, 1200)
        } else {
            locked = false
            aeLockOn = false
            previewView.removeCallbacks(relock)
            cam.cameraControl.cancelFocusAndMetering()
            applyCamera2Options()
            ring.removeCallbacks(hideRing)
            ring.visibility = View.GONE
            btnLock.text = "AE/AF Lock: OFF"
            if (recording == null) info.text = ""
        }
    }

    private fun setEv(idx: Int) {
        val cam = camera ?: return
        cam.cameraControl.setExposureCompensationIndex(idx)
        cam.cameraInfo.exposureState.exposureCompensationRange.let { exposure.progress = idx - it.lower }
        if (recording == null && !locked) info.text = "EV $idx"
        if (locked) {
            aeLockOn = false
            applyCamera2Options()
            previewView.removeCallbacks(relock)
            previewView.postDelayed(relock, 900)
        }
    }

    // ---------- camera setup ----------
    private fun buildVideoChoices(provider: ProcessCameraProvider, selector: CameraSelector) {
        val prev = vChoices.getOrNull(vIdx)?.label
        val camInfo = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return
        val supported = QualitySelector.getSupportedQualities(camInfo)
        val ranges = Camera2CameraInfo.from(camInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val has60 = ranges?.any { it.upper >= 60 } == true
        val all = listOf(
            VChoice("1080p 30fps", Quality.FHD, 30, 16_000_000),
            VChoice("1080p 60fps", Quality.FHD, 60, 24_000_000),
            VChoice("720p 30fps", Quality.HD, 30, 10_000_000),
            VChoice("720p 60fps", Quality.HD, 60, 14_000_000),
            VChoice("4K 30fps", Quality.UHD, 30, 40_000_000),
            VChoice("480p 30fps", Quality.SD, 30, 4_000_000)
        )
        vChoices = all.filter { it.quality in supported && (it.fps == 30 || has60) }
        vIdx = vChoices.indexOfFirst { it.label == prev }.let { if (it >= 0) it else 0 }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(if (videoMode) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewSelector).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            provider.unbindAll()
            locked = false
            aeLockOn = false
            btnLock.text = "AE/AF Lock: OFF"
            ring.visibility = View.GONE
            try {
                if (videoMode) {
                    buildVideoChoices(provider, selector)
                    val ch = vChoices.getOrNull(vIdx)
                    val qs = if (ch != null)
                        QualitySelector.from(ch.quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    else QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    val rb = Recorder.Builder().setQualitySelector(qs)
                    if (ch != null) rb.setTargetVideoEncodingBitRate(ch.bitrate)
                    val vcb = VideoCapture.Builder(rb.build())
                    if (ch != null && ch.fps == 60) vcb.setTargetFrameRate(Range(60, 60))
                    val vc = vcb.build()
                    videoCapture = vc
                    imageCapture = null
                    camera = provider.bindToLifecycle(this, selector, preview, vc)
                } else {
                    // 4:3, up to ~12 MP (a full 50-64 MP frame would run out of memory), full quality pipeline
                    val rs = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                        .build()
                    val ic = ImageCapture.Builder()
                        .setCaptureMode(if (qualityBest) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                                        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(rs)
                        .build()
                    imageCapture = ic
                    videoCapture = null
                    camera = provider.bindToLifecycle(this, selector, preview, ic)
                }
            } catch (e: Exception) {
                val ch = vChoices.getOrNull(vIdx)
                if (videoMode && ch != null && ch.fps == 60) {
                    val alt = vChoices.indexOfFirst { it.quality == ch.quality && it.fps == 30 }
                    if (alt >= 0) {
                        vIdx = alt
                        toast("60fps isn't available with these settings, using 30fps")
                        startCamera()
                        return@addListener
                    }
                }
                toast("Couldn't start camera: ${e.message}")
                return@addListener
            }
            camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let {
                exposure.max = it.upper - it.lower
                exposure.progress = -it.lower
            }
            zoom.progress = 0
            updateModeUi()
            applyFlash()
            applyPreviewFilter()
            applyCamera2Options()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateModeUi() {
        val v = if (videoMode) View.VISIBLE else View.GONE
        val p = if (videoMode) View.GONE else View.VISIBLE
        btnVfmt.visibility = v
        btnMic.visibility = v
        btnEnhance.visibility = p
        btnQ.visibility = p
        btnVfmt.text = "Video: ${vChoices.getOrNull(vIdx)?.label ?: "auto"}"
        updateMicLabel()
    }

    private fun applyFlash() {
        val torch = flashIdx == 3 || (videoMode && flashIdx == 2)
        camera?.cameraControl?.enableTorch(torch)
        imageCapture?.flashMode = when (flashIdx) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun applyPreviewFilter() {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(filters[filterIdx].second) }
        previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    // ---------- camera2 extras: in-camera noise reduction, focus, exposure lock ----------
    private fun <T> chr(key: CameraCharacteristics.Key<T>): T? =
        camera?.let { Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(key) }

    private fun minFocusDistance(): Float = chr(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    private fun supportsNr() = chr(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        ?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
    private fun supportsAeLock() = chr(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true

    private fun applyCamera2Options() {
        val cam = camera ?: return
        try {
            val b = CaptureRequestOptions.Builder()
            if (denoiseLevel > 0 && supportsNr()) {
                b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            if (aeLockOn && supportsAeLock()) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            when (afIdx) {
                1 -> b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
                2 -> {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
                }
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            toast("Setting not applied: ${e.message}")
        }
    }

    // ---------- photo processing ----------
    // Auto-levels + a little color, then the chosen filter. Applied while drawing, so it costs almost nothing.
    private fun buildMatrix(src: Bitmap, enh: Boolean, fIdx: Int): ColorMatrix {
        val cm = ColorMatrix()
        if (enh) {
            val sw = 96
            val sh = 72
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            small.recycle()
            val hist = IntArray(256)
            for (p in px) {
                val l = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                hist[l]++
            }
            val total = px.size
            var acc = 0
            var lo = 0
            while (lo < 255 && acc + hist[lo] < total * 0.01) { acc += hist[lo]; lo++ }
            acc = 0
            var hi = 255
            while (hi > 0 && acc + hist[hi] < total * 0.01) { acc += hist[hi]; hi-- }
            lo = minOf(lo, 35)
            hi = maxOf(hi, 220)
            val s = 255f / (hi - lo)
            val o = -lo * s
            cm.postConcat(ColorMatrix(floatArrayOf(s,0f,0f,0f,o, 0f,s,0f,0f,o, 0f,0f,s,0f,o, 0f,0f,0f,1f,0f)))
            cm.postConcat(sat(1.18f))
        }
        cm.postConcat(filters[fIdx].second)
        return cm
    }

    private fun luma(c: Int) = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000

    /**
     * One pass over the photo, row by row (low memory):
     *  - denoise: averages only neighbouring pixels of similar brightness (keeps edges), and smooths
     *    color blotches/speckle using a heavily blurred copy, but not across real color edges
     *  - enhance: local contrast ("clarity") and lifted shadows
     */
    private fun processPhoto(bmp: Bitmap, dn: Int, enhance: Boolean) {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16 || (dn == 0 && !enhance)) return
        var t = bmp
        repeat(3) {
            val n = Bitmap.createScaledBitmap(t, maxOf(t.width / 2, 2), maxOf(t.height / 2, 2), true)
            if (t !== bmp) t.recycle()
            t = n
        }
        val blur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(blur).drawBitmap(t, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        t.recycle()

        val thr = if (dn >= 2) 30 else 18
        val cw = if (dn >= 2) 0.92f else 0.75f
        val clarity = if (enhance) 0.5f else 0f
        val shadow = if (enhance) 0.15f else 0f

        var pxCur = IntArray(w)
        var pxNext = IntArray(w)
        var yPrev = IntArray(w)
        var yCur = IntArray(w)
        var yNext = IntArray(w)
        val blurRow = IntArray(w)
        val res = IntArray(w)
        bmp.getPixels(pxCur, 0, w, 0, 0, w, 1)
        for (x in 0 until w) yCur[x] = luma(pxCur[x])
        System.arraycopy(yCur, 0, yPrev, 0, w)

        for (y in 0 until h) {
            if (y + 1 < h) bmp.getPixels(pxNext, 0, w, 0, y + 1, w, 1)
            else System.arraycopy(pxCur, 0, pxNext, 0, w)
            for (x in 0 until w) yNext[x] = luma(pxNext[x])
            blur.getPixels(blurRow, 0, w, 0, y, w, 1)

            for (x in 0 until w) {
                val c = pxCur[x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val yc = yCur[x]
                var yd = yc
                if (dn > 0 && x > 0 && x < w - 1) {
                    var sum = yc
                    var cnt = 1
                    var v = yPrev[x - 1]; if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yPrev[x];     if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yPrev[x + 1]; if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yCur[x - 1];  if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yCur[x + 1];  if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yNext[x - 1]; if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yNext[x];     if (abs(v - yc) < thr) { sum += v; cnt++ }
                    v = yNext[x + 1]; if (abs(v - yc) < thr) { sum += v; cnt++ }
                    yd = sum / cnt
                }
                val bp = blurRow[x]
                val br = (bp shr 16) and 255
                val bg = (bp shr 8) and 255
                val bb = bp and 255
                val ybl = luma(bp)
                var cr = (r - yc).toFloat()
                var cg = (g - yc).toFloat()
                var cb = (b - yc).toFloat()
                if (dn > 0) {
                    val dr = (br - ybl).toFloat()
                    val dg = (bg - ybl).toFloat()
                    val db = (bb - ybl).toFloat()
                    val e = abs(dr - cr) + abs(dg - cg) + abs(db - cb)
                    val wgt = cw * (1f - ((e - 24f) / 60f).coerceIn(0f, 1f))
                    cr += wgt * (dr - cr)
                    cg += wgt * (dg - cg)
                    cb += wgt * (db - cb)
                }
                var yo = yd.toFloat()
                if (enhance) {
                    yo += clarity * (yd - ybl)
                    if (yo < 90f) yo += (90f - yo) * shadow
                }
                val nr = (yo + cr).toInt().coerceIn(0, 255)
                val ng = (yo + cg).toInt().coerceIn(0, 255)
                val nb = (yo + cb).toInt().coerceIn(0, 255)
                res[x] = (c and (0xFF shl 24)) or (nr shl 16) or (ng shl 8) or nb
            }
            bmp.setPixels(res, 0, w, 0, y, w, 1)
            val ty = yPrev; yPrev = yCur; yCur = yNext; yNext = ty
            val tp = pxCur; pxCur = pxNext; pxNext = tp
        }
        blur.recycle()
    }

    private fun sharpenInPlace(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        val a = (amount * 256).toInt()
        var cur = IntArray(w)
        bmp.getPixels(cur, 0, w, 0, 0, w, 1)
        var prev = cur.copyOf()
        var next = IntArray(w)
        val res = IntArray(w)
        for (y in 0 until h) {
            if (y + 1 < h) bmp.getPixels(next, 0, w, 0, y + 1, w, 1)
            else System.arraycopy(cur, 0, next, 0, w)
            for (x in 0 until w) {
                val c0 = cur[x]
                if (x == 0 || x == w - 1) { res[x] = c0; continue }
                val l = cur[x - 1]
                val r = cur[x + 1]
                val u = prev[x]
                val d = next[x]
                var outPx = c0 and (0xFF shl 24)
                var shift = 0
                while (shift <= 16) {
                    val cc = (c0 shr shift) and 255
                    val sum = ((l shr shift) and 255) + ((r shr shift) and 255) +
                              ((u shr shift) and 255) + ((d shr shift) and 255)
                    var v = cc + (a * (4 * cc - sum)) / 256
                    if (v < 0) v = 0 else if (v > 255) v = 255
                    outPx = outPx or (v shl shift)
                    shift += 8
                }
                res[x] = outPx
            }
            bmp.setPixels(res, 0, w, 0, y, w, 1)
            val t = prev
            prev = cur
            cur = next
            next = t
        }
    }

    // ---------- photo capture ----------
    // Focus first (on your last tap, or the center), wait for it, then shoot - this is what keeps photos sharp.
    private fun capture() {
        val cam = camera
        if (cam == null || afIdx == 2 || locked) { doCapture(); return }
        var fired = false
        val fire = Runnable { if (!fired) { fired = true; doCapture() } }
        try {
            val fresh = lastTapX >= 0f && System.currentTimeMillis() - lastTapTime < 4000
            val px = if (fresh) lastTapX else previewView.width / 2f
            val py = if (fresh) lastTapY else previewView.height / 2f
            val pt = previewView.meteringPointFactory.createPoint(px, py)
            info.text = "Focusing…"
            val fut = cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF).build())
            fut.addListener(fire, ContextCompat.getMainExecutor(this))
            previewView.postDelayed(fire, 2000)
        } catch (e: Exception) { doCapture() }
    }

    private fun doCapture() {
        val ic = imageCapture ?: return
        if (!locked) info.text = ""
        val dn = denoiseLevel
        val enh = enhanceOn
        val fIdx = filterIdx
        if (dn > 0 || enh) toast("Processing photo…")
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val src = try { image.toBitmap() } catch (t: Throwable) {
                    image.close()
                    runOnUiThread { toast("Couldn't read photo: ${t.message}") }
                    return
                }
                image.close()
                proc.execute { processAndSave(src, rot, dn, enh, fIdx) }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { toast("Capture failed: ${e.message}") }
            }
        })
    }

    private fun processAndSave(src: Bitmap, rot: Int, dn: Int, enh: Boolean, fIdx: Int) {
        try {
            val cm = buildMatrix(src, enh, fIdx)
            val swap = rot == 90 || rot == 270
            val ow = if (swap) src.height else src.width
            val oh = if (swap) src.width else src.height
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply {
                postRotate(rot.toFloat())
                when (rot) {
                    90 -> postTranslate(src.height.toFloat(), 0f)
                    180 -> postTranslate(src.width.toFloat(), src.height.toFloat())
                    270 -> postTranslate(0f, src.width.toFloat())
                }
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            })
            src.recycle()
            processPhoto(out, dn, enh)
            if (enh) sharpenInPlace(out, 0.5f)
            val uri = savePhoto(out)
            val th = Bitmap.createScaledBitmap(out, 160, (160f * out.height / out.width).toInt().coerceAtLeast(1), true)
            out.recycle()
            runOnUiThread { showThumb(th, uri, "image/jpeg"); toast("Saved to Pictures/ProCam") }
        } catch (t: Throwable) {
            runOnUiThread { toast("Couldn't process photo: ${t.message}") }
        }
    }

    private fun savePhoto(bmp: Bitmap): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ProCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.JPEG, 97, s) } }
        return uri
    }

    // ---------- video ----------
    private fun toggleRecording() {
        val active = recording
        if (active != null) { active.stop(); return }
        val vc = videoCapture ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ProCam_${System.currentTimeMillis()}")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()

        val audioPerm = hasAudioPermission()
        var clean: CleanAudio? = null
        if (micMode == 0 && audioPerm) {
            val ca = CleanAudio()
            if (ca.start()) clean = ca else toast("Clean audio unavailable, using standard mic")
        }
        var pending = vc.output.prepareRecording(this, opts)
        val stdAudio = audioPerm && (micMode == 1 || (micMode == 0 && clean == null))
        if (stdAudio) pending = pending.withAudioEnabled()
        if (micMode != 2 && !audioPerm) toast("No mic permission - recording without sound")
        cleanAudio = clean
        micMuted = false

        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    cleanAudio?.let { it.offsetUs = (SystemClock.elapsedRealtimeNanos() - it.startNs) / 1000 }
                    btnCapture.text = "■"
                    btnMode.isEnabled = false
                    btnSwitch.isEnabled = false
                    btnVfmt.isEnabled = false
                    updateMicLabel()
                }
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000
                    info.text = String.format("● REC %02d:%02d%s", s / 60, s % 60, if (micMuted) "  (muted)" else "")
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    btnCapture.text = "⏺"
                    btnMode.isEnabled = true
                    btnSwitch.isEnabled = true
                    btnVfmt.isEnabled = true
                    info.text = ""
                    micMuted = false
                    updateMicLabel()
                    val ca = cleanAudio
                    cleanAudio = null
                    if (!ev.hasError()) {
                        val raw = ev.outputResults.outputUri
                        if (ca != null) {
                            toast("Cleaning audio…")
                            proc.execute {
                                ca.stop()
                                val merged = try { remuxWithAudio(raw, ca) } catch (t: Throwable) { null }
                                runOnUiThread { finishVideo(merged ?: raw, merged == null) }
                            }
                        } else finishVideo(raw, false)
                    } else {
                        if (ca != null) proc.execute { ca.stop() }
                        toast("Recording error: ${ev.error}")
                    }
                }
            }
        }
        if (stdAudio) try { recording?.mute(false) } catch (e: Exception) {}
    }

    private fun finishVideo(uri: Uri, audioFailed: Boolean) {
        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try { bmp = contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (e: Exception) {}
        }
        showThumb(bmp, uri, "video/mp4")
        toast(if (audioFailed) "Saved, but clean audio failed (video may be silent)" else "Saved to Movies/ProCam")
    }

    // Put the cleaned audio into the recorded video (video stream is copied untouched, no re-encoding)
    private fun remuxWithAudio(raw: Uri, audio: CleanAudio): Uri? {
        val fmt = audio.format ?: return null
        val frames = synchronized(audio.frames) { audio.frames.toList() }
        if (frames.isEmpty()) return null
        val tmp = File(cacheDir, "mux_${System.currentTimeMillis()}.mp4")
        val ex = MediaExtractor()
        try {
            ex.setDataSource(this, raw, null)
            var vi = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) { vi = i; break }
            }
            if (vi < 0) return null
            val vf = ex.getTrackFormat(vi)
            var rot = 0
            try {
                val mr = MediaMetadataRetriever()
                mr.setDataSource(this, raw)
                rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                mr.release()
            } catch (e: Exception) {}

            val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux.setOrientationHint(rot)
            val vt = mux.addTrack(vf)
            val at = mux.addTrack(fmt)
            mux.start()
            ex.selectTrack(vi)

            val maxSz = if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val buf = ByteBuffer.allocate(maxOf(maxSz, 4 * 1024 * 1024))
            val bi = MediaCodec.BufferInfo()
            val off = audio.offsetUs
            var ai = 0
            var t0 = -1L
            var lastRel = 0L

            fun writeAudioUpTo(limit: Long) {
                while (ai < frames.size) {
                    val f = frames[ai]
                    val pts = f.pts - off
                    if (pts > limit) break
                    ai++
                    if (pts < 0) continue
                    bi.set(0, f.data.size, pts, 0)
                    mux.writeSampleData(at, ByteBuffer.wrap(f.data), bi)
                }
            }

            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t0 < 0) t0 = t
                val rel = t - t0
                writeAudioUpTo(rel)
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                bi.set(0, size, rel, flags)
                mux.writeSampleData(vt, buf, bi)
                lastRel = rel
                ex.advance()
            }
            writeAudioUpTo(lastRel + 100_000)
            mux.stop()
            mux.release()

            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "ProCam_${System.currentTimeMillis()}")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCam")
            }
            val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            contentResolver.openOutputStream(out)?.use { os -> tmp.inputStream().use { it.copyTo(os) } }
            contentResolver.delete(raw, null, null)
            return out
        } finally {
            try { ex.release() } catch (e: Exception) {}
            tmp.delete()
        }
    }

    // ---------- preview of last shot ----------
    private fun showThumb(bmp: Bitmap?, uri: Uri?, mime: String) {
        lastUri = uri
        lastMime = mime
        if (bmp != null) thumb.setImageBitmap(bmp) else thumb.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun openLast() {
        val uri = lastUri ?: run { toast("Nothing captured yet"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) { toast("No gallery app found") }
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        cleanAudio?.let { ca -> proc.execute { ca.stop() } }
        io.shutdown()
        proc.shutdown()
    }
}
