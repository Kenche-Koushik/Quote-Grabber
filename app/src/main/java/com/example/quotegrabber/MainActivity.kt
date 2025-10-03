package com.example.quotegrabber

import android.Manifest
import android.annotation.SuppressLint
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private val isScanning = AtomicBoolean(false)
    private var camera: Camera? = null
    private var isFlashlightOn = false

    // New, modern way to handle permission requests.
    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/denied
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
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize ML Kit Text Recognizer
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Setup camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Request camera permission
        requestPermissions()

        binding.scanButton.setOnClickListener {
            // Turn off flashlight when scanning starts
            if (isFlashlightOn) {
                camera?.cameraControl?.enableTorch(false)
                isFlashlightOn = false
                binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
            }

            // Set the flag to true to process the next available frame
            isScanning.set(true)
            binding.recognizedText.text = "Scanning..."
        }

        binding.flashlightButton.setOnClickListener {
            toggleFlashlight()
        }

        // --- NEW: Set up listener for the full-screen results view ---
        binding.resultsContainer.setOnClickListener {
            // Hide the results view and show the camera view
            binding.resultsContainer.visibility = View.GONE
            binding.cameraUiContainer.visibility = View.VISIBLE
            // Reset the text in the small preview
            binding.recognizedText.text = getString(R.string.scan_placeholder)
        }
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

                // Preview UseCase
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

                // ImageAnalysis UseCase
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ::analyzeImage)
                    }

                // Select back camera as a default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera and store the camera instance
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                // Check for flash unit and update UI
                setupFlashlightButton()

                // Implement Tap-to-Focus
                binding.cameraPreview.setOnTouchListener { view, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                        val factory = binding.cameraPreview.meteringPointFactory
                        val point = factory.createPoint(motionEvent.x, motionEvent.y)
                        val action = FocusMeteringAction.Builder(point).build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        // Call performClick for accessibility standards
                        view.performClick()
                    }
                    true // Return true to indicate the event was handled
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupFlashlightButton() {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        binding.flashlightButton.isEnabled = hasFlash
        if(!hasFlash) {
            binding.flashlightButton.alpha = 0.5f // Visually indicate it's disabled
        }
    }

    private fun toggleFlashlight() {
        camera?.let {
            if(it.cameraInfo.hasFlashUnit()) {
                isFlashlightOn = !isFlashlightOn
                it.cameraControl.enableTorch(isFlashlightOn)
                if (isFlashlightOn) {
                    binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_on)
                } else {
                    binding.flashlightButton.setImageResource(R.drawable.ic_flashlight_off)
                }
            }
        }
    }

    // Use @OptIn to acknowledge the use of an experimental API.
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        // If the scan button was not clicked, do not process the frame.
        if (!isScanning.get()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Process image with ML Kit
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Task completed successfully
                    processTextBlock(visionText)
                }
                .addOnFailureListener { e ->
                    // Task failed with an exception
                    Log.e(TAG, "Text recognition failed", e)
                    binding.recognizedText.text = "Failed to recognize text."
                }
                .addOnCompleteListener {
                    // Reset the flag and close the proxy regardless of success or failure.
                    isScanning.set(false)
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processTextBlock(result: Text) {
        val resultText = result.text
        if (resultText.isEmpty()) {
            // If no text is found, show a quick message and stay on the camera screen
            Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show()
            binding.recognizedText.text = getString(R.string.scan_placeholder)
        } else {
            // --- NEW: If text is found, switch to the full-screen results view ---
            binding.fullScreenText.text = resultText
            binding.resultsContainer.visibility = View.VISIBLE
            binding.cameraUiContainer.visibility = View.GONE
        }
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