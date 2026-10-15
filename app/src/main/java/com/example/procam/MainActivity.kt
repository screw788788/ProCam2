package com.example.procam

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var exposure: SeekBar
    private lateinit var zoom: SeekBar
    private lateinit var focus: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var thumb: ImageView
    private lateinit var btnCapture: Button
    private lateinit var btnMode: Button
    private lateinit var btnSwitch: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lens = CameraSelector.LENS_FACING_BACK
    private var videoMode = false
    private var flashIdx = 0
    private var filterIdx = 0
    private var afIdx = 0            // 0 Auto, 1 Macro, 2 Manual
    private var nrOn = false
    private var stabOn = false
    private var focusDiopter = 0f
    private var lastUri: Uri? = null
    private var lastMime = "image/jpeg"

    private val flashNames = listOf("OFF", "AUTO", "ON", "TORCH")
    private val afNames = listOf("Auto", "Macro", "Manual")
    private val io = Executors.newSingleThreadExecutor()

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
        info = findViewById(R.id.info)
        exposure = findViewById(R.id.exposure)
        zoom = findViewById(R.id.zoom)
        focus = findViewById(R.id.focus)
        focusLabel = findViewById(R.id.focusLabel)
        thumb = findViewById(R.id.thumb)
        btnCapture = findViewById(R.id.btnCapture)
        btnMode = findViewById(R.id.btnMode)
        btnSwitch = findViewById(R.id.btnSwitch)

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
        findViewById<Button>(R.id.btnNr).setOnClickListener { b ->
            if (!nrOn && !supportsNr()) { toast("Noise reduction control isn't supported on this camera"); return@setOnClickListener }
            nrOn = !nrOn
            (b as Button).text = "Denoise: ${if (nrOn) "ON" else "OFF"}"
            applyCamera2Options()
        }
        findViewById<Button>(R.id.btnStab).setOnClickListener { b ->
            if (!stabOn && !supportsOis() && !supportsEis()) { toast("Stabilization isn't supported on this camera"); return@setOnClickListener }
            stabOn = !stabOn
            (b as Button).text = "Stabilize: ${if (stabOn) "ON" else "OFF"}"
            if (stabOn && videoMode && !supportsEis()) toast("Video stabilization: optical only")
            applyCamera2Options()
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
            val idx = range.lower + p
            camera?.cameraControl?.setExposureCompensationIndex(idx)
            if (recording == null) info.text = "EV $idx"
        })
        focus.setOnSeekBarChangeListener(simpleListener { p ->
            focusDiopter = minFocusDistance() * (p / 100f)
            applyCamera2Options()
        })

        previewView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP && afIdx != 2) {
                val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                v.performClick()
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

    private fun simpleListener(cb: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) cb(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    // ---------- camera setup ----------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            provider.unbindAll()
            try {
                if (videoMode) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.fromOrderedList(
                            listOf(Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build()
                    val vc = VideoCapture.withOutput(recorder)
                    videoCapture = vc
                    imageCapture = null
                    camera = provider.bindToLifecycle(this, selector, preview, vc)
                } else {
                    val ic = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = ic
                    videoCapture = null
                    camera = provider.bindToLifecycle(this, selector, preview, ic)
                }
            } catch (e: Exception) {
                toast("Couldn't start camera: ${e.message}")
                return@addListener
            }
            camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let {
                exposure.max = it.upper - it.lower
                exposure.progress = -it.lower
            }
            zoom.progress = 0
            applyFlash()
            applyPreviewFilter()
            applyCamera2Options()
        }, ContextCompat.getMainExecutor(this))
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

    // ---------- camera2 extras: denoise, stabilize, focus ----------
    private fun <T> chr(key: CameraCharacteristics.Key<T>): T? =
        camera?.let { Camera2CameraInfo.from(it.cameraInfo).getCameraCharacteristic(key) }

    private fun minFocusDistance(): Float = chr(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    private fun supportsNr() = chr(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        ?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true
    private fun supportsOis() = chr(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
    private fun supportsEis() = chr(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

    private fun applyCamera2Options() {
        val cam = camera ?: return
        try {
            val b = CaptureRequestOptions.Builder()
            if (nrOn && supportsNr()) {
                b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            if (stabOn) {
                if (supportsOis())
                    b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                if (videoMode && supportsEis())
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
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

    // ---------- photo ----------
    private fun capture() {
        val ic = imageCapture ?: return
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                var bmp = image.toBitmap()
                image.close()
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(bmp, 0f, 0f,
                    Paint().apply { colorFilter = ColorMatrixColorFilter(filters[filterIdx].second) })
                val uri = savePhoto(out)
                val th = Bitmap.createScaledBitmap(out, 160, (160f * out.height / out.width).toInt().coerceAtLeast(1), true)
                runOnUiThread { showThumb(th, uri, "image/jpeg"); toast("Saved to Pictures/ProCam") }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { toast("Capture failed: ${e.message}") }
            }
        })
    }

    private fun savePhoto(bmp: Bitmap): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ProCam_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, s) } }
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
        var pending = vc.output.prepareRecording(this, opts)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pending = pending.withAudioEnabled()
        else toast("No mic permission - recording without sound")

        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    btnCapture.text = "■"
                    btnMode.isEnabled = false
                    btnSwitch.isEnabled = false
                }
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000
                    info.text = String.format("● REC %02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    btnCapture.text = "⏺"
                    btnMode.isEnabled = true
                    btnSwitch.isEnabled = true
                    info.text = ""
                    if (!ev.hasError()) {
                        val uri = ev.outputResults.outputUri
                        var bmp: Bitmap? = null
                        if (Build.VERSION.SDK_INT >= 29) {
                            try { bmp = contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (e: Exception) {}
                        }
                        showThumb(bmp, uri, "video/mp4")
                        toast("Saved to Movies/ProCam")
                    } else toast("Recording error: ${ev.error}")
                }
            }
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

    override fun onDestroy() { super.onDestroy(); recording?.stop(); io.shutdown() }
}
