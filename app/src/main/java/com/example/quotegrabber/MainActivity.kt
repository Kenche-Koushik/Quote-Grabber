package com.example.quotegrabber

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.quotegrabber.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Used during candidate selection
private data class ReadingCandidate(
    val number: String,
    val box: Rect?,
    val height: Int,
    var score: Int = 0
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer

    private val isScanning = AtomicBoolean(false)
    private var camera: Camera? = null
    private var isFlashlightOn = false

    // Framing + crop
    private var cropRect: Rect? = null
    private var analysisCropRect: Rect? = null

    // Voting & stability
    private val readingVotes = mutableMapOf<String, Int>()
    private var lastFrameReading: String? = null
    private var readingStabilityScore = 0

    // Tunables
    private val STABILITY_THRESHOLD = 3
    private val REQUIRED_VOTES_TO_WIN = 2
    private val SCAN_TIMEOUT_MS = 3000L

    private var scanStartTime = 0L
    private var lastValidBitmap: Bitmap? = null

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = REQUIRED_PERMISSIONS.all { perm -> permissions[perm] == true }
            if (!granted) {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ML Kit Text Recognizer (V1 Latin) — matches dependency
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        cameraExecutor = Executors.newSingleThreadExecutor()
        requestPermissions()

        binding.scanButton.setOnClickListener {
            // Fresh scan
            readingVotes.clear()
            lastValidBitmap?.recycle(); lastValidBitmap = null
            lastFrameReading = null
            readingStabilityScore = 0
            scanStartTime = System.currentTimeMillis()
            isScanning.set(true)
            Toast.makeText(this, "Scanning... Hold steady.", Toast.LENGTH_SHORT).show()
            setUiEnabled(false)
        }

        binding.flashlightButton.setOnClickListener { toggleFlashlight() }

        binding.scanAgainButton.setOnClickListener {
            readingVotes.clear()
            lastValidBitmap?.recycle(); lastValidBitmap = null
            isScanning.set(false)
            binding.resultsContainer.visibility = View.GONE
            binding.cameraUiContainer.visibility = View.VISIBLE
            setUiEnabled(true)
        }
    }

    private fun setUiEnabled(isEnabled: Boolean) {
        val alpha = if (isEnabled) 1f else 0.5f
        binding.scanButton.isEnabled = isEnabled
        binding.scanButton.alpha = alpha
        binding.zoomSlider.isEnabled = isEnabled
        binding.zoomSlider.alpha = alpha
        binding.flashlightButton.isEnabled = isEnabled && (camera?.cameraInfo?.hasFlashUnit() == true)
        binding.flashlightButton.alpha = alpha
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                binding.cameraPreview.post {
                    val viewPort = binding.cameraPreview.viewPort ?: run {
                        Log.e(TAG, "ViewPort is null, cannot bind camera")
                        return@post
                    }

                    val preview = Preview.Builder()
                        .setTargetRotation(binding.cameraPreview.display.rotation)
                        .build()
                        .also { it.setSurfaceProvider(binding.cameraPreview.surfaceProvider) }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetRotation(binding.cameraPreview.display.rotation)
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, ::analyzeImage) }

                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageAnalysis)
                        .setViewPort(viewPort)
                        .build()

                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup
                    )

                    setupCameraControls()
                    binding.framingGuide.post { updateCropRect() }

                    binding.cameraPreview.setOnTouchListener { view, motionEvent ->
                        if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                            val factory = binding.cameraPreview.meteringPointFactory
                            val point = factory.createPoint(motionEvent.x, motionEvent.y)
                            val action = FocusMeteringAction.Builder(point).build()
                            camera?.cameraControl?.startFocusAndMetering(action)
                            view.performClick()
                        }
                        true
                    }
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupCameraControls() {
        val zoom = camera?.cameraInfo?.zoomState?.value
        if (zoom != null) {
            binding.zoomSlider.valueFrom = zoom.minZoomRatio
            binding.zoomSlider.valueTo = zoom.maxZoomRatio
            binding.zoomSlider.stepSize = 0.1f
            binding.zoomSlider.value = zoom.zoomRatio
            binding.zoomSlider.addOnChangeListener { _, v, _ ->
                camera?.cameraControl?.setZoomRatio(v)
            }
        } else {
            binding.zoomSlider.visibility = View.GONE
        }

        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        binding.flashlightButton.isEnabled = hasFlash
        if (!hasFlash) binding.flashlightButton.alpha = 0.5f
    }

    private fun updateCropRect() {
        val pv = binding.cameraPreview
        val guide = binding.framingGuide
        val pw = pv.width
        val ph = pv.height
        val gw = guide.width
        val gh = guide.height
        if (pw > 0 && ph > 0 && gw > 0 && gh > 0) {
            cropRect = Rect(guide.left, guide.top, guide.left + gw, guide.top + gh)
        }
    }

    private fun toggleFlashlight() {
        camera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                isFlashlightOn = !isFlashlightOn
                cam.cameraControl.enableTorch(isFlashlightOn)
                binding.flashlightButton.setImageResource(
                    if (isFlashlightOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off
                )
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        if (!isScanning.get()) {
            imageProxy.close()
            return
        }

        val fullBitmap = imageProxyToBitmap(imageProxy)
        lastValidBitmap?.recycle()
        lastValidBitmap = fullBitmap

        var imageToProcess: Bitmap? = null
        var isCropped = false
        analysisCropRect = null

        if (cropRect != null && fullBitmap.width > 0 && fullBitmap.height > 0) {
            val pvW = binding.cameraPreview.width
            val pvH = binding.cameraPreview.height
            if (pvW == 0 || pvH == 0) {
                Log.e(TAG, "Preview dimensions are zero, cannot map crop rect.")
                imageProxy.close(); return
            }
            val mapped = mapViewRectToBitmapRect(
                cropRect!!, pvW, pvH, fullBitmap.width, fullBitmap.height
            )
            val l = mapped.left.coerceIn(0, fullBitmap.width)
            val t = mapped.top.coerceIn(0, fullBitmap.height)
            val r = mapped.right.coerceIn(0, fullBitmap.width)
            val b = mapped.bottom.coerceIn(0, fullBitmap.height)
            val w = (r - l).coerceAtLeast(0)
            val h = (b - t).coerceAtLeast(0)
            if (w > 0 && h > 0) {
                try {
                    imageToProcess = Bitmap.createBitmap(fullBitmap, l, t, w, h)
                    isCropped = true
                    analysisCropRect = Rect(0, 0, w, h)
                    Log.d(TAG, "Crop OK: [l=$l, t=$t, w=$w, h=$h]")
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap cropping error: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Mapped crop has non-positive size. Skipping frame.")
            }
        }

        if (imageToProcess == null) {
            Log.w(TAG, "Cropping failed or not attempted, skipping frame.")
            imageProxy.close(); return
        }

        val enhancedBitmap = enhanceBitmapForOcr(imageToProcess)
        val image = InputImage.fromBitmap(enhancedBitmap, 0)

        // Color reference for red detection (same crop)
        val colorRef = imageToProcess.copy(Bitmap.Config.ARGB_8888, false)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                processTextBlock(visionText, colorRef)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
            }
            .addOnCompleteListener {
                try { colorRef.recycle() } catch (_: Exception) {}
                imageProxy.close()
                enhancedBitmap.recycle()
                if (isCropped && imageToProcess != fullBitmap) {
                    imageToProcess.recycle()
                }
            }
    }

    // Binarize + light 3x3 dilation to help thin glyphs like "1"
    private fun enhanceBitmapForOcr(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return bitmap

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sum = 0L
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            gray[i] = y
            sum += y
        }
        val avg = if (gray.isNotEmpty()) (sum / gray.size).toInt() else 128
        val thr = (avg * 0.90).toInt()

        for (i in gray.indices) {
            pixels[i] = if (gray[i] > thr) Color.WHITE else Color.BLACK
        }

        // 3x3 dilation
        val outPx = pixels.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (pixels[idx] == Color.BLACK) {
                    var keep = false
                    loop@ for (dy in -1..1) for (dx in -1..1) {
                        if (pixels[(y + dy) * w + (x + dx)] == Color.BLACK) {
                            keep = true; break@loop
                        }
                    }
                    outPx[idx] = if (keep) Color.BLACK else Color.WHITE
                }
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPx, 0, w, 0, 0, w, h)
        }
    }

    private fun showResults(reading: String) {
        lastValidBitmap?.let { bmp ->
            if (!bmp.isRecycled) binding.scannedImageView.setImageBitmap(bmp)
            else binding.scannedImageView.setImageDrawable(null)
        } ?: run { binding.scannedImageView.setImageDrawable(null) }

        binding.fullScreenText.text = reading
        binding.resultsContainer.visibility = View.VISIBLE
        binding.cameraUiContainer.visibility = View.GONE

        if (isFlashlightOn) {
            camera?.cameraControl?.enableTorch(false)
            isFlashlightOn = false
            binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
        }
    }

    private fun processTextBlock(result: Text, colorBitmap: Bitmap) {
        if (!isScanning.get()) return

        val currentReading = extractMeterReading(result, analysisCropRect, colorBitmap)

        runOnUiThread {
            if (System.currentTimeMillis() - scanStartTime > SCAN_TIMEOUT_MS) {
                isScanning.set(false)
                setUiEnabled(true)
                val winner = lastFrameReading ?: readingVotes.maxByOrNull { it.value }?.key
                if (winner != null) showResults(winner)
                else Toast.makeText(this, "Could not find a stable reading. Please try again.", Toast.LENGTH_SHORT).show()
                if (isFlashlightOn) {
                    camera?.cameraControl?.enableTorch(false)
                    isFlashlightOn = false
                    binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
                }
                return@runOnUiThread
            }

            if (currentReading != null) {
                // Temporal stability via Levenshtein distance
                lastFrameReading?.let { prev ->
                    val d = textDistance(prev, currentReading)
                    readingStabilityScore = if (d <= 2) readingStabilityScore + 1 else 0
                }
                lastFrameReading = currentReading

                if (readingStabilityScore >= STABILITY_THRESHOLD) {
                    isScanning.set(false)
                    setUiEnabled(true)
                    showResults(currentReading)
                    return@runOnUiThread
                }

                val votes = readingVotes.getOrDefault(currentReading, 0) + 1
                readingVotes[currentReading] = votes
                if (votes >= REQUIRED_VOTES_TO_WIN) {
                    isScanning.set(false)
                    setUiEnabled(true)
                    showResults(currentReading)
                }
            }
        }
    }

    // Levenshtein
    private fun textDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) dp[i + 1][0] = i + 1
        for (j in b.indices) dp[0][j + 1] = j + 1
        for (i in a.indices) for (j in b.indices) {
            dp[i + 1][j + 1] = if (a[i] == b[j]) dp[i][j]
            else 1 + minOf(dp[i][j], dp[i][j + 1], dp[i + 1][j])
        }
        return dp[a.length][b.length]
    }

    // OCR cleanup (no digit filtering here—done later)
    private fun cleanText(text: String): String =
        text.uppercase()
            .replace("O", "0")
            .replace("I", "1")
            .replace("L", "1")
            .replace("Z", "2")
            .replace("S", "5")
            .replace("B", "8")
            .replace("G", "6")
            .replace("Q", "0")
            .replace(",", ".")
            .replace(" ", "")

    // Region is red (fill or strong red border)
    private fun isRegionRed(bmp: Bitmap, rectIn: Rect): Boolean {
        if (bmp.width <= 0 || bmp.height <= 0) return false
        val rect = Rect(
            rectIn.left.coerceIn(0, bmp.width - 1),
            rectIn.top.coerceIn(0, bmp.height - 1),
            rectIn.right.coerceIn(1, bmp.width),
            rectIn.bottom.coerceIn(1, bmp.height)
        )
        if (rect.width() <= 1 || rect.height() <= 1) return false

        var reds = 0
        var total = 0
        val stepX = max(1, rect.width() / 24)
        val stepY = max(1, rect.height() / 12)
        for (y in rect.top until rect.bottom step stepY) {
            for (x in rect.left until rect.right step stepX) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (r > 120 && r > (g + b) * 0.9 && r > max(g, b) * 1.25) reds++
                total++
            }
        }
        val ratio = if (total == 0) 0f else reds.toFloat() / total
        return ratio > 0.18f
    }

    private fun rightSlice(box: Rect, w: Int): Rect {
        val sliceW = w.coerceAtLeast(1)
        return Rect(box.right - sliceW, box.top, box.right, box.bottom)
    }

    private fun insetPct(r: Rect, pct: Float): Rect {
        val dx = (r.width() * pct).toInt()
        val dy = (r.height() * pct).toInt()
        return Rect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
    }

    // Core extraction with stitching, font consistency, and red-tail filtering
    private fun extractMeterReading(result: Text, analysisCropRect: Rect?, colorBitmap: Bitmap): String? {
        val allElements = result.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .filter { el ->
                val box = el.boundingBox
                analysisCropRect != null && box != null &&
                        analysisCropRect.contains(box.left, box.top) &&
                        analysisCropRect.contains(box.right, box.bottom)
            }
        if (allElements.isEmpty() || analysisCropRect == null) return null

        val units = setOf("KWH", "KVAH", "MD")
        val imageCenterY = analysisCropRect.centerY()
        val validReadingPattern = Regex("""\b(0*\d{4,7})\b""")

        val unitCandidates = allElements.mapNotNull { el ->
            if ((el.confidence ?: 0f) < 0.3f) return@mapNotNull null
            val text = el.text.uppercase()
            if (units.any { text.contains(it) }) Pair(text, el.boundingBox) else null
        }

        val numberElements = allElements
            .sortedBy { it.boundingBox?.left ?: 0 }
            .map { cleanText(it.text) to it }
            .filter { (txt, el) -> txt.matches(Regex("""^[\n\r\t\d\.]+$""")) && (el.confidence ?: 0f) >= 0.3f }

        val stitchedCandidates = stitchAdjacentElements(numberElements)
        if (stitchedCandidates.isEmpty()) return null

        val maxElementHeight = stitchedCandidates.maxOfOrNull { it.height } ?: 0
        if (maxElementHeight == 0) return null

        // Allow some variation (mechanical wheels not uniform)
        val heightThreshold = (maxElementHeight * 0.80f)
        val mainGroup = stitchedCandidates.filter { it.box != null && it.height > 6 && it.height >= heightThreshold }
        if (mainGroup.isEmpty()) return null

        val finalCandidates = mutableListOf<ReadingCandidate>()
        for ((numberString, box, height) in mainGroup) {
            var cleanedNum = numberString
            val h = height.toFloat()
            val w = (box?.width() ?: 0).toFloat()
            val avgDigitW = if (cleanedNum.isNotEmpty()) w / cleanedNum.length else w

            // 1) likely fractional wheel (smaller last digit)
            val likelyFractionalTail = cleanedNum.length >= 5 && (
                    h < maxElementHeight * 0.92f || avgDigitW < h * 0.62f
                    )

            // 2) red last-digit (fill or border)
            var redTail = false
            if (box != null && cleanedNum.length >= 2) {
                val lastW = avgDigitW.toInt().coerceAtLeast(2)
                val lastRect = rightSlice(box, lastW)
                val inner = insetPct(lastRect, 0.12f)
                val redFill = isRegionRed(colorBitmap, inner)
                val border = Rect(lastRect).apply { inset(-(lastW * 0.25f).toInt(), 0) }
                val redEdge = !redFill && isRegionRed(colorBitmap, border)
                redTail = redFill || redEdge
            }

            if ((likelyFractionalTail || redTail) && cleanedNum.length > 1) {
                cleanedNum = cleanedNum.dropLast(1)
            }

            val finalCleanedNumber = cleanedNum.substringBefore('.')
                .replace(Regex("[^0-9]"), "")

            if (finalCleanedNumber.matches(validReadingPattern)) {
                val candidate = ReadingCandidate(finalCleanedNumber, box, height)

                unitCandidates.forEach { (_, unitBox) ->
                    if (isUnitSpatiallyClose(candidate.box, unitBox)) {
                        candidate.score += 10
                        return@forEach
                    }
                }

                if (box != null && abs(box.centerY() - imageCenterY) < (imageCenterY * 0.25)) {
                    candidate.score += 1
                }

                finalCandidates.add(candidate)
            }
        }

        if (finalCandidates.isEmpty()) return null

        val maxScore = finalCandidates.maxOfOrNull { it.score } ?: 0
        val top = if (maxScore > 0) finalCandidates.filter { it.score == maxScore } else finalCandidates
        return top.maxByOrNull { it.number.length }?.number
    }

    // Map PreviewView rect to bitmap rect for FILL_CENTER behavior
    private fun mapViewRectToBitmapRect(viewRect: Rect, viewW: Int, viewH: Int, bmpW: Int, bmpH: Int): Rect {
        if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return Rect(0, 0, bmpW, bmpH)
        val scale = max(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
        val dispW = bmpW * scale
        val dispH = bmpH * scale
        val offX = (dispW - viewW) / 2f
        val offY = (dispH - viewH) / 2f
        fun mapX(x: Int) = ((x + offX) / scale).toInt()
        fun mapY(y: Int) = ((y + offY) / scale).toInt()
        val l = mapX(viewRect.left).coerceIn(0, bmpW)
        val t = mapY(viewRect.top).coerceIn(0, bmpH)
        val r = mapX(viewRect.right).coerceIn(0, bmpW)
        val b = mapY(viewRect.bottom).coerceIn(0, bmpH)
        return Rect(min(l, r), min(t, b), max(l, r), max(t, b))
    }

    private fun stitchAdjacentElements(elements: List<Pair<String, Text.Element>>): List<ReadingCandidate> {
        val stitched = mutableListOf<ReadingCandidate>()
        var i = 0
        while (i < elements.size) {
            var txt = elements[i].first
            var box = elements[i].second.boundingBox?.let { Rect(it) }
            var h = box?.height() ?: 0
            var j = i + 1
            while (j < elements.size) {
                val nextTxt = elements[j].first
                val nextBox = elements[j].second.boundingBox ?: break
                val curBox = box ?: break
                if (nextTxt.matches(Regex("""^[\d\.]+$"""))) {
                    val gap = nextBox.left - curBox.right
                    val vOverlap = max(0, min(curBox.bottom, nextBox.bottom) - max(curBox.top, nextBox.top))
                    if (gap < h * 2.0 && vOverlap > h * 0.3) {
                        txt += nextTxt
                        curBox.right = nextBox.right
                        curBox.top = min(curBox.top, nextBox.top)
                        curBox.bottom = max(curBox.bottom, nextBox.bottom)
                        h = max(h, nextBox.height())
                        j++
                    } else break
                } else break
            }
            stitched.add(ReadingCandidate(txt, box, h))
            i = j
        }
        return stitched
    }

    private fun isUnitSpatiallyClose(numberBox: Rect?, unitBox: Rect?): Boolean {
        if (numberBox == null || unitBox == null) return false
        val tolX = numberBox.width() * 5
        val tolY = numberBox.height() * 2
        val verticalAligned = abs(numberBox.centerY() - unitBox.centerY()) < numberBox.height()
        val right = unitBox.left > numberBox.right && (unitBox.left - numberBox.right) < tolX && verticalAligned
        val left = numberBox.left > unitBox.right && (numberBox.left - unitBox.right) < tolX && verticalAligned
        val topAligned = abs(numberBox.centerX() - unitBox.centerX()) < numberBox.width()
        val top = numberBox.top > unitBox.bottom && (numberBox.top - unitBox.bottom) < tolY && topAligned
        return right || left || top
    }

    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val image = imageProxy.image!!
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val bytes = out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textRecognizer.close()
        lastValidBitmap?.recycle()
    }

    companion object {
        private const val TAG = "QuoteGrabber"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
