package com.example.quotegrabber

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private val isScanning = AtomicBoolean(false)
    private var camera: Camera? = null
    private var isFlashlightOn = false
    private var cropRect: Rect? = null

    private var potentialReading: String? = null
    private var stableFrames = 0
    private val REQUIRED_STABLE_FRAMES = 2
    private val SCAN_TIMEOUT_MS = 5000L
    private var scanStartTime = 0L


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

        binding.scanButton.setOnClickListener {
            if (isFlashlightOn) {
                camera?.cameraControl?.enableTorch(false)
                isFlashlightOn = false
                binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
            }

            potentialReading = null
            stableFrames = 0
            isScanning.set(true)
            scanStartTime = System.currentTimeMillis()
            Toast.makeText(this, "Scanning... Hold steady.", Toast.LENGTH_SHORT).show()

            setUiEnabled(false) // Disable controls
        }

        binding.flashlightButton.setOnClickListener {
            toggleFlashlight()
        }

        binding.scanAgainButton.setOnClickListener {
            potentialReading = null
            stableFrames = 0
            isScanning.set(false)
            binding.resultsContainer.visibility = View.GONE
            binding.cameraUiContainer.visibility = View.VISIBLE

            setUiEnabled(true) // Re-enable controls
        }
    }

    private fun setUiEnabled(isEnabled: Boolean) {
        val alphaValue = if (isEnabled) 1.0f else 0.5f
        binding.scanButton.isEnabled = isEnabled
        binding.scanButton.alpha = alphaValue

        binding.zoomSlider.isEnabled = isEnabled
        binding.zoomSlider.alpha = alphaValue

        // Only re-enable flashlight if the device has one
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
        if (!isScanning.get()) {
            imageProxy.close()
            return
        }

        val fullBitmap = imageProxyToBitmap(imageProxy)
        val imageToProcess: Bitmap

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

            if (cropLeft + cropWidth <= fullBitmap.width && cropTop + cropHeight <= fullBitmap.height) {
                imageToProcess = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
            } else {
                imageToProcess = fullBitmap
            }
        } else {
            imageToProcess = fullBitmap
        }

        // --- New: Pre-process the bitmap for better OCR ---
        val enhancedBitmap = enhanceBitmapForOcr(imageToProcess)

        val image = InputImage.fromBitmap(enhancedBitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                processTextBlock(visionText, fullBitmap)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    // --- New: Image pre-processing function ---
    private fun enhanceBitmapForOcr(bitmap: Bitmap): Bitmap {
        val enhancedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhancedBitmap)
        val paint = Paint()
        // Increase contrast and convert to grayscale
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f) // Grayscale
            // --- IMPROVEMENT: Increased contrast to make faint characters like decimal points more visible ---
            setScale(2.0f, 2.0f, 2.0f, 1f) // Increased contrast from 1.5f
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return enhancedBitmap
    }

    private fun processTextBlock(result: Text, fullBitmapForDisplay: Bitmap) {
        if (!isScanning.get()) return

        val currentReading = extractMeterReading(result)

        runOnUiThread {
            if (System.currentTimeMillis() - scanStartTime > SCAN_TIMEOUT_MS) {
                isScanning.set(false)
                setUiEnabled(true) // Re-enable on timeout
                if (potentialReading != null) {
                    binding.scannedImageView.setImageBitmap(fullBitmapForDisplay)
                    binding.fullScreenText.text = potentialReading
                    binding.resultsContainer.visibility = View.VISIBLE
                    binding.cameraUiContainer.visibility = View.GONE
                } else {
                    Toast.makeText(this, "Could not find a stable reading. Please try again.", Toast.LENGTH_SHORT).show()
                }
                return@runOnUiThread
            }


            if (currentReading != null) {
                if (currentReading == potentialReading) {
                    stableFrames++
                } else {
                    potentialReading = currentReading
                    stableFrames = 1
                }

                if (stableFrames >= REQUIRED_STABLE_FRAMES) {
                    isScanning.set(false)
                    setUiEnabled(true) // Re-enable on success
                    binding.scannedImageView.setImageBitmap(fullBitmapForDisplay)
                    binding.fullScreenText.text = potentialReading
                    binding.resultsContainer.visibility = View.VISIBLE
                    binding.cameraUiContainer.visibility = View.GONE
                }
            }
        }
    }

    // --- New: Context-aware text parsing logic ---
    private fun extractMeterReading(result: Text): String? {
        val allElements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }

        val numberCandidates = allElements.mapNotNull { element ->
            val cleanedText = element.text.replace("O", "0", true)
                .replace("I", "1", true)
                .replace("S", "5", true)
                .replace("B", "8", true)
                .replace("Z", "2", true)
                .replace(",", ".") // --- IMPROVEMENT: Treat commas as decimal points ---

            val numericPattern = Regex("""\b(\d{4,7}(?:\.\d{1,2})?)\b""")
            numericPattern.find(cleanedText)?.let { match ->
                val reading = match.groupValues[1]
                Pair(reading, element.boundingBox)
            }
        }

        // Highest priority: Find a number with "kWh" to its right
        for ((number, numberBox) in numberCandidates) {
            val unit = findUnitOnRight(numberBox, allElements)
            if (unit != null) {
                return number // Found the best possible match
            }
        }

        // Fallback: Return the largest number found, as it's most likely the reading
        return numberCandidates.maxByOrNull { it.first.length }?.first
    }

    private fun findUnitOnRight(numberBox: Rect?, allElements: List<Text.Element>): String? {
        if (numberBox == null) return null

        val units = setOf("KWH", "KVAH", "MD")

        for (element in allElements) {
            val unitCandidate = element.text.uppercase()
            if (units.any { unitCandidate.contains(it) }) {
                val unitBox = element.boundingBox ?: continue

                // Check if the unit is roughly on the same horizontal line and to the right
                val isVerticallyAligned = abs(numberBox.centerY() - unitBox.centerY()) < (numberBox.height() / 2)
                val isToTheRight = unitBox.left > numberBox.right

                if (isVerticallyAligned && isToTheRight) {
                    return unitCandidate
                }
            }
        }
        return null
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "QuoteGrabber"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

