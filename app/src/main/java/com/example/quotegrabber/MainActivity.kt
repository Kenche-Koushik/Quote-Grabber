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

// Data class to hold candidate information including height
data class ReadingCandidate(val number: String, val box: Rect?, val height: Int)


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private val isScanning = AtomicBoolean(false)
    private var camera: Camera? = null
    private var isFlashlightOn = false
    private var cropRect: Rect? = null

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
        lastValidBitmap?.recycle()
        lastValidBitmap = fullBitmap

        var imageToProcess: Bitmap
        var isCropped: Boolean
        if (cropRect != null) {
            val previewWidth = binding.cameraPreview.width.toFloat()
            val previewHeight = binding.cameraPreview.height.toFloat()
            val bitmapWidth = fullBitmap.width.toFloat()
            val bitmapHeight = fullBitmap.height.toFloat()

            // --- Widen the Crop ---
            val scaleX = bitmapWidth / previewWidth
            val scaleY = bitmapHeight / previewHeight
            val paddingX = (cropRect!!.width() * scaleX * 0.05f).toInt()
            val paddingY = (cropRect!!.height() * scaleY * 0.1f).toInt()

            val cropLeft = max(0, (cropRect!!.left * scaleX).toInt() - paddingX)
            val cropTop = max(0, (cropRect!!.top * scaleY).toInt() - paddingY)
            val cropRight = min(fullBitmap.width, (cropRect!!.right * scaleX).toInt() + paddingX)
            val cropBottom = min(fullBitmap.height, (cropRect!!.bottom * scaleY).toInt() + paddingY)

            val cropWidth = cropRight - cropLeft
            val cropHeight = cropBottom - cropTop


            if (cropWidth > 0 && cropHeight > 0) {
                try {
                    imageToProcess = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                    isCropped = true
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Bitmap cropping error: ${e.message}")
                    imageToProcess = fullBitmap
                    isCropped = false
                }
            } else {
                Log.w(TAG, "Calculated crop rect is invalid after padding, using full bitmap")
                imageToProcess = fullBitmap
                isCropped = false
            }
        } else {
            imageToProcess = fullBitmap
            isCropped = false
        }

        val enhancedBitmap = enhanceBitmapForOcr(imageToProcess)
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
        if (lastValidBitmap != null && !lastValidBitmap!!.isRecycled) {
            binding.scannedImageView.setImageBitmap(lastValidBitmap)
        } else {
            binding.scannedImageView.setImageDrawable(null)
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

        val currentReading = extractMeterReading(result)

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

    // --- Updated cleanText with more replacements ---
    private fun cleanText(text: String): String {
        return text.uppercase() // Convert to uppercase for consistent replacement
            .replace("O", "0")
            .replace("I", "1")
            .replace("L", "1") // L -> 1
            .replace("Z", "2") // Z -> 2
            .replace("S", "5") // S -> 5
            .replace("B", "8") // B -> 8
            .replace("G", "6") // G -> 6 (less common, but possible)
            .replace("Q", "0") // Q -> 0 (less common)
            .replace(",", ".")
            .replace(" ", "")
            .replace(Regex("\\.\\d"), "") // Remove decimal and digit after it
            .filter { it.isDigit() }
    }

    // --- Parsing Logic with Bounding Box Consistency Check ---
    private fun extractMeterReading(result: Text): String? {
        val allElements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        if (allElements.isEmpty()) return null

        val units = setOf("KWH", "KVAH", "MD")
        // --- IMPROVEMENT 2: Relaxed Regex (Min 3 digits) ---
        val validReadingPattern = Regex("""\b(0*\d{3,7})\b""") // Whole numbers, 3-7 digits

        val candidates = mutableListOf<ReadingCandidate>()

        // 1. Find all potential numbers, clean them, and store with height
        for (element in allElements) {
            if ((element.confidence ?: 0f) < 0.3f) continue
            val cleanedText = cleanText(element.text) // Removes decimals

            validReadingPattern.findAll(cleanedText).forEach { match ->
                val number = match.value
                val box = element.boundingBox
                val height = box?.height() ?: 0
                if (height > 5) {
                    candidates.add(ReadingCandidate(number, box, height))
                }
            }
        }
        if (candidates.isEmpty()) return null

        // 2. Group candidates by height (allow 25% tolerance - increased)
        val heightGroups = candidates.groupBy { candidate ->
            candidates.minByOrNull { abs(it.height - candidate.height) }?.height ?: candidate.height
        }.filterValues { group ->
            if (group.isEmpty()) return@filterValues false // Avoid division by zero
            val avgHeight = group.map { it.height }.average()
            group.all { abs(it.height - avgHeight) <= avgHeight * 0.25 } // Tolerance increased to 25%
        }


        // 3. Find the group with the most candidates
        val mostConsistentGroup = heightGroups.maxByOrNull { it.value.size }?.value
            ?: candidates


        // 4. Find potential units
        val unitCandidates = allElements.mapNotNull { element ->
            if ((element.confidence ?: 0f) < 0.3f) return@mapNotNull null
            val unitCandidateText = element.text.uppercase()
            if (units.any { unitCandidateText.contains(it) }) {
                Pair(unitCandidateText, element.boundingBox)
            } else {
                null
            }
        }

        val pairedReadings = mutableListOf<String>()
        val otherValidReadings = mutableListOf<String>()

        // --- Categorize Readings *within the most consistent group* ---
        for (candidate in mostConsistentGroup) {
            var foundPair = false
            for ((unit, unitBox) in unitCandidates) {
                if (isUnitSpatiallyClose(candidate.box, unitBox)) {
                    pairedReadings.add(candidate.number)
                    foundPair = true
                    break
                }
            }
            if (!foundPair) {
                otherValidReadings.add(candidate.number)
            }
        }


        // --- Apply Priorities ---
        // Priority 1: Longest reading (from consistent group) spatially paired with a unit
        if (pairedReadings.isNotEmpty()) {
            return pairedReadings.maxByOrNull { it.length }
        }

        // Priority 2: Longest valid reading (from consistent group) found anywhere
        if (otherValidReadings.isNotEmpty()) {
            return otherValidReadings.maxByOrNull { it.length }
        }

        // Fallback: If height grouping failed, try longest valid from original candidates
        if (mostConsistentGroup === candidates && candidates.isNotEmpty()){
            Log.w(TAG,"Height consistency check failed, falling back to longest overall candidate.")
            val validOriginalCandidates = candidates.filter { it.number.matches(validReadingPattern) }
            return validOriginalCandidates.maxByOrNull { it.number.length }?.number
        }

        return null // No valid reading found
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