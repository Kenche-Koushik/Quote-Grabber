package com.example.quotegrabber

import android.Manifest
import android.annotation.SuppressLint
// Removed unused Context import
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.quotegrabber.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions // Correct import
import java.util.concurrent.Executors
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup // <-- Import for the fix
import androidx.camera.core.ViewPort // <-- Import for the fix
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.TextRecognition // Keep this import
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Data class to hold candidate information for scoring
data class ReadingCandidate(
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
    private var cropRect: Rect? = null
    private var analysisCropRect: Rect? = null // Store the rect used for analysis

    private val readingVotes = mutableMapOf<String, Int>()

    // --- NEW: OCR stability lock ---
    private var lastFrameReading: String? = null
    private var readingStabilityScore = 0
    private val STABILITY_THRESHOLD = 6
    private val REQUIRED_VOTES_TO_WIN = 2
    private val SCAN_TIMEOUT_MS = 3000L
    private var scanStartTime = 0L
    private var lastValidBitmap: Bitmap? = null

    // --- Removed Motion Sensor Logic ---

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
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

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) // Use specific latin options
        cameraExecutor = Executors.newSingleThreadExecutor()
        requestPermissions()

        // --- Removed Sensor Initialization ---

        binding.scanButton.setOnClickListener {
            readingVotes.clear()
            lastValidBitmap?.recycle()
            lastValidBitmap = null

            // ✅ Reset stability memory for clean scan
            lastFrameReading = null
            readingStabilityScore = 0
            scanStartTime = System.currentTimeMillis()

            isScanning.set(true)
            Toast.makeText(this, "Scanning... Hold steady.", Toast.LENGTH_SHORT).show()

            setUiEnabled(false)
        }

        binding.flashlightButton.setOnClickListener {
            toggleFlashlight()
        }

        binding.scanAgainButton.setOnClickListener {
            readingVotes.clear()
            lastValidBitmap?.recycle()
            lastValidBitmap = null
            isScanning.set(false)
            binding.resultsContainer.visibility = View.GONE
            binding.cameraUiContainer.visibility = View.VISIBLE

            setUiEnabled(true)
        }
    }

    // --- Removed Sensor Listener ---

    private fun setUiEnabled(isEnabled: Boolean) {
        val alphaValue = if (isEnabled) 1.0f else 0.5f
        binding.scanButton.isEnabled = isEnabled
        binding.scanButton.alpha = alphaValue

        binding.zoomSlider.isEnabled = isEnabled
        binding.zoomSlider.alpha = alphaValue

        binding.flashlightButton.isEnabled = if (isEnabled) camera?.cameraInfo?.hasFlashUnit() ?: false else false
        binding.flashlightButton.alpha = alphaValue
    }


    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // --- FIX: Wait for the view to be laid out to get the ViewPort ---
                binding.cameraPreview.post {
                    val viewPort = binding.cameraPreview.viewPort ?: run {
                        Log.e(TAG, "ViewPort is null, cannot bind camera")
                        return@post
                    }

                    val preview = Preview.Builder()
                        .setTargetRotation(binding.cameraPreview.display.rotation)
                        .build()
                        .also {
                            it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                        }

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
                        .also {
                            it.setAnalyzer(cameraExecutor, ::analyzeImage)
                        }

                    // --- FIX: Group UseCases and set the ViewPort ---
                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageAnalysis)
                        .setViewPort(viewPort) // This links the zoom/crop of preview and analysis
                        .build()

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()

                    // --- FIX: Bind the UseCaseGroup, not the individual cases ---
                    camera = cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup)

                    // These must be called *after* the camera is bound
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
                } // End of cameraPreview.post

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupCameraControls() {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        if (zoomState != null) {
            binding.zoomSlider.valueFrom = zoomState.minZoomRatio
            binding.zoomSlider.valueTo = zoomState.maxZoomRatio
            binding.zoomSlider.stepSize = 0.1f
            binding.zoomSlider.value = zoomState.zoomRatio
            binding.zoomSlider.addOnChangeListener { _, value, _ ->
                camera?.cameraControl?.setZoomRatio(value)
            }
        } else {
            binding.zoomSlider.visibility = View.GONE
        }

        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        binding.flashlightButton.isEnabled = hasFlash
        if(!hasFlash) {
            binding.flashlightButton.alpha = 0.5f
        }
    }

    private fun updateCropRect() {
        val previewView = binding.cameraPreview
        val framingGuide = binding.framingGuide
        val previewWidth = previewView.width
        val previewHeight = previewView.height
        val guideLeft = framingGuide.left
        val guideTop = framingGuide.top
        val guideWidth = framingGuide.width
        val guideHeight = framingGuide.height

        if (previewWidth > 0 && previewHeight > 0 && guideWidth > 0 && guideHeight > 0) {
            cropRect = Rect(guideLeft, guideTop, guideLeft + guideWidth, guideTop + guideHeight)
        }
    }

    private fun toggleFlashlight() {
        camera?.let {
            if(it.cameraInfo.hasFlashUnit()) {
                isFlashlightOn = !isFlashlightOn
                it.cameraControl.enableTorch(isFlashlightOn)
                binding.flashlightButton.setImageResource(
                    if (isFlashlightOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off
                )
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        // --- REMOVED: isDeviceSteady check ---

        if (!isScanning.get()) {
            imageProxy.close()
            return
        }

        // --- The bitmap received here is NOW correctly zoomed/cropped by the camera ---
        val fullBitmap = imageProxyToBitmap(imageProxy)
        lastValidBitmap?.recycle() // Recycle previous full bitmap
        lastValidBitmap = fullBitmap // Keep reference to the *uncropped* bitmap for display

        var imageToProcess: Bitmap? = null // Use nullable Bitmap
        var isCropped = false
        analysisCropRect = null // Reset analysis rect

        // --- Our manual crop logic now crops from the *already-zoomed* image ---
        if (cropRect != null && fullBitmap.width > 0 && fullBitmap.height > 0) {
            val previewWidth = binding.cameraPreview.width
            val previewHeight = binding.cameraPreview.height

            if (previewWidth == 0 || previewHeight == 0) {
                Log.e(TAG, "Preview dimensions are zero, cannot map crop rect.")
                imageProxy.close()
                return
            }

            // Map the on-screen framing box (in PreviewView coordinates)
            // to the underlying bitmap coordinates assuming PreviewView's
            // default scale type (FILL_CENTER). This handles center-crop
            // and prevents reading outside the guide even with aspect-mismatch.
            val mapped = mapViewRectToBitmapRect(
                cropRect!!,
                previewWidth,
                previewHeight,
                fullBitmap.width,
                fullBitmap.height
            )

            val finalLeft = mapped.left.coerceIn(0, fullBitmap.width)
            val finalTop = mapped.top.coerceIn(0, fullBitmap.height)
            val finalRight = mapped.right.coerceIn(0, fullBitmap.width)
            val finalBottom = mapped.bottom.coerceIn(0, fullBitmap.height)

            val finalWidth = (finalRight - finalLeft).coerceAtLeast(0)
            val finalHeight = (finalBottom - finalTop).coerceAtLeast(0)

            if (finalWidth > 0 && finalHeight > 0) {
                try {
                    imageToProcess = Bitmap.createBitmap(fullBitmap, finalLeft, finalTop, finalWidth, finalHeight)
                    isCropped = true
                    analysisCropRect = Rect(0, 0, finalWidth, finalHeight)
                    Log.d(TAG, "Crop OK: [l=$finalLeft, t=$finalTop, w=$finalWidth, h=$finalHeight]")
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap cropping error: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "Mapped crop has non-positive size. Skipping frame.")
            }
        }

        // --- FIX: If cropping fails, DO NOT process the full image. Skip the frame. ---
        if (imageToProcess == null) {
            Log.w(TAG, "Cropping failed or not attempted, skipping frame.")
            imageProxy.close() // Make sure to close the proxy
            return // EXIT THE FUNCTION
        }

        val enhancedBitmap = enhanceBitmapForOcr(imageToProcess) // Enhance the potentially cropped image
        val image = InputImage.fromBitmap(enhancedBitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                processTextBlock(visionText)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
                enhancedBitmap.recycle()
                // Only recycle imageToProcess if it's a *new* bitmap created by cropping
                if (isCropped && imageToProcess != fullBitmap) {
                    imageToProcess.recycle()
                }
            }
    }

    // --- Adaptive Thresholding Only (Sharpening Removed) ---
    private fun enhanceBitmapForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap

        // REMOVED: Sharpening step
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height) // Get pixels from original bitmap

        var totalLuminance: Long = 0
        val grayPixels = IntArray(pixels.size)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            grayPixels[i] = gray
            totalLuminance += gray
        }

        val avgLuminance = if (pixels.isNotEmpty()) (totalLuminance / pixels.size).toInt() else 128
        val threshold = (avgLuminance * 0.95).toInt() // Tuned threshold

        for (i in pixels.indices) {
            pixels[i] = if (grayPixels[i] > threshold) Color.WHITE else Color.BLACK
        }

        val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhancedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // REMOVED: Recycling of intermediate sharpened bitmap

        return enhancedBitmap
    }

    // --- REMOVED: applySharpeningFilter function ---


    private fun showResults(reading: String) {
        if (lastValidBitmap != null && !lastValidBitmap!!.isRecycled) { // Check if bitmap is valid
            // Display the *uncropped* bitmap saved earlier
            binding.scannedImageView.setImageBitmap(lastValidBitmap)
        } else {
            binding.scannedImageView.setImageDrawable(null) // Clear if bitmap is invalid
            Log.e(TAG, "lastValidBitmap was null or recycled in showResults")
        }
        binding.fullScreenText.text = reading
        binding.resultsContainer.visibility = View.VISIBLE
        binding.cameraUiContainer.visibility = View.GONE

        if (isFlashlightOn) {
            camera?.cameraControl?.enableTorch(false)
            isFlashlightOn = false
            binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
        }
    }

    private fun processTextBlock(result: Text) {
        if (!isScanning.get()) return

        val currentReading = extractMeterReading(result, analysisCropRect)

        runOnUiThread {
            if (System.currentTimeMillis() - scanStartTime > SCAN_TIMEOUT_MS) {
                isScanning.set(false)
                setUiEnabled(true)

                val winner = lastFrameReading ?: readingVotes.maxByOrNull { it.value }?.key
                if (winner != null) {
                    showResults(winner)
                } else {
                    Toast.makeText(this, "Could not find a stable reading. Please try again.", Toast.LENGTH_SHORT).show()
                    if (isFlashlightOn) {
                        camera?.cameraControl?.enableTorch(false)
                        isFlashlightOn = false
                        binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
                    }
                }
                return@runOnUiThread
            }

            if (currentReading != null) {
                // --- NEW: Stability check using character similarity ---
                if (lastFrameReading != null) {
                    val d = textDistance(lastFrameReading!!, currentReading)
                    if (d <= 1) {
                        readingStabilityScore++
                    } else {
                        readingStabilityScore = 0
                    }
                }
                lastFrameReading = currentReading

                if (readingStabilityScore >= STABILITY_THRESHOLD) {
                    isScanning.set(false)
                    setUiEnabled(true)
                    showResults(currentReading)
                    return@runOnUiThread
                }

                val currentVotes = readingVotes.getOrDefault(currentReading, 0) + 1
                readingVotes[currentReading] = currentVotes

                if (currentVotes >= REQUIRED_VOTES_TO_WIN) {
                    isScanning.set(false)
                    setUiEnabled(true)
                    showResults(currentReading)
                }
            }
        }
    }

    // --- Levenshtein distance ---
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



    // --- Updated cleanText: ONLY does character swaps and space removal ---
    private fun cleanText(text: String): String {
        return text.uppercase()
            .replace("O", "0")
            .replace("I", "1")
            .replace("L", "1")
            .replace("Z", "2")
            .replace("S", "5")
            .replace("B", "8")
            .replace("G", "6")
            .replace("Q", "0")
            .replace(",", ".") // replace comma with dot
            .replace(" ", "") // remove spaces
        // DO NOT filter digits or remove decimals here
    }

    // --- NEW: Parsing Logic with Element Stitching AND Font Size Consistency ---
    private fun extractMeterReading(result: Text, analysisCropRect: Rect?): String? {
        val allElements = result.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .filter { element ->
                val box = element.boundingBox
                analysisCropRect != null && box != null &&
                        analysisCropRect.contains(box.left, box.top) &&
                        analysisCropRect.contains(box.right, box.bottom)
            }
        if (allElements.isEmpty() || analysisCropRect == null) return null

        val units = setOf("KWH", "KVAH", "MD")
        val imageCenterY = analysisCropRect.centerY()
        val validReadingPattern = Regex("""\b(0*\d{4,7})\b""") // 4-7 digits

        // 1. Find all potential units
        val unitCandidates = allElements.mapNotNull { element ->
            if ((element.confidence ?: 0f) < 0.3f) return@mapNotNull null
            val unitCandidateText = element.text.uppercase()
            if (units.any { unitCandidateText.contains(it) }) {
                Pair(unitCandidateText, element.boundingBox)
            } else {
                null
            }
        }

        // 2. Find all number elements and clean them
        val numberElements = allElements
            .sortedBy { it.boundingBox?.left ?: 0 } // Sort left-to-right
            .map { Pair(cleanText(it.text), it) } // Clean text
            .filter { (it.first.matches(Regex("""^[\d\.]+$"""))) && (it.second.confidence ?: 0f) >= 0.3f } // Keep only numbers/decimals

        // 3. Stitch adjacent number elements
        val stitchedCandidates = stitchAdjacentElements(numberElements)

        // 4. --- NEW: Filter by Font Size Consistency ---
        if (stitchedCandidates.isEmpty()) return null

        val maxElementHeight = stitchedCandidates.maxOfOrNull { it.height } ?: 0
        if (maxElementHeight == 0) return null // No valid elements found

        // --- THIS IS THE STRICT FONT SIZE LOGIC ---
        // Keep only elements that are at least 92% of the max height (aggressively drop small text)
        val heightThreshold = (maxElementHeight * 0.70f) // allow 30% variation for mechanical wheels
        val mostConsistentGroup = stitchedCandidates.filter {
            it.box != null && it.height > 6 && it.height >= heightThreshold
        }

        if (mostConsistentGroup.isEmpty()) {
            Log.w(TAG, "No candidates passed the strict height filter (>=92% of max).")
            return null
        }


        // 5. Score all candidates *from the most consistent group*
        val finalCandidates = mutableListOf<ReadingCandidate>()
        for ((numberString, box, height) in mostConsistentGroup) {
            // Mechanical meter: drop red fractional wheel (often smaller)
            var cleanedNum = numberString
            val avgDigitWidth = (box?.width() ?: 0) / cleanedNum.length.toFloat()
// Drop last digit if it is likely the red fractional wheel
            if (cleanedNum.length > 1 && (height < maxElementHeight * 0.85f || avgDigitWidth < maxElementHeight * 0.55f)) {
                cleanedNum = cleanedNum.dropLast(1)
            }
            val finalCleanedNumber = cleanedNum.substringBefore('.').filter { it.isDigit() }

            if (finalCleanedNumber.matches(validReadingPattern)) {
                val candidate = ReadingCandidate(finalCleanedNumber, box, height)

                // P1: Spatial Pair (+10)
                for ((_, unitBox) in unitCandidates) {
                    if (isUnitSpatiallyClose(candidate.box, unitBox)) {
                        candidate.score += 10
                        break
                    }
                }

                // P2: Center Score (+1)
                if (box != null && abs(box.centerY() - imageCenterY) < (imageCenterY * 0.25)) {
                    candidate.score += 1
                }

                finalCandidates.add(candidate) // Add all valid candidates from the group
            }
        }

        if (finalCandidates.isEmpty()) {
            Log.w(TAG, "No candidates passed validation after scoring.")
            return null
        }

        // 6. Find the best candidate
        val maxScore = finalCandidates.maxOfOrNull { it.score } ?: 0

        val topCandidates = if (maxScore > 0) {
            finalCandidates.filter { it.score == maxScore } // Get all with max score
        } else {
            finalCandidates // If no one scored, consider all from the consistent group
        }

        // Among the top candidates, return the longest one
        return topCandidates.maxByOrNull { it.number.length }?.number
    }

    // --- Helper: map a PreviewView rect to bitmap rect for FILL_CENTER behavior ---
    private fun mapViewRectToBitmapRect(viewRect: Rect, viewW: Int, viewH: Int, bmpW: Int, bmpH: Int): Rect {
        if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return Rect(0, 0, bmpW, bmpH)

        // Scale used by PreviewView to draw image (FILL_CENTER):
        // image is scaled by s so that it fills the view; larger dimension crops.
        val scale = max(viewW.toFloat() / bmpW.toFloat(), viewH.toFloat() / bmpH.toFloat())

        // Displayed image size inside the view after scaling
        val dispW = bmpW * scale
        val dispH = bmpH * scale

        // Because of center-crop, portions may be cut off. Compute offsets so we can
        // translate from view coords to image coords.
        val offX = (dispW - viewW) / 2f
        val offY = (dispH - viewH) / 2f

        fun mapX(x: Int): Int = ((x + offX) / scale).toInt()
        fun mapY(y: Int): Int = ((y + offY) / scale).toInt()

        val left = mapX(viewRect.left).coerceIn(0, bmpW)
        val top = mapY(viewRect.top).coerceIn(0, bmpH)
        val right = mapX(viewRect.right).coerceIn(0, bmpW)
        val bottom = mapY(viewRect.bottom).coerceIn(0, bmpH)

        return Rect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
    }

    // --- Helper function to stitch number elements ---
    private fun stitchAdjacentElements(elements: List<Pair<String, Text.Element>>): List<ReadingCandidate> {
        val stitchedList = mutableListOf<ReadingCandidate>()
        var i = 0
        while (i < elements.size) {
            var currentText = elements[i].first
            var currentBox = elements[i].second.boundingBox?.let { Rect(it) } // Make a copy
            var currentHeight = currentBox?.height() ?: 0
            var j = i + 1

            // Check next elements
            while (j < elements.size) {
                val nextText = elements[j].first
                val nextBox = elements[j].second.boundingBox
                if (currentBox == null || nextBox == null) break // Can't compare

                if (nextText.matches(Regex("""^[\d\.]+$"""))) {
                    val horizontalGap = nextBox.left - currentBox.right
                    val verticalOverlap = max(0, min(currentBox.bottom, nextBox.bottom) - max(currentBox.top, nextBox.top))

                    // Stitch if horizontally adjacent (gap < 2.0x height) AND vertical overlap > 30%
                    if (horizontalGap < (currentHeight * 2.0) && verticalOverlap > (currentHeight * 0.3)) {
                        currentText += nextText // Stitch text
                        currentBox.right = nextBox.right // Extend box
                        currentBox.top = min(currentBox.top, nextBox.top) // Extend box
                        currentBox.bottom = max(currentBox.bottom, nextBox.bottom) // Extend box
                        currentHeight = max(currentHeight, nextBox.height()) // Use max height
                        j++ // Keep checking next element
                    } else {
                        break // Not adjacent, stop stitching
                    }
                } else {
                    break // Not a number, stop stitching
                }
            }
            stitchedList.add(ReadingCandidate(currentText, currentBox, currentHeight))
            i = j // Move to the next unstitch element
        }
        return stitchedList
    }


    /**
     * Checks if a unit is spatially close to a number (left, right, or top).
     * Increased tolerance.
     */
    private fun isUnitSpatiallyClose(numberBox: Rect?, unitBox: Rect?): Boolean {
        if (numberBox == null || unitBox == null) return false

        val toleranceX = numberBox.width() * 5
        val toleranceY = numberBox.height() * 2

        // 1. Check Right
        val isVerticallyAlignedRight = abs(numberBox.centerY() - unitBox.centerY()) < numberBox.height()
        val isToTheRight = unitBox.left > numberBox.right
        val isCloseHorizontallyRight = (unitBox.left - numberBox.right) < toleranceX
        if (isVerticallyAlignedRight && isToTheRight && isCloseHorizontallyRight) return true

        // 2. Check Left
        val isVerticallyAlignedLeft = abs(numberBox.centerY() - unitBox.centerY()) < numberBox.height()
        val isToTheLeft = numberBox.left > unitBox.right // Corrected condition
        val isCloseHorizontallyLeft = (numberBox.left - unitBox.right) < toleranceX
        if (isVerticallyAlignedLeft && isToTheLeft && isCloseHorizontallyLeft) return true

        // 3. Check Top
        val isHorizontallyAlignedTop = abs(numberBox.centerX() - unitBox.centerX()) < numberBox.width()
        val isAbove = numberBox.top > unitBox.bottom
        val isCloseVertically = (numberBox.top - unitBox.bottom) < toleranceY
        if (isHorizontallyAlignedTop && isAbove && isCloseVertically) return true

        return false
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
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onPause() {
        super.onPause()
        // --- Removed Sensor Un-registration ---
    }

    override fun onResume() {
        super.onResume()
        // --- Removed Sensor Registration ---
    }

    override fun onDestroy() {
        super.onDestroy()
        // --- Removed Sensor Un-registration ---
        cameraExecutor.shutdown()
        textRecognizer.close()
        lastValidBitmap?.recycle()
    }

    companion object {
        private const val TAG = "QuoteGrabber"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}