package com.example.quotegrabber

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import com.google.mlkit.vision.text.TextRecognition
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

        textRecognizer = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
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
            val scaleX = bitmapWidth / previewWidth
            val scaleY = bitmapHeight / previewHeight
            val cropLeft = (cropRect!!.left * scaleX).toInt()
            val cropTop = (cropRect!!.top * scaleY).toInt()
            val cropWidth = (cropRect!!.width() * scaleX).toInt()
            val cropHeight = (cropRect!!.height() * scaleY).toInt()

            if (cropLeft + cropWidth <= fullBitmap.width && cropTop + cropHeight <= fullBitmap.height && cropWidth > 0 && cropHeight > 0) {
                try {
                    imageToProcess = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                    isCropped = true
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Bitmap cropping error: ${e.message}")
                    imageToProcess = fullBitmap // Fallback to full image on error
                    isCropped = false
                }
            } else {
                Log.w(TAG, "Calculated crop rect is out of bounds or invalid, using full bitmap")
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
                if (isCropped && imageToProcess != fullBitmap) { // Only recycle if it's a separate bitmap
                    imageToProcess.recycle()
                }
            }
    }

    // --- Sharpening filter re-added + Adaptive Thresholding ---
    private fun enhanceBitmapForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap // Return original if invalid dimensions

        val sharpenedBitmap = applySharpeningFilter(bitmap) // Sharpening step re-added
        val pixels = IntArray(width * height)
        sharpenedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

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

        val avgLuminance = if (pixels.isNotEmpty()) (totalLuminance / pixels.size).toInt() else 128 // Use mid-gray if empty
        val threshold = (avgLuminance * 0.9).toInt()

        for (i in pixels.indices) {
            pixels[i] = if (grayPixels[i] > threshold) Color.WHITE else Color.BLACK
        }

        val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhancedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        // Recycle the intermediate sharpened bitmap only if it's different from original
        if (sharpenedBitmap != bitmap) {
            sharpenedBitmap.recycle()
        }


        return enhancedBitmap
    }

    // --- Sharpening Filter Kernel re-added ---
    private fun applySharpeningFilter(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return bitmap // Kernel needs neighbors

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Sharpening kernel
        val kernel = arrayOf(
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(-1f, 5f, -1f),
            floatArrayOf(0f, -1f, 0f)
        )

        // Apply kernel skipping edges
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixelIndex = (y + ky) * width + (x + kx)
                        if (pixelIndex >= 0 && pixelIndex < pixels.size) { // Boundary check
                            val pixel = pixels[pixelIndex]
                            val kernelVal = kernel[ky + 1][kx + 1]
                            sumR += Color.red(pixel) * kernelVal
                            sumG += Color.green(pixel) * kernelVal
                            sumB += Color.blue(pixel) * kernelVal
                        }
                    }
                }

                val r = min(255, max(0, sumR.toInt()))
                val g = min(255, max(0, sumG.toInt()))
                val b = min(255, max(0, sumB.toInt()))
                if(x >= 0 && x < width && y >=0 && y < height) { // Double check bounds before setting pixel
                    resultBitmap.setPixel(x, y, Color.rgb(r, g, b))
                }
            }
        }
        // Handle edges (copy original pixel values) - simple approach
        for (y in 0 until height) {
            if (width > 0) {
                resultBitmap.setPixel(0, y, pixels[y * width]) // Left edge
                resultBitmap.setPixel(width - 1, y, pixels[y * width + width - 1]) // Right edge
            }
        }
        for (x in 0 until width) {
            if (height > 0) {
                resultBitmap.setPixel(x, 0, pixels[x]) // Top edge
                resultBitmap.setPixel(x, height-1, pixels[(height-1)*width + x]) // Bottom edge
            }
        }

        return resultBitmap
    }


    private fun showResults(reading: String) {
        if (lastValidBitmap != null && !lastValidBitmap!!.isRecycled) { // Check if bitmap is valid
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

    private fun cleanText(text: String): String {
        return text.replace("O", "0", true)
            .replace("I", "1", true)
            .replace("S", "5", true)
            .replace("B", "8", true)
            .replace("Z", "2", true)
            .replace(",", ".")
            .replace(" ", "")
            // Remove any non-numeric/non-decimal characters that might remain
            .filter { it.isDigit() || it == '.' }
    }

    // --- Parsing Logic with updated Regex and Priority ---
    private fun extractMeterReading(result: Text): String? {
        val allElements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
        if (allElements.isEmpty()) return null

        val units = setOf("KWH", "KVAH", "MD")
        val validReadingPattern = Regex("""(0*\d{4,7}|\d+\.\d+|\.\d+)""")
        val decimalReadingPattern = Regex(""".*\..*""") // Simple check for a decimal point

        val candidates = mutableListOf<Pair<String, Rect?>>()

        // 1. Find all potential numbers and units, apply basic cleaning
        for (element in allElements) {
            // Relax confidence slightly if needed, but 0.3f is reasonable
            if ((element.confidence ?: 0f) < 0.3f) continue
            val cleanedText = cleanText(element.text)
            // Find all occurrences within the cleaned text
            // Use a simpler pattern first to find number-like strings
            val numberLikePattern = Regex("""(\d+\.?\d*|\.\d+)""")
            numberLikePattern.findAll(cleanedText).forEach { match ->
                candidates.add(Pair(match.value, element.boundingBox))
            }
        }


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
        val decimalReadings = mutableListOf<String>()
        val otherValidReadings = mutableListOf<String>()


        // --- Categorize Readings ---
        for ((number, numberBox) in candidates) {
            // Re-validate against the stricter pattern here
            if (!number.matches(validReadingPattern)) continue

            var foundPair = false
            // Check for Spatial Pair
            for ((unit, unitBox) in unitCandidates) {
                if (isUnitSpatiallyClose(numberBox, unitBox)) {
                    pairedReadings.add(number)
                    foundPair = true
                    break // A number can only be paired once
                }
            }
            if (foundPair) continue // Skip other checks if paired

            // Check for Decimal
            if (number.matches(decimalReadingPattern)) {
                decimalReadings.add(number)
                continue // Skip size check if decimal
            }

            // If no pair and no decimal, add to other valid readings
            otherValidReadings.add(number)
        }


        // --- Apply Priorities ---
        // Priority 1: Longest reading spatially paired with a unit
        if (pairedReadings.isNotEmpty()) {
            return pairedReadings.maxByOrNull { it.length }
        }

        // Priority 2: Longest reading containing a decimal point
        if (decimalReadings.isNotEmpty()) {
            return decimalReadings.maxByOrNull { it.length }
        }

        // Priority 3: Longest valid reading found anywhere
        if (otherValidReadings.isNotEmpty()) {
            return otherValidReadings.maxByOrNull { it.length }
        }

        return null // No valid reading found
    }


    /**
     * Checks if a unit is spatially close to a number (left, right, or top).
     * Increased tolerance.
     */
    private fun isUnitSpatiallyClose(numberBox: Rect?, unitBox: Rect?): Boolean {
        if (numberBox == null || unitBox == null) return false

        // --- IMPROVEMENT: More Tolerant Spatial Checks ---
        // Use a larger multiplier for tolerance based on width AND height
        val toleranceX = numberBox.width() * 5
        val toleranceY = numberBox.height() * 2 // Allow more vertical difference

        // 1. Check if unit is on the Right
        // Allow centers to be within one full number height vertically
        val isVerticallyAlignedRight = abs(numberBox.centerY() - unitBox.centerY()) < numberBox.height()
        val isToTheRight = unitBox.left > numberBox.right
        val isCloseHorizontallyRight = (unitBox.left - numberBox.right) < toleranceX
        if (isVerticallyAlignedRight && isToTheRight && isCloseHorizontallyRight) return true

        // 2. Check if unit is on the Left
        // Allow centers to be within one full number height vertically
        val isVerticallyAlignedLeft = abs(numberBox.centerY() - unitBox.centerY()) < numberBox.height()
        val isToTheLeft = numberBox.left > unitBox.right
        val isCloseHorizontallyLeft = (numberBox.left - unitBox.right) < toleranceX
        if (isVerticallyAlignedLeft && isToTheLeft && isCloseHorizontallyLeft) return true


        // 3. Check if unit is on Top
        // Allow centers to be within one full number width horizontally
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

