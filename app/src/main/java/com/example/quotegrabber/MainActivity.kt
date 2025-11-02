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
            isScanning.set(true)
            scanStartTime = System.currentTimeMillis()
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
                val preview = Preview.Builder().build().also {
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
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ::analyzeImage)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

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

        val fullBitmap = imageProxyToBitmap(imageProxy)
        lastValidBitmap?.recycle() // Recycle previous full bitmap
        lastValidBitmap = fullBitmap // Keep reference to the *uncropped* bitmap for display

        var imageToProcess: Bitmap? = null // Use nullable Bitmap
        var isCropped = false
        analysisCropRect = null // Reset analysis rect

        if (cropRect != null && fullBitmap.width > 0 && fullBitmap.height > 0) {
            val previewWidth = binding.cameraPreview.width.toFloat()
            val previewHeight = binding.cameraPreview.height.toFloat()
            val bitmapWidth = fullBitmap.width.toFloat()
            val bitmapHeight = fullBitmap.height.toFloat()

            // --- Ensure scale factors are valid ---
            if (previewWidth == 0f || previewHeight == 0f) {
                Log.e(TAG, "Preview dimensions are zero, cannot calculate scale.")
                imageProxy.close()
                return
            }


            val scaleX = bitmapWidth / previewWidth
            val scaleY = bitmapHeight / previewHeight
            val paddingX = (cropRect!!.width() * scaleX * 0.05f).toInt()
            val paddingY = (cropRect!!.height() * scaleY * 0.1f).toInt()

            // Calculate padded crop coordinates
            val desiredLeft = (cropRect!!.left * scaleX).toInt() - paddingX
            val desiredTop = (cropRect!!.top * scaleY).toInt() - paddingY
            val desiredRight = (cropRect!!.right * scaleX).toInt() + paddingX
            val desiredBottom = (cropRect!!.bottom * scaleY).toInt() + paddingY

            // --- Robust Boundary Check ---
            val finalLeft = max(0, desiredLeft)
            val finalTop = max(0, desiredTop)
            val finalRight = min(fullBitmap.width, desiredRight)
            val finalBottom = min(fullBitmap.height, desiredBottom)

            // Ensure width and height are positive after clamping
            val finalWidth = finalRight - finalLeft
            val finalHeight = finalBottom - finalTop

            if (finalWidth > 0 && finalHeight > 0) {
                try {
                    // Create the cropped bitmap
                    imageToProcess = Bitmap.createBitmap(fullBitmap, finalLeft, finalTop, finalWidth, finalHeight)
                    isCropped = true
                    analysisCropRect = Rect(0, 0, finalWidth, finalHeight) // Rect relative to the *cropped* bitmap
                    Log.d(TAG, "Successfully cropped image to: [$finalLeft, $finalTop, $finalWidth, $finalHeight]")
                } catch (e: Exception) { // Catch broader exceptions
                    Log.e(TAG, "Bitmap cropping error: ${e.message}", e)
                    // Fallback handled below by checking if imageToProcess is null
                }
            } else {
                Log.w(TAG, "Calculated crop rect has zero or negative dimensions after clamping.")
                // Fallback handled below
            }
        }

        // If cropping failed or wasn't attempted, do not process
        if (imageToProcess == null) {
            Log.w(TAG, "Cropping failed, skipping frame.")
            imageProxy.close()
            return
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

                val winner = readingVotes.maxByOrNull { it.value }?.key
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

    // --- NEW: Parsing Logic with Scoring System (Line-based) ---
    private fun extractMeterReading(result: Text, analysisCropRect: Rect?): String? {
        val allLines = result.textBlocks.flatMap { it.lines }
        val allElements = allLines.flatMap { it.elements } // Still needed for units
        if (allLines.isEmpty() || analysisCropRect == null) return null

        val units = setOf("KWH", "KVAH", "MD")
        // --- This is the pattern for the FINAL, cleaned number ---
        val validReadingPattern = Regex("""\b(0*\d{4,7})\b""") // Whole numbers, 4-7 digits
        val imageCenterY = analysisCropRect.centerY()
        val maxLineHeight = allLines.maxOfOrNull { it.boundingBox?.height() ?: 0 } ?: 0

        val candidates = mutableListOf<ReadingCandidate>()

        // 1. Find all potential units (from elements, for better location accuracy)
        val unitCandidates = allElements.mapNotNull { element ->
            if ((element.confidence ?: 0f) < 0.3f) return@mapNotNull null
            val unitCandidateText = element.text.uppercase()
            if (units.any { unitCandidateText.contains(it) }) {
                Pair(unitCandidateText, element.boundingBox)
            } else {
                null
            }
        }

        // 2. Find and Score all number candidates *from lines*
        for (line in allLines) {
            if ((line.confidence ?: 0f) < 0.3f) continue

            // This cleaning joins fragmented numbers like "4495 5" into "44955"
            val cleanedLineText = cleanText(line.text)

            // Find numbers, ignoring decimals
            // This regex finds whole numbers OR numbers with decimals
            val regex = Regex("""(0*\d{3,7})(?:\.\d+)?|(\d+\.\d+)""")

            regex.findAll(cleanedLineText).forEach { match ->
                // Get the captured group, preferring the whole number part
                val numberString = match.groupValues[1].ifEmpty { match.groupValues[2] }

                // Now, remove the decimal part for a final, clean number
                val finalCleanedNumber = numberString.replace(Regex("\\..*"), "").filter { it.isDigit() }

                // Validate against our strict 4-7 digit pattern
                if (finalCleanedNumber.matches(validReadingPattern)) {
                    val box = line.boundingBox // Use the whole line's box
                    val height = box?.height() ?: 0

                    val candidate = ReadingCandidate(finalCleanedNumber, box, height)

                    // Score the candidate
                    // P1: Spatial Pair (+10)
                    for ((_, unitBox) in unitCandidates) {
                        if (isUnitSpatiallyClose(candidate.box, unitBox)) {
                            candidate.score += 10
                            break
                        }
                    }

                    // P2: Size Score (+5)
                    if (maxLineHeight > 0 && height >= maxLineHeight * 0.7) {
                        candidate.score += 5
                    }

                    // P3: Center Score (+1)
                    if (box != null && abs(box.centerY() - imageCenterY) < (imageCenterY * 0.25)) {
                        candidate.score += 1
                    }

                    if (candidate.score > 0) {
                        candidates.add(candidate)
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            // Fallback: If no scored candidates, just find the longest valid number from any line
            val fallbackCandidates = allLines.mapNotNull { line ->
                val cleanedText = cleanText(line.text).replace(Regex("\\..*"), "").filter { it.isDigit() }
                if (cleanedText.matches(validReadingPattern)) {
                    ReadingCandidate(cleanedText, line.boundingBox, line.boundingBox?.height() ?: 0)
                } else {
                    null
                }
            }
            if (fallbackCandidates.isEmpty()) return null
            return fallbackCandidates.maxByOrNull { it.number.length }?.number
        }

        // 3. Find the best candidate
        val maxScore = candidates.maxOfOrNull { it.score } ?: 0

        if (maxScore == 0) {
            // If no one scored (e.g., no unit, all same size), just use the longest valid one
            return candidates.maxByOrNull { it.number.length }?.number
        }

        // Find all candidates with the max score
        val topCandidates = candidates.filter { it.score == maxScore }

        // Among the top-scoring, return the longest one
        return topCandidates.maxByOrNull { it.number.length }?.number
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

