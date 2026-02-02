package com.techcity.techcitysuite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.BarcodeFormat as ZXingBarcodeFormat
import com.techcity.techcitysuite.databinding.ActivityBarcodeScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
class BarcodeScannerActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityBarcodeScannerBinding
    private lateinit var cameraExecutor: ExecutorService

    // ML Kit scanner (used when USE_ZXING = false)
    private var mlKitScanner: BarcodeScanner? = null

    // ZXing reader (used when USE_ZXING = true)
    private var zxingReader: MultiFormatReader? = null
    private var zxingMultiReader: GenericMultipleBarcodeReader? = null

    // Image capture for photo-based scanning
    private var imageCapture: ImageCapture? = null

    // Captured image bitmap for display
    private var capturedBitmap: android.graphics.Bitmap? = null

    // Scanning frame dimensions (the blue box) - will be measured from layout
    private var scanningFrameRect: android.graphics.Rect? = null
    private var previewViewRect: android.graphics.Rect? = null

    private var camera: Camera? = null
    private var isProcessingFrame = false
    private var isDialogShowing = false

    // Store detected barcodes from continuous scanning
    private val detectedBarcodes = mutableMapOf<String, BarcodeInfo>()

    // Track how many times each barcode has been detected (for confidence)
    private val barcodeDetectionCount = mutableMapOf<String, Int>()

    // Minimum detection count before considering a barcode as "confirmed"
    private val MIN_DETECTION_COUNT = 2

    // Auto-match mode: When enabled, automatically returns when a valid identifier is scanned
    private var autoMatchEnabled = false
    private var validIdentifiers: Set<String> = emptySet()
    private var hasAutoReturned = false  // Prevent multiple returns

    // IMEI priority mode: When true, prioritize IMEI/Serial barcodes over product codes
    private var prioritizeIMEI = true

    // Filter product codes: When true, ignore EAN/UPC product barcodes
    private var filterProductCodes = false

    // Image dimensions for center region calculation
    private var imageWidth = 0
    private var imageHeight = 0

    // Center region percentage (only detect barcodes in center X% of frame)
    // Set to 1.0 to disable center filtering (use full frame)
    private val CENTER_REGION_PERCENT = 1.0f  // DISABLED - use full frame for better detection

    // Frame counter for debugging
    private var frameCount = 0L

    // Data class to hold barcode information
    data class BarcodeInfo(
        val rawValue: String,
        val format: Int,
        val formatName: String,
        val smartLabel: String,
        val priority: Int = 0  // Higher = more important
    )

    companion object {
        private const val TAG = "BarcodeScannerActivity"
        const val RESULT_BARCODE_VALUE = "barcode_value"
        const val RESULT_BARCODE_TYPE = "barcode_type"
        const val RESULT_VERIFIED = "verified"  // Flag indicating user clicked Verify button

        // Auto-match mode extras
        const val EXTRA_AUTO_MATCH_ENABLED = "auto_match_enabled"
        const val EXTRA_VALID_IDENTIFIERS = "valid_identifiers"

        // IMEI priority mode extras
        const val EXTRA_PRIORITIZE_IMEI = "prioritize_imei"
        const val EXTRA_FILTER_PRODUCT_CODES = "filter_product_codes"

        // Priority levels for barcode types
        const val PRIORITY_IMEI = 100
        const val PRIORITY_SERIAL = 90
        const val PRIORITY_OTHER = 50
        const val PRIORITY_PRODUCT_CODE = 10

        // ============================================================
        // SCANNER LIBRARY TOGGLE
        // ============================================================
        // Set to TRUE to use ZXing (better for thin IMEI barcodes)
        // Set to FALSE to use ML Kit (Google's library)
        // ============================================================
        private const val USE_ZXING = true
        // ============================================================

        // ============================================================
        // PHOTO CAPTURE MODE TOGGLE
        // ============================================================
        // Set to TRUE to capture a photo first, then scan (better quality)
        // Set to FALSE to use continuous video scanning
        // ============================================================
        private const val USE_PHOTO_CAPTURE = true
        // ============================================================
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityBarcodeScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check for auto-match mode
        autoMatchEnabled = intent.getBooleanExtra(EXTRA_AUTO_MATCH_ENABLED, false)
        val identifiersList = intent.getStringArrayListExtra(EXTRA_VALID_IDENTIFIERS)
        if (identifiersList != null) {
            validIdentifiers = identifiersList.toSet()
        }

        // Check for IMEI priority mode (default true for better IMEI detection)
        prioritizeIMEI = intent.getBooleanExtra(EXTRA_PRIORITIZE_IMEI, true)
        filterProductCodes = intent.getBooleanExtra(EXTRA_FILTER_PRODUCT_CODES, false)

        // Initialize the appropriate barcode scanner
        if (USE_ZXING) {
            initializeZXing()
            Log.d(TAG, "Using ZXing scanner")
        } else {
            initializeMLKit()
            Log.d(TAG, "Using ML Kit scanner")
        }

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Setup UI
        setupUI()

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission()
        }

        // Measure scanning frame dimensions after layout is complete
        binding.root.post {
            measureScanningFrame()
        }
    }

    /**
     * Measure the scanning frame (blue box) position relative to the preview
     */
    private fun measureScanningFrame() {
        val previewView = binding.previewView
        val viewfinderFrame = binding.viewfinderFrame

        // Get preview view location and size
        val previewLocation = IntArray(2)
        previewView.getLocationOnScreen(previewLocation)
        previewViewRect = android.graphics.Rect(
            previewLocation[0],
            previewLocation[1],
            previewLocation[0] + previewView.width,
            previewLocation[1] + previewView.height
        )

        // Get viewfinder frame (blue box) location and size
        val frameLocation = IntArray(2)
        viewfinderFrame.getLocationOnScreen(frameLocation)
        scanningFrameRect = android.graphics.Rect(
            frameLocation[0],
            frameLocation[1],
            frameLocation[0] + viewfinderFrame.width,
            frameLocation[1] + viewfinderFrame.height
        )

        Log.d(TAG, "Preview: ${previewViewRect}, Viewfinder Frame: ${scanningFrameRect}")
    }

    /**
     * Initialize ML Kit barcode scanner
     */
    private fun initializeMLKit() {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        mlKitScanner = BarcodeScanning.getClient(options)
    }

    /**
     * Initialize ZXing barcode reader with hints for better IMEI detection
     */
    private fun initializeZXing() {
        val hints = mutableMapOf<DecodeHintType, Any>(
            // Enable formats commonly used for IMEIs and serial numbers
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                ZXingBarcodeFormat.CODE_128,    // Most common for IMEIs
                ZXingBarcodeFormat.CODE_39,
                ZXingBarcodeFormat.CODE_93,
                ZXingBarcodeFormat.ITF,         // Interleaved 2 of 5 - also common for IMEIs
                ZXingBarcodeFormat.CODABAR,     // Sometimes used for serial numbers
                ZXingBarcodeFormat.EAN_13,
                ZXingBarcodeFormat.EAN_8,
                ZXingBarcodeFormat.UPC_A,
                ZXingBarcodeFormat.UPC_E,
                ZXingBarcodeFormat.QR_CODE,
                ZXingBarcodeFormat.DATA_MATRIX,
                ZXingBarcodeFormat.RSS_14,      // GS1 DataBar
                ZXingBarcodeFormat.RSS_EXPANDED // GS1 DataBar Expanded
            ),
            // Try harder to find barcodes
            DecodeHintType.TRY_HARDER to true,
            // Character set
            DecodeHintType.CHARACTER_SET to "UTF-8",
            // Don't assume pure barcode - helps with labels that have text
            DecodeHintType.PURE_BARCODE to false
        )

        zxingReader = MultiFormatReader().apply {
            setHints(hints)
        }

        // Create multi-barcode reader to find ALL barcodes in frame
        zxingMultiReader = GenericMultipleBarcodeReader(zxingReader)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mlKitScanner?.close()

        // Clean up captured bitmap to avoid memory leaks
        capturedBitmap?.recycle()
        capturedBitmap = null
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: PERMISSION HANDLING
    // ============================================================================

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ============================================================================
    // END OF PART 3: PERMISSION HANDLING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: UI SETUP
    // ============================================================================

    private fun setupUI() {
        // Back/Cancel button
        binding.backButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        // Capture button behavior depends on mode
        binding.captureButton.setOnClickListener {
            if (USE_PHOTO_CAPTURE) {
                // Photo capture mode: Take a photo and process it
                captureAndProcessPhoto()
            } else {
                // Continuous scanning mode: Show detected barcodes
                showDetectedBarcodes()
            }
        }

        // Update instruction text based on mode
        binding.instructionText.text = "Position barcode inside the frame"
    }

    /**
     * Capture a photo and process it for barcodes
     */
    private fun captureAndProcessPhoto() {
        val imageCapture = imageCapture ?: return

        // Show processing indicator
        runOnUiThread {
            binding.instructionText.text = "📸 Capturing..."
            binding.captureButton.isEnabled = false
        }

        // Take the picture
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    runOnUiThread {
                        binding.instructionText.text = "🔍 Processing..."
                    }

                    // Process the captured image
                    processPhotoForBarcodes(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    runOnUiThread {
                        binding.instructionText.text = "❌ Capture failed. Try again."
                        binding.captureButton.isEnabled = true
                        Toast.makeText(this@BarcodeScannerActivity, "Photo capture failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    /**
     * Process captured photo for barcodes using ZXing or ML Kit
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processPhotoForBarcodes(imageProxy: ImageProxy) {
        val scannerName = if (USE_ZXING) "ZXing" else "ML Kit"

        try {
            // Convert ImageProxy to Bitmap (already cropped to scanning area)
            capturedBitmap = imageProxyToBitmap(imageProxy)

            if (capturedBitmap == null) {
                runOnUiThread {
                    binding.captureButton.isEnabled = true
                    showPhotoResultsDialog(emptyList(), "Failed to capture image")
                }
                imageProxy.close()
                return
            }

            Log.d(TAG, "Captured bitmap size: ${capturedBitmap!!.width}x${capturedBitmap!!.height}")

            if (USE_ZXING) {
                // Process cropped bitmap with ZXing
                val results = processPhotoWithZXingFromBitmap(capturedBitmap!!)

                runOnUiThread {
                    binding.captureButton.isEnabled = true

                    // Convert ZXing results to BarcodeInfo
                    val barcodeList = mutableListOf<BarcodeInfo>()
                    for (result in results) {
                        val value = result.text ?: continue
                        val format = result.barcodeFormat
                        val formatName = format.name
                        val smartLabel = getSmartLabelFromValue(value)
                        val priority = if (isImeiFormat(value)) PRIORITY_IMEI else PRIORITY_OTHER

                        barcodeList.add(BarcodeInfo(value, format.ordinal, formatName, smartLabel, priority))
                    }

                    // Show results screen with captured image
                    showPhotoResultsDialog(barcodeList)
                }
            } else {
                // Process with ML Kit using the cropped bitmap
                val inputImage = InputImage.fromBitmap(capturedBitmap!!, 0)

                mlKitScanner?.process(inputImage)
                    ?.addOnSuccessListener { barcodes ->
                        runOnUiThread {
                            binding.captureButton.isEnabled = true

                            // Convert ML Kit results to BarcodeInfo
                            val barcodeList = mutableListOf<BarcodeInfo>()
                            for (barcode in barcodes) {
                                val value = barcode.rawValue ?: continue
                                val format = barcode.format
                                val formatName = getFormatName(format)
                                val smartLabel = getSmartLabel(value, format)
                                val priority = calculateBarcodePriority(barcode)

                                barcodeList.add(BarcodeInfo(value, format, formatName, smartLabel, priority))
                            }

                            // Show results screen with captured image
                            showPhotoResultsDialog(barcodeList)
                        }
                    }
                    ?.addOnFailureListener { e ->
                        runOnUiThread {
                            binding.captureButton.isEnabled = true
                            showPhotoResultsDialog(emptyList(), "Scan failed: ${e.message}")
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo processing failed: ${e.message}", e)
            runOnUiThread {
                binding.captureButton.isEnabled = true
                showPhotoResultsDialog(emptyList(), "Processing failed: ${e.message}")
            }
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Process cropped bitmap with ZXing
     */
    private fun processPhotoWithZXingFromBitmap(bitmap: android.graphics.Bitmap): List<com.google.zxing.Result> {
        val results = mutableListOf<com.google.zxing.Result>()
        val foundValues = mutableSetOf<String>()

        val width = bitmap.width
        val height = bitmap.height

        Log.d(TAG, "Processing cropped bitmap: ${width}x${height}")

        // Extract luminance (grayscale) from bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yData = ByteArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Standard luminance formula
            yData[i] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt() and 0xFF).toByte()
        }

        // Strategy 1: Full image scan with multi-reader
        scanPhotoRegion(yData, width, height, 0, 0, width, height, results, foundValues, "full")

        // Strategy 2: Scan horizontal strips (IMEI barcodes are usually horizontal)
        val numStrips = 8  // More strips for better coverage
        val stripHeight = height / numStrips
        for (i in 0 until numStrips) {
            val top = i * stripHeight
            scanPhotoRegion(yData, width, height, 0, top, width, stripHeight, results, foundValues, "strip$i")
        }

        // Strategy 3: Scan with overlapping strips (catches barcodes between strip boundaries)
        val overlapStripHeight = height / 6
        for (i in 0 until 5) {
            val top = (i * overlapStripHeight) + (overlapStripHeight / 2)
            if (top + overlapStripHeight <= height) {
                scanPhotoRegion(yData, width, height, 0, top, width, overlapStripHeight, results, foundValues, "overlap$i")
            }
        }

        // Strategy 4: Scan center regions at different scales
        for (scale in listOf(0.9f, 0.7f, 0.5f, 0.3f)) {
            val padX = ((1 - scale) * width / 2).toInt()
            val padY = ((1 - scale) * height / 2).toInt()
            val regionWidth = (width * scale).toInt()
            val regionHeight = (height * scale).toInt()
            if (regionWidth > 100 && regionHeight > 50) {
                scanPhotoRegion(yData, width, height, padX, padY, regionWidth, regionHeight, results, foundValues, "center$scale")
            }
        }

        // Strategy 5: Scan with GlobalHistogramBinarizer
        scanPhotoRegionGlobal(yData, width, height, 0, 0, width, height, results, foundValues)

        // Strategy 6: Scan with inverted colors
        scanPhotoRegionInverted(yData, width, height, 0, 0, width, height, results, foundValues)

        // Strategy 7: Focus on bottom half (where iPhone/device barcodes typically are)
        val bottomHalfTop = height / 2
        val bottomHalfHeight = height - bottomHalfTop
        scanPhotoRegion(yData, width, height, 0, bottomHalfTop, width, bottomHalfHeight, results, foundValues, "bottomHalf")

        // Strategy 8: Scan bottom third (more focused)
        val bottomThirdTop = (height * 2) / 3
        val bottomThirdHeight = height - bottomThirdTop
        scanPhotoRegion(yData, width, height, 0, bottomThirdTop, width, bottomThirdHeight, results, foundValues, "bottomThird")

        // Strategy 9: Try upscaled image for thin barcodes (if image is small)
        if (width < 1500 && results.isEmpty()) {
            Log.d(TAG, "Trying upscaled scan...")
            scanWithUpscaledBitmap(bitmap, results, foundValues)
        }

        // Strategy 10: Enhanced contrast scan
        if (results.isEmpty()) {
            Log.d(TAG, "Trying enhanced contrast scan...")
            scanWithEnhancedContrast(yData, width, height, results, foundValues)
        }

        Log.d(TAG, "Cropped photo scan found ${results.size} barcodes: ${results.map { it.text }}")

        return results.filter { result ->
            val value = result.text ?: ""
            value.length >= 8 && !isGarbageResult(value)
        }
    }

    /**
     * Scan with an upscaled bitmap for thin barcode detection
     */
    private fun scanWithUpscaledBitmap(
        bitmap: android.graphics.Bitmap,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>
    ) {
        try {
            // Scale up by 2x
            val scaledWidth = bitmap.width * 2
            val scaledHeight = bitmap.height * 2

            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

            val pixels = IntArray(scaledWidth * scaledHeight)
            scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

            val yData = ByteArray(scaledWidth * scaledHeight)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                yData[i] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt() and 0xFF).toByte()
            }

            // Scan full upscaled image
            scanPhotoRegion(yData, scaledWidth, scaledHeight, 0, 0, scaledWidth, scaledHeight, results, foundValues, "upscaled")

            // Scan strips on upscaled
            val stripHeight = scaledHeight / 6
            for (i in 0 until 6) {
                val top = i * stripHeight
                scanPhotoRegion(yData, scaledWidth, scaledHeight, 0, top, scaledWidth, stripHeight, results, foundValues, "upscaledStrip$i")
            }

            scaledBitmap.recycle()
            Log.d(TAG, "Upscaled scan found ${results.size} barcodes")
        } catch (e: Exception) {
            Log.e(TAG, "Upscaled scan error: ${e.message}")
        }
    }

    /**
     * Scan with enhanced contrast
     */
    private fun scanWithEnhancedContrast(
        originalYData: ByteArray,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>
    ) {
        try {
            // Create contrast-enhanced version
            val enhancedData = ByteArray(originalYData.size)

            // Find min and max values
            var minVal = 255
            var maxVal = 0
            for (value in originalYData) {
                val v = value.toInt() and 0xFF
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }

            // Stretch contrast
            val range = maxVal - minVal
            if (range > 20) {  // Only enhance if there's enough range
                for (i in originalYData.indices) {
                    val v = originalYData[i].toInt() and 0xFF
                    val stretched = ((v - minVal) * 255 / range).coerceIn(0, 255)
                    enhancedData[i] = stretched.toByte()
                }

                // Scan with enhanced data
                scanPhotoRegion(enhancedData, width, height, 0, 0, width, height, results, foundValues, "enhanced")
                scanPhotoRegionGlobal(enhancedData, width, height, 0, 0, width, height, results, foundValues)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enhanced contrast scan error: ${e.message}")
        }
    }

    /**
     * Convert ImageProxy to Bitmap for display
     * Handles both JPEG (from ImageCapture) and YUV (from ImageAnalysis) formats
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): android.graphics.Bitmap? {
        return try {
            val image = imageProxy.image ?: return null

            // Check image format
            val format = imageProxy.format
            Log.d(TAG, "Image format: $format, planes: ${image.planes.size}")

            var bitmap = if (format == android.graphics.ImageFormat.JPEG || image.planes.size == 1) {
                // JPEG format - single plane containing JPEG data
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                // YUV format - convert to bitmap
                yuvTobitmap(image)
            }

            if (bitmap == null) return null

            // Rotate bitmap if needed based on image rotation
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                bitmap = rotated
            }

            // Crop to scanning area (center region matching the blue box)
            val croppedBitmap = cropToScanningArea(bitmap)
            if (croppedBitmap != bitmap) {
                bitmap.recycle()
            }

            croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap: ${e.message}", e)
            null
        }
    }

    /**
     * Crop bitmap to EXACTLY match the blue scanning frame
     * Uses the measured dimensions of the scanning frame relative to the preview
     * Adds a small buffer to prevent edge clipping from camera shake
     */
    private fun cropToScanningArea(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val previewRect = previewViewRect
        val frameRect = scanningFrameRect

        if (previewRect == null || frameRect == null) {
            Log.w(TAG, "Scanning frame not measured, using fallback crop")
            return cropToScanningAreaFallback(bitmap)
        }

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        val previewWidth = previewRect.width()
        val previewHeight = previewRect.height()

        if (previewWidth <= 0 || previewHeight <= 0) {
            Log.w(TAG, "Invalid preview dimensions, using fallback crop")
            return cropToScanningAreaFallback(bitmap)
        }

        // Calculate the scanning frame position as percentages of the preview
        val frameLeftPercent = (frameRect.left - previewRect.left).toFloat() / previewWidth
        val frameTopPercent = (frameRect.top - previewRect.top).toFloat() / previewHeight
        val frameWidthPercent = frameRect.width().toFloat() / previewWidth
        val frameHeightPercent = frameRect.height().toFloat() / previewHeight

        // Add buffer (5% on each side) to prevent clipping from camera shake
        val bufferPercent = 0.03f
        val adjustedLeftPercent = maxOf(0f, frameLeftPercent - bufferPercent)
        val adjustedTopPercent = maxOf(0f, frameTopPercent - bufferPercent)
        val adjustedWidthPercent = minOf(1f - adjustedLeftPercent, frameWidthPercent + (bufferPercent * 2))
        val adjustedHeightPercent = minOf(1f - adjustedTopPercent, frameHeightPercent + (bufferPercent * 2))

        // Apply these percentages to the captured bitmap
        val cropLeft = (bitmapWidth * adjustedLeftPercent).toInt()
        val cropTop = (bitmapHeight * adjustedTopPercent).toInt()
        val cropWidth = (bitmapWidth * adjustedWidthPercent).toInt()
        val cropHeight = (bitmapHeight * adjustedHeightPercent).toInt()

        // Ensure we don't go out of bounds
        val safeLeft = maxOf(0, minOf(cropLeft, bitmapWidth - 1))
        val safeTop = maxOf(0, minOf(cropTop, bitmapHeight - 1))
        val safeWidth = minOf(cropWidth, bitmapWidth - safeLeft)
        val safeHeight = minOf(cropHeight, bitmapHeight - safeTop)

        Log.d(TAG, "Crop: bitmap=${bitmapWidth}x${bitmapHeight}, " +
                "crop=($safeLeft, $safeTop, ${safeWidth}x${safeHeight})")

        return try {
            if (safeWidth > 0 && safeHeight > 0) {
                android.graphics.Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
            } else {
                Log.e(TAG, "Invalid crop dimensions")
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop bitmap: ${e.message}")
            bitmap
        }
    }

    /**
     * Fallback crop if scanning frame measurements aren't available
     */
    private fun cropToScanningAreaFallback(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Fallback: wider crop area (95% width to prevent clipping)
        val cropWidthPercent = 0.95f
        val cropHeightPercent = 0.45f

        val cropWidth = (width * cropWidthPercent).toInt()
        val cropHeight = (height * cropHeightPercent).toInt()

        val cropLeft = (width - cropWidth) / 2
        val cropTop = (height - cropHeight) / 2

        return try {
            android.graphics.Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Convert YUV_420_888 image to Bitmap
     */
    private fun yuvTobitmap(image: Image): android.graphics.Bitmap? {
        return try {
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

            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
            val imageBytes = out.toByteArray()

            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert YUV to bitmap: ${e.message}", e)
            null
        }
    }

    /**
     * Show photo results dialog with captured image and detected barcodes
     */
    private fun showPhotoResultsDialog(barcodeList: List<BarcodeInfo>, errorMessage: String? = null) {
        isDialogShowing = true

        // Create custom dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_photo_results, null)

        val imageView = dialogView.findViewById<android.widget.ImageView>(R.id.capturedImageView)
        val statusText = dialogView.findViewById<TextView>(R.id.statusText)
        val barcodeListView = dialogView.findViewById<RecyclerView>(R.id.barcodeResultsRecyclerView)
        val verifyButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.verifyButton)

        // Set captured image
        capturedBitmap?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
        }

        // Sort by priority (IMEI first)
        val sortedBarcodes = barcodeList.sortedByDescending { it.priority }

        // Check if we found IMEI or Serial Number
        val imeiCount = sortedBarcodes.count { it.smartLabel == "IMEI" }
        val serialCount = sortedBarcodes.count { it.smartLabel == "Serial Number" }
        val hasMatch = imeiCount > 0 || serialCount > 0

        // Get the first IMEI or Serial Number for verification
        val matchedBarcode = sortedBarcodes.firstOrNull {
            it.smartLabel == "IMEI" || it.smartLabel == "Serial Number"
        }

        // Set status text
        statusText.text = when {
            errorMessage != null -> "❌ $errorMessage"
            sortedBarcodes.isEmpty() -> "❌ No barcodes detected\n\nTips:\n• Move closer to barcode\n• Ensure barcode is in focus\n• Try better lighting"
            imeiCount > 0 -> "✅ Found ${sortedBarcodes.size} barcode(s) ($imeiCount IMEI)\nTap to select:"
            serialCount > 0 -> "✅ Found ${sortedBarcodes.size} barcode(s) ($serialCount Serial)\nTap to select:"
            else -> "Found ${sortedBarcodes.size} barcode(s)\nTap to select:"
        }

        // Show/hide Verify button based on whether IMEI or Serial was found
        if (hasMatch && matchedBarcode != null) {
            verifyButton.visibility = View.VISIBLE
            verifyButton.text = "✓ Verify ${matchedBarcode.smartLabel}"
        } else {
            verifyButton.visibility = View.GONE
        }

        // Create dialog with Retry (neutral) and Cancel (negative) buttons
        val dialog = AlertDialog.Builder(this)
            .setTitle("Scan Results")
            .setView(dialogView)
            .setCancelable(true)
            .setNeutralButton("Retry") { d, _ ->
                d.dismiss()
                isDialogShowing = false
                // Reset for next capture
                binding.instructionText.text = "Position barcode inside the frame"
                binding.captureButton.isEnabled = true
            }
            .setNegativeButton("Cancel") { d, _ ->
                d.dismiss()
            }
            .create()

        // Verify button - returns result with verified flag
        verifyButton.setOnClickListener {
            dialog.dismiss()
            matchedBarcode?.let { barcode ->
                detectedBarcodes[barcode.rawValue] = barcode
                returnBarcodeResult(barcode, verified = true)
            }
        }

        // Setup barcode list
        if (sortedBarcodes.isNotEmpty()) {
            barcodeListView.visibility = View.VISIBLE
            barcodeListView.layoutManager = LinearLayoutManager(this)
            barcodeListView.adapter = BarcodeAdapter(sortedBarcodes) { selectedBarcode ->
                dialog.dismiss()
                // Add to detected barcodes and return
                detectedBarcodes[selectedBarcode.rawValue] = selectedBarcode
                returnBarcodeResult(selectedBarcode)
            }
        } else {
            barcodeListView.visibility = View.GONE
        }

        dialog.setOnDismissListener {
            isDialogShowing = false
            binding.captureButton.isEnabled = true
        }

        dialog.show()
    }

    /**
     * Process photo with ZXing - tries multiple strategies for best detection
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processPhotoWithZXing(imageProxy: ImageProxy): List<com.google.zxing.Result> {
        val results = mutableListOf<com.google.zxing.Result>()
        val foundValues = mutableSetOf<String>()

        val image = imageProxy.image ?: return results
        val format = imageProxy.format

        Log.d(TAG, "Processing photo: format=$format, size=${image.width}x${image.height}")

        // Get grayscale data based on image format
        val width: Int
        val height: Int
        val yData: ByteArray

        if (format == android.graphics.ImageFormat.JPEG || image.planes.size == 1) {
            // JPEG format - decode to bitmap first, then extract luminance
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode JPEG")
                return results
            }

            // Rotate bitmap if needed
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            width = rotatedBitmap.width
            height = rotatedBitmap.height

            // Extract luminance (grayscale) from bitmap
            val pixels = IntArray(width * height)
            rotatedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            yData = ByteArray(width * height)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Standard luminance formula
                yData[i] = ((0.299 * r + 0.587 * g + 0.114 * b).toInt() and 0xFF).toByte()
            }

            // Clean up bitmap if we created a rotated copy
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }

            Log.d(TAG, "Converted JPEG to grayscale: ${width}x${height}")
        } else {
            // YUV format - use Y plane directly
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer.duplicate()
            yBuffer.rewind()

            val rowStride = yPlane.rowStride
            width = image.width
            height = image.height

            yData = ByteArray(width * height)
            if (rowStride == width) {
                yBuffer.get(yData, 0, width * height)
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * rowStride)
                    yBuffer.get(yData, row * width, width)
                }
            }

            Log.d(TAG, "Using YUV Y plane: ${width}x${height}")
        }

        // Now scan with ZXing using the grayscale data

        // Strategy 1: Full image scan with multi-reader
        scanPhotoRegion(yData, width, height, 0, 0, width, height, results, foundValues, "full")

        // Strategy 2: Scan horizontal strips (IMEI barcodes are usually horizontal)
        val numStrips = 6
        val stripHeight = height / numStrips
        for (i in 0 until numStrips) {
            val top = i * stripHeight
            scanPhotoRegion(yData, width, height, 0, top, width, stripHeight, results, foundValues, "strip$i")
        }

        // Strategy 3: Scan center region (different sizes)
        for (scale in listOf(0.8f, 0.6f, 0.4f)) {
            val padX = ((1 - scale) * width / 2).toInt()
            val padY = ((1 - scale) * height / 2).toInt()
            val regionWidth = (width * scale).toInt()
            val regionHeight = (height * scale).toInt()
            scanPhotoRegion(yData, width, height, padX, padY, regionWidth, regionHeight, results, foundValues, "center$scale")
        }

        // Strategy 4: Scan with GlobalHistogramBinarizer
        scanPhotoRegionGlobal(yData, width, height, 0, 0, width, height, results, foundValues)

        // Strategy 5: Scan with inverted colors
        scanPhotoRegionInverted(yData, width, height, 0, 0, width, height, results, foundValues)

        Log.d(TAG, "Photo scan found ${results.size} barcodes")

        return results.filter { result ->
            val value = result.text ?: ""
            value.length >= 8 && !isGarbageResult(value)
        }
    }

    /**
     * Scan a region of the photo
     */
    private fun scanPhotoRegion(
        yData: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>,
        regionName: String
    ) {
        if (width <= 0 || height <= 0) return
        if (left + width > fullWidth || top + height > fullHeight) return

        try {
            val source = PlanarYUVLuminanceSource(
                yData, fullWidth, fullHeight, left, top, width, height, false
            )

            // Try HybridBinarizer
            try {
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val multiResults = zxingMultiReader?.decodeMultiple(bitmap)
                multiResults?.forEach { result ->
                    val value = result.text
                    if (value != null && value !in foundValues && !isGarbageResult(value)) {
                        foundValues.add(value)
                        results.add(result)
                        Log.d(TAG, "Photo [$regionName] found: $value (${result.barcodeFormat})")
                    }
                }
            } catch (e: NotFoundException) {
                // Try single reader as fallback
                try {
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    val result = zxingReader?.decodeWithState(bitmap)
                    if (result != null) {
                        val value = result.text
                        if (value != null && value !in foundValues && !isGarbageResult(value)) {
                            foundValues.add(value)
                            results.add(result)
                            Log.d(TAG, "Photo [$regionName] single: $value (${result.barcodeFormat})")
                        }
                    }
                } catch (e2: NotFoundException) {
                    // No barcode found
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo scan error [$regionName]: ${e.message}")
        } finally {
            zxingReader?.reset()
        }
    }

    /**
     * Scan with GlobalHistogramBinarizer
     */
    private fun scanPhotoRegionGlobal(
        yData: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>
    ) {
        try {
            val source = PlanarYUVLuminanceSource(
                yData, fullWidth, fullHeight, left, top, width, height, false
            )
            val bitmap = BinaryBitmap(GlobalHistogramBinarizer(source))

            val multiResults = zxingMultiReader?.decodeMultiple(bitmap)
            multiResults?.forEach { result ->
                val value = result.text
                if (value != null && value !in foundValues && !isGarbageResult(value)) {
                    foundValues.add(value)
                    results.add(result)
                    Log.d(TAG, "Photo [global] found: $value (${result.barcodeFormat})")
                }
            }
        } catch (e: NotFoundException) {
            // No barcode found
        } catch (e: Exception) {
            Log.e(TAG, "Photo global scan error: ${e.message}")
        } finally {
            zxingReader?.reset()
        }
    }

    /**
     * Scan with inverted colors
     */
    private fun scanPhotoRegionInverted(
        yData: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>
    ) {
        try {
            val source = PlanarYUVLuminanceSource(
                yData, fullWidth, fullHeight, left, top, width, height, false
            )
            val invertedSource = source.invert()
            val bitmap = BinaryBitmap(HybridBinarizer(invertedSource))

            val multiResults = zxingMultiReader?.decodeMultiple(bitmap)
            multiResults?.forEach { result ->
                val value = result.text
                if (value != null && value !in foundValues && !isGarbageResult(value)) {
                    foundValues.add(value)
                    results.add(result)
                    Log.d(TAG, "Photo [inverted] found: $value (${result.barcodeFormat})")
                }
            }
        } catch (e: NotFoundException) {
            // No barcode found
        } catch (e: Exception) {
            Log.e(TAG, "Photo inverted scan error: ${e.message}")
        } finally {
            zxingReader?.reset()
        }
    }

    private fun updateBarcodeDisplay() {
        val confirmedCount = detectedBarcodes.size
        runOnUiThread {
            if (confirmedCount > 0) {
                // Sort by priority for display
                val sortedBarcodes = detectedBarcodes.values.sortedByDescending { it.priority }

                // Build the display text with barcode list
                val sb = StringBuilder()
                val imeiCount = sortedBarcodes.count { it.smartLabel == "IMEI" }

                if (imeiCount > 0) {
                    sb.append("✅ Found $confirmedCount barcode(s) ($imeiCount IMEI)\n")
                } else {
                    sb.append("✅ Found $confirmedCount barcode(s)\n")
                }

                // Add each barcode to the list (show top 3 to avoid clutter)
                sortedBarcodes.take(3).forEachIndexed { index, barcodeInfo ->
                    // Truncate long values for display
                    val displayValue = if (barcodeInfo.rawValue.length > 18) {
                        barcodeInfo.rawValue.take(15) + "..."
                    } else {
                        barcodeInfo.rawValue
                    }

                    // Add star for IMEI
                    val prefix = if (barcodeInfo.smartLabel == "IMEI") "★" else "•"
                    sb.append("$prefix ${barcodeInfo.smartLabel}: $displayValue")

                    if (index < minOf(sortedBarcodes.size, 3) - 1) {
                        sb.append("\n")
                    }
                }

                // Show if there are more
                if (sortedBarcodes.size > 3) {
                    sb.append("\n  ...and ${sortedBarcodes.size - 3} more")
                }

                binding.instructionText.text = sb.toString()
                binding.captureButton.alpha = 1.0f
            } else {
                if (USE_PHOTO_CAPTURE) {
                    binding.instructionText.text = "Position barcode inside the frame"
                } else if (autoMatchEnabled) {
                    binding.instructionText.text = "Scanning...\nWill auto-verify when matched"
                } else {
                    binding.instructionText.text = "Scanning..."
                }
                binding.captureButton.alpha = 0.6f
            }
        }
    }

    /**
     * Update UI to show scanning is active and what's being detected
     */
    private fun updateScanningIndicator(barcodesInFrame: Int) {
        // Skip this for photo capture mode (we handle display separately)
        if (USE_PHOTO_CAPTURE) {
            return
        }

        // Always update when no confirmed barcodes yet
        if (detectedBarcodes.isEmpty() && !hasAutoReturned) {
            // Pulsing effect to show scanning is active
            val pulse = (System.currentTimeMillis() % 1000) / 1000f
            binding.captureButton.alpha = 0.4f + (pulse * 0.3f)

            val scannerName = if (USE_ZXING) "ZXing" else "ML Kit"

            // Show frame count and barcode count
            val sb = StringBuilder()
            sb.append("📷 [$scannerName] Frame #$frameCount")

            if (barcodesInFrame > 0) {
                sb.append(" | 👀 $barcodesInFrame")
            }
            sb.append("\n")

            // Show last detected barcodes (from lastDetectedInfo)
            if (lastDetectedInfo.isNotEmpty()) {
                sb.append("─────────────────\n")
                sb.append(lastDetectedInfo)

                // Check if IMEI was NOT detected - give hint
                if (!lastDetectedInfo.contains("IMEI")) {
                    sb.append("\n─────────────────\n")
                    sb.append("💡 No IMEI found?\n")
                    sb.append("• Tap on IMEI barcode to focus\n")
                    sb.append("• Move closer to the barcode\n")
                    sb.append("• Try turning on flash")
                }
            } else {
                sb.append("No barcodes detected yet\n")
                sb.append("💡 Tap screen to focus on barcode")
            }

            binding.instructionText.text = sb.toString()
        }
    }

    /**
     * Store info about last detected barcodes for display
     */
    private var lastDetectedInfo: String = ""

    /**
     * Update the last detected info for on-screen display (ML Kit version)
     */
    private fun updateLastDetectedInfo(barcodes: List<Barcode>) {
        if (barcodes.isEmpty()) {
            return
        }

        val sb = StringBuilder()
        sb.append("Found ${barcodes.size} barcode(s):\n")

        barcodes.take(5).forEachIndexed { index, barcode ->
            val value = barcode.rawValue ?: "?"
            val format = getFormatName(barcode.format)
            val label = getSmartLabel(value, barcode.format)

            // Truncate long values
            val displayValue = if (value.length > 15) {
                value.take(12) + "..."
            } else {
                value
            }

            // Mark different types with icons
            val icon = when {
                label == "IMEI" -> "✅"
                label == "Serial Number" -> "🔤"
                label.contains("Product") -> "📦"
                label == "Data Matrix" || label == "QR Code" -> "⬛"
                else -> "○"
            }

            sb.append("$icon $displayValue\n")
            sb.append("   $format | $label\n")
        }

        if (barcodes.size > 5) {
            sb.append("...+${barcodes.size - 5} more")
        }

        lastDetectedInfo = sb.toString()
    }

    /**
     * Update the last detected info for on-screen display (ZXing version)
     */
    private fun updateLastDetectedInfoZXing(results: List<com.google.zxing.Result>) {
        if (results.isEmpty()) {
            lastDetectedInfo = ""
            return
        }

        val sb = StringBuilder()
        sb.append("Found ${results.size} barcode(s):\n")

        // Sort by priority (IMEI first)
        val sortedResults = results.sortedByDescending { result ->
            val value = result.text ?: ""
            when {
                isImeiFormat(value) -> 100
                isSerialNumberFormat(value) -> 80
                else -> 10
            }
        }

        sortedResults.take(5).forEach { result ->
            val value = result.text ?: "?"
            val format = result.barcodeFormat.name
            val label = getSmartLabelFromValue(value)

            // Truncate long values
            val displayValue = if (value.length > 15) {
                value.take(12) + "..."
            } else {
                value
            }

            // Mark different types with icons
            val icon = when {
                label == "IMEI" -> "✅"
                label == "Serial Number" -> "🔤"
                label.contains("Product") -> "📦"
                else -> "○"
            }

            sb.append("$icon $displayValue\n")
            sb.append("   $format | $label\n")
        }

        if (results.size > 5) {
            sb.append("...+${results.size - 5} more")
        }

        lastDetectedInfo = sb.toString()
    }

    // ============================================================================
    // END OF PART 4: UI SETUP
    // ============================================================================


    // ============================================================================
    // START OF PART 5: CAMERA SETUP WITH CONTINUOUS SCANNING
    // ============================================================================

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Use 16:9 aspect ratio to match typical phone screen
            val aspectRatio = AspectRatio.RATIO_16_9

            // Preview use case - matches screen aspect ratio
            val preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image capture use case - SAME aspect ratio as preview for consistency
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(aspectRatio)  // Match preview aspect ratio
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // Image analysis use case for continuous scanning (only if not using photo capture)
            val imageAnalysis = if (!USE_PHOTO_CAPTURE) {
                ImageAnalysis.Builder()
                    .setTargetAspectRatio(aspectRatio)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            if (USE_ZXING) {
                                processFrameZXing(imageProxy)
                            } else {
                                processFrameMLKit(imageProxy)
                            }
                        }
                    }
            } else {
                null
            }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                if (USE_PHOTO_CAPTURE) {
                    // Photo capture mode: Preview + ImageCapture only
                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } else if (imageAnalysis != null) {
                    // Continuous scanning mode: Preview + ImageAnalysis
                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                }

                // Setup tap-to-focus on preview
                setupTapToFocus()

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Tap to focus disabled - not needed for photo capture mode
     */
    private fun setupTapToFocus() {
        // Disabled - photo capture mode handles focus automatically
    }

    // ============================================================================
    // END OF PART 5: CAMERA SETUP WITH CONTINUOUS SCANNING
    // ============================================================================


    // ============================================================================
    // START OF PART 6: ML KIT BARCODE SCANNING
    // ============================================================================

    private fun processFrameMLKit(imageProxy: ImageProxy) {
        // Skip if dialog is showing or already auto-returned
        if (isDialogShowing || hasAutoReturned) {
            imageProxy.close()
            return
        }

        // Skip if still processing previous frame
        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true
        frameCount++

        // Store image dimensions
        imageWidth = imageProxy.width
        imageHeight = imageProxy.height

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        mlKitScanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                // Update on-screen debug info
                runOnUiThread {
                    updateLastDetectedInfo(barcodes)
                    updateScanningIndicator(barcodes.size)
                }

                processBarcodeResultsMLKit(barcodes)
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "ML Kit scanning failed: ${e.message}")
                runOnUiThread {
                    lastDetectedInfo = "⚠️ Scan error: ${e.message}"
                }
            }
            ?.addOnCompleteListener {
                isProcessingFrame = false
                imageProxy.close()
            }
    }

    private fun processBarcodeResultsMLKit(barcodes: List<Barcode>) {
        if (barcodes.isEmpty() || hasAutoReturned) {
            return
        }

        var newBarcodesAdded = false

        // Process ALL barcodes (no center region filtering)
        val barcodesToProcess = barcodes

        // Calculate priority for each barcode and sort by priority (highest first)
        val sortedBarcodes = barcodesToProcess.sortedByDescending { barcode ->
            calculateBarcodePriority(barcode)
        }

        for (barcode in sortedBarcodes) {
            val rawValue = barcode.rawValue ?: continue
            val format = barcode.format

            // Skip product codes if filter is enabled
            if (filterProductCodes && isProductCode(format, rawValue)) {
                continue
            }

            // AUTO-MATCH MODE: Only process barcodes that match valid identifiers
            if (autoMatchEnabled) {
                if (validIdentifiers.contains(rawValue)) {
                    // Found a match!
                    hasAutoReturned = true

                    val formatName = getFormatName(format)
                    val smartLabel = getSmartLabel(rawValue, format)
                    val priority = calculateBarcodePriority(barcode)

                    // Return immediately on UI thread
                    runOnUiThread {
                        val barcodeInfo = BarcodeInfo(rawValue, format, formatName, smartLabel, priority)
                        returnBarcodeResult(barcodeInfo)
                    }
                    return  // Stop processing further barcodes
                }
                // In auto-match mode, ignore barcodes that don't match valid identifiers
                continue
            }

            // NORMAL MODE: Process all barcodes with priority handling

            // Skip product codes if we already have an IMEI detected (prioritize IMEI)
            if (prioritizeIMEI && isProductCode(format, rawValue) && hasImeiDetected()) {
                continue
            }

            // Increment detection count
            val currentCount = barcodeDetectionCount.getOrDefault(rawValue, 0) + 1
            barcodeDetectionCount[rawValue] = currentCount

            // Require fewer detections for IMEIs (they're harder to read)
            val requiredDetections = if (isImeiFormat(rawValue)) 1 else MIN_DETECTION_COUNT

            // Only add to confirmed list if detected enough times
            if (currentCount >= requiredDetections && !detectedBarcodes.containsKey(rawValue)) {
                val formatName = getFormatName(format)
                val smartLabel = getSmartLabel(rawValue, format)
                val priority = calculateBarcodePriority(barcode)

                detectedBarcodes[rawValue] = BarcodeInfo(rawValue, format, formatName, smartLabel, priority)
                newBarcodesAdded = true
            }
        }

        if (newBarcodesAdded) {
            updateBarcodeDisplay()
        }
    }

    // ============================================================================
    // END OF PART 6: ML KIT BARCODE SCANNING
    // ============================================================================


    // ============================================================================
    // START OF PART 7: ZXING BARCODE SCANNING
    // ============================================================================

    private fun processFrameZXing(imageProxy: ImageProxy) {
        // Skip if dialog is showing or already auto-returned
        if (isDialogShowing || hasAutoReturned) {
            imageProxy.close()
            return
        }

        // Skip if still processing previous frame
        if (isProcessingFrame) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true
        frameCount++

        // Store image dimensions
        imageWidth = imageProxy.width
        imageHeight = imageProxy.height

        try {
            // Get Y plane data with proper handling of row stride
            val yPlane = imageProxy.planes[0]
            val yBuffer = yPlane.buffer.duplicate()  // Duplicate to avoid affecting original
            yBuffer.rewind()  // Ensure we read from the beginning

            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride

            val width = imageProxy.width
            val height = imageProxy.height

            // If row stride equals width, we can use the buffer directly
            // Otherwise, we need to copy row by row
            val yData: ByteArray
            if (rowStride == width && pixelStride == 1) {
                yData = ByteArray(width * height)
                yBuffer.get(yData, 0, width * height)
            } else {
                // Handle row stride padding
                yData = ByteArray(width * height)
                for (row in 0 until height) {
                    yBuffer.position(row * rowStride)
                    yBuffer.get(yData, row * width, width)
                }
            }

            // Convert ImageProxy to ZXing format
            val results = decodeWithZXingData(yData, width, height)

            // Update on-screen debug info
            runOnUiThread {
                updateLastDetectedInfoZXing(results)
                updateScanningIndicator(results.size)
            }

            processBarcodeResultsZXing(results)

        } catch (e: Exception) {
            Log.e(TAG, "ZXing scanning failed: ${e.message}")
        } finally {
            isProcessingFrame = false
            imageProxy.close()
        }
    }

    /**
     * Decode barcodes using ZXing from raw Y data
     */
    private fun decodeWithZXingData(yData: ByteArray, width: Int, height: Int): List<com.google.zxing.Result> {
        val results = mutableListOf<com.google.zxing.Result>()
        val foundValues = mutableSetOf<String>()  // Track unique values

        // Strategy 1: Scan full image with multi-barcode reader
        scanRegion(yData, width, height, 0, 0, width, height, results, foundValues, "full")

        // Strategy 2: Scan horizontal strips (IMEIs are usually in horizontal bands)
        val stripHeight = height / 4
        for (i in 0 until 4) {
            val top = i * stripHeight
            scanRegion(yData, width, height, 0, top, width, stripHeight, results, foundValues, "strip$i")
        }

        // Strategy 3: Scan center region with padding
        val padX = width / 8
        val padY = height / 8
        scanRegion(yData, width, height, padX, padY, width - 2 * padX, height - 2 * padY, results, foundValues, "center")

        // Strategy 4: Scan with GlobalHistogramBinarizer (better for some lighting conditions)
        scanRegionWithGlobalBinarizer(yData, width, height, 0, 0, width, height, results, foundValues)

        // Strategy 5: Scan with inverted colors (some barcodes show better inverted)
        scanRegionInverted(yData, width, height, 0, 0, width, height, results, foundValues)

        // Filter out garbage results (too short, clearly invalid)
        return results.filter { result ->
            val value = result.text ?: ""
            // Keep if it's a valid length (IMEI is 15 digits, serial numbers 8-20 chars)
            value.length >= 8 && !isGarbageResult(value)
        }
    }

    /**
     * Scan with inverted luminance (white becomes black, black becomes white)
     */
    private fun scanRegionInverted(
        yData: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>
    ) {
        try {
            val source = PlanarYUVLuminanceSource(
                yData,
                fullWidth,
                fullHeight,
                left,
                top,
                width,
                height,
                false
            )

            // Invert the source
            val invertedSource = source.invert()
            val binaryBitmap = BinaryBitmap(HybridBinarizer(invertedSource))

            try {
                val multiResults = zxingMultiReader?.decodeMultiple(binaryBitmap)
                multiResults?.forEach { result ->
                    val value = result.text
                    if (value != null && value !in foundValues && !isGarbageResult(value)) {
                        foundValues.add(value)
                        results.add(result)
                        Log.d(TAG, "ZXing [inverted] multi: $value (${result.barcodeFormat})")
                    }
                }
            } catch (e: NotFoundException) {
                // No barcodes found
            }

        } catch (e: Exception) {
            Log.e(TAG, "ZXing inverted scan error: ${e.message}")
        } finally {
            zxingReader?.reset()
        }
    }

    /**
     * Scan a specific region of the image
     */
    private fun scanRegion(
        yData: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>,
        regionName: String
    ) {
        try {
            val source = PlanarYUVLuminanceSource(
                yData,
                fullWidth,
                fullHeight,
                left,
                top,
                width,
                height,
                false
            )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            // Try multi-barcode reader first
            try {
                val multiResults = zxingMultiReader?.decodeMultiple(binaryBitmap)
                multiResults?.forEach { result ->
                    val value = result.text
                    if (value != null && value !in foundValues && !isGarbageResult(value)) {
                        foundValues.add(value)
                        results.add(result)
                        Log.d(TAG, "ZXing [$regionName] multi: $value (${result.barcodeFormat})")
                    }
                }
            } catch (e: NotFoundException) {
                // No barcodes found with multi-reader, try single reader
            }

            // Also try single reader (sometimes finds different barcodes)
            try {
                val singleResult = zxingReader?.decodeWithState(binaryBitmap)
                if (singleResult != null) {
                    val value = singleResult.text
                    if (value != null && value !in foundValues && !isGarbageResult(value)) {
                        foundValues.add(value)
                        results.add(singleResult)
                        Log.d(TAG, "ZXing [$regionName] single: $value (${singleResult.barcodeFormat})")
                    }
                }
            } catch (e: NotFoundException) {
                // No barcode found
            }

        } catch (e: Exception) {
            Log.e(TAG, "ZXing scan error in $regionName: ${e.message}")
        } finally {
            zxingReader?.reset()
        }
    }

    /**
     * Scan with GlobalHistogramBinarizer (better for uneven lighting)
     */
    private fun scanRegionWithGlobalBinarizer(
        yData: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        results: MutableList<com.google.zxing.Result>,
        foundValues: MutableSet<String>
    ) {
        try {
            val source = PlanarYUVLuminanceSource(
                yData,
                fullWidth,
                fullHeight,
                left,
                top,
                width,
                height,
                false
            )

            // Use GlobalHistogramBinarizer instead of HybridBinarizer
            val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))

            try {
                val multiResults = zxingMultiReader?.decodeMultiple(binaryBitmap)
                multiResults?.forEach { result ->
                    val value = result.text
                    if (value != null && value !in foundValues && !isGarbageResult(value)) {
                        foundValues.add(value)
                        results.add(result)
                        Log.d(TAG, "ZXing [global] multi: $value (${result.barcodeFormat})")
                    }
                }
            } catch (e: NotFoundException) {
                // No barcodes found
            }

        } catch (e: Exception) {
            Log.e(TAG, "ZXing global binarizer error: ${e.message}")
        } finally {
            zxingReader?.reset()
        }
    }

    /**
     * Check if a barcode result is likely garbage/noise
     */
    private fun isGarbageResult(value: String): Boolean {
        // Too short to be meaningful
        if (value.length < 3) return true

        // Single repeated character
        if (value.all { it == value[0] }) return true

        // Just numbers 0-9 (less than 8 digits is likely noise)
        if (value.length < 8 && value.all { it.isDigit() }) return true

        // Common garbage patterns
        val garbagePatterns = listOf("21", "12", "11", "22", "00", "01", "10")
        if (value in garbagePatterns) return true

        return false
    }

    private fun processBarcodeResultsZXing(results: List<com.google.zxing.Result>) {
        if (results.isEmpty() || hasAutoReturned) {
            return
        }

        var newBarcodesAdded = false

        // Calculate priority for each barcode and sort by priority (highest first)
        val sortedResults = results.sortedByDescending { result ->
            calculateBarcodePriorityZXing(result)
        }

        for (result in sortedResults) {
            val rawValue = result.text ?: continue
            val format = result.barcodeFormat

            // Skip product codes if filter is enabled
            if (filterProductCodes && isProductCodeZXing(format, rawValue)) {
                continue
            }

            // AUTO-MATCH MODE: Only process barcodes that match valid identifiers
            if (autoMatchEnabled) {
                if (validIdentifiers.contains(rawValue)) {
                    // Found a match!
                    hasAutoReturned = true

                    val formatName = format.name
                    val smartLabel = getSmartLabelFromValue(rawValue)
                    val priority = calculateBarcodePriorityZXing(result)

                    // Return immediately on UI thread
                    runOnUiThread {
                        val barcodeInfo = BarcodeInfo(rawValue, format.ordinal, formatName, smartLabel, priority)
                        returnBarcodeResult(barcodeInfo)
                    }
                    return  // Stop processing further barcodes
                }
                // In auto-match mode, ignore barcodes that don't match valid identifiers
                continue
            }

            // NORMAL MODE: Process all barcodes with priority handling

            // Skip product codes if we already have an IMEI detected (prioritize IMEI)
            if (prioritizeIMEI && isProductCodeZXing(format, rawValue) && hasImeiDetected()) {
                continue
            }

            // Increment detection count
            val currentCount = barcodeDetectionCount.getOrDefault(rawValue, 0) + 1
            barcodeDetectionCount[rawValue] = currentCount

            // Require fewer detections for IMEIs (they're harder to read)
            val requiredDetections = if (isImeiFormat(rawValue)) 1 else MIN_DETECTION_COUNT

            // Only add to confirmed list if detected enough times
            if (currentCount >= requiredDetections && !detectedBarcodes.containsKey(rawValue)) {
                val formatName = format.name
                val smartLabel = getSmartLabelFromValue(rawValue)
                val priority = calculateBarcodePriorityZXing(result)

                detectedBarcodes[rawValue] = BarcodeInfo(rawValue, format.ordinal, formatName, smartLabel, priority)
                newBarcodesAdded = true

                Log.d(TAG, "ZXing confirmed: $rawValue ($smartLabel)")
            }
        }

        if (newBarcodesAdded) {
            updateBarcodeDisplay()
        }
    }

    /**
     * Calculate priority for ZXing result
     */
    private fun calculateBarcodePriorityZXing(result: com.google.zxing.Result): Int {
        val rawValue = result.text ?: return PRIORITY_OTHER
        val format = result.barcodeFormat

        // Check if it matches valid identifiers (highest priority in auto-match mode)
        if (autoMatchEnabled && validIdentifiers.contains(rawValue)) {
            return 200
        }

        return when {
            // IMEI: 15 digits
            isImeiFormat(rawValue) -> PRIORITY_IMEI

            // Serial Number: alphanumeric mix
            isSerialNumberFormat(rawValue) -> PRIORITY_SERIAL

            // Product codes: EAN/UPC
            isProductCodeZXing(format, rawValue) -> PRIORITY_PRODUCT_CODE

            // Default
            else -> PRIORITY_OTHER
        }
    }

    /**
     * Check if ZXing format is a product code
     */
    private fun isProductCodeZXing(format: ZXingBarcodeFormat, value: String): Boolean {
        return when (format) {
            ZXingBarcodeFormat.EAN_13,
            ZXingBarcodeFormat.EAN_8,
            ZXingBarcodeFormat.UPC_A,
            ZXingBarcodeFormat.UPC_E -> true
            else -> {
                // Also check by pattern: 12-13 digits (common for UPC/EAN)
                (value.length == 12 || value.length == 13) && value.all { it.isDigit() }
            }
        }
    }

    /**
     * Get smart label from value only (for ZXing)
     */
    private fun getSmartLabelFromValue(value: String): String {
        return when {
            // IMEI detection: 14-16 digits
            isImeiFormat(value) -> "IMEI"

            // Serial number detection: alphanumeric, typically 8-20 chars
            isSerialNumberFormat(value) -> "Serial Number"

            // Product codes: 12-13 digits
            (value.length == 12 || value.length == 13) && value.all { it.isDigit() } -> "Product Code"

            // EID detection: typically 32 digits
            value.length == 32 && value.all { it.isDigit() } -> "EID"

            // Default
            else -> "Barcode"
        }
    }

    // ============================================================================
    // END OF PART 7: ZXING BARCODE SCANNING
    // ============================================================================


    // ============================================================================
    // START OF PART 8: BARCODE SELECTION DIALOG
    // ============================================================================

    private fun showDetectedBarcodes() {
        if (detectedBarcodes.isEmpty()) {
            // Show dialog with manual entry option
            showNoBarcodesDialog()
            return
        }

        isDialogShowing = true

        // Sort barcodes by priority (highest first)
        val barcodeList = detectedBarcodes.values.sortedByDescending { it.priority }

        // If only one barcode, return it directly
        if (barcodeList.size == 1) {
            returnBarcodeResult(barcodeList[0])
            return
        }

        // If we have exactly one IMEI and user probably wants IMEI, auto-select it
        val imeiList = barcodeList.filter { it.smartLabel == "IMEI" }
        if (imeiList.size == 1 && prioritizeIMEI) {
            // Only one IMEI detected, auto-return it
            returnBarcodeResult(imeiList[0])
            return
        }

        // Show selection dialog for multiple barcodes
        val dialogView = layoutInflater.inflate(R.layout.dialog_barcode_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.barcodeRecyclerView)
        val countText = dialogView.findViewById<TextView>(R.id.barcodeCountText)

        // Show count with IMEI hint if applicable
        val imeiCount = imeiList.size
        val countMessage = if (imeiCount > 0) {
            "${barcodeList.size} barcodes detected ($imeiCount IMEI) - tap to select"
        } else {
            "${barcodeList.size} barcodes detected - tap to select"
        }
        countText.text = countMessage

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = BarcodeAdapter(barcodeList) { selectedBarcode ->
            dialog.dismiss()
            returnBarcodeResult(selectedBarcode)
        }

        dialog.setOnDismissListener {
            isDialogShowing = false
        }

        dialog.show()
    }

    /**
     * Show dialog when no barcodes detected - offer manual entry
     */
    private fun showNoBarcodesDialog() {
        isDialogShowing = true

        val scannerName = if (USE_ZXING) "ZXing" else "ML Kit"
        val options = arrayOf(
            "📷 Keep scanning",
            "✏️ Enter IMEI manually"
        )

        AlertDialog.Builder(this)
            .setTitle("No IMEI barcode detected")
            .setMessage("$scannerName couldn't read the IMEI barcode.\n\nTips:\n• Tap on the IMEI barcode to focus\n• Move closer to the barcode\n• Turn on flash for better contrast\n• Make sure barcode isn't damaged")
            .setItems(options) { dialog, which ->
                dialog.dismiss()
                isDialogShowing = false
                when (which) {
                    0 -> {
                        // Keep scanning - do nothing, just dismiss
                    }
                    1 -> {
                        // Manual entry
                        showManualEntryDialog()
                    }
                }
            }
            .setOnCancelListener {
                isDialogShowing = false
            }
            .show()
    }

    /**
     * Show dialog to manually enter IMEI/Serial
     */
    private fun showManualEntryDialog() {
        val inputLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val inputField = android.widget.EditText(this).apply {
            hint = "Enter IMEI or Serial Number"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        }
        inputLayout.addView(inputField)

        AlertDialog.Builder(this)
            .setTitle("Manual Entry")
            .setMessage("Enter the IMEI or Serial Number from the label:")
            .setView(inputLayout)
            .setPositiveButton("OK") { dialog, _ ->
                val enteredValue = inputField.text.toString().trim()
                if (enteredValue.isNotEmpty()) {
                    // Return the manually entered value
                    val barcodeInfo = BarcodeInfo(
                        rawValue = enteredValue,
                        format = Barcode.FORMAT_UNKNOWN,
                        formatName = "Manual Entry",
                        smartLabel = if (isImeiFormat(enteredValue)) "IMEI" else "Serial Number",
                        priority = PRIORITY_IMEI
                    )
                    returnBarcodeResult(barcodeInfo)
                } else {
                    Toast.makeText(this, "No value entered", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        // Auto-show keyboard
        inputField.requestFocus()
        inputField.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun returnBarcodeResult(barcodeInfo: BarcodeInfo, verified: Boolean = false) {
        val resultIntent = Intent().apply {
            putExtra(RESULT_BARCODE_VALUE, barcodeInfo.rawValue)
            putExtra(RESULT_BARCODE_TYPE, barcodeInfo.smartLabel)
            putExtra(RESULT_VERIFIED, verified)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // ============================================================================
    // END OF PART 8: BARCODE SELECTION DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 9: HELPER METHODS
    // ============================================================================

    /**
     * Check if barcode is in the center region of the frame
     */
    private fun isBarcodeInCenterRegion(barcode: Barcode): Boolean {
        val boundingBox = barcode.boundingBox ?: return true  // If no bounding box, include it

        if (imageWidth == 0 || imageHeight == 0) return true  // If dimensions unknown, include it

        // Calculate center region boundaries
        val marginX = (imageWidth * (1 - CENTER_REGION_PERCENT) / 2).toInt()
        val marginY = (imageHeight * (1 - CENTER_REGION_PERCENT) / 2).toInt()

        val centerLeft = marginX
        val centerRight = imageWidth - marginX
        val centerTop = marginY
        val centerBottom = imageHeight - marginY

        // Check if barcode center is within center region
        val barcodeCenterX = boundingBox.centerX()
        val barcodeCenterY = boundingBox.centerY()

        return barcodeCenterX in centerLeft..centerRight &&
                barcodeCenterY in centerTop..centerBottom
    }

    /**
     * Calculate priority for a barcode (higher = more important)
     */
    private fun calculateBarcodePriority(barcode: Barcode): Int {
        val rawValue = barcode.rawValue ?: return PRIORITY_OTHER
        val format = barcode.format

        // Check if it matches valid identifiers (highest priority in auto-match mode)
        if (autoMatchEnabled && validIdentifiers.contains(rawValue)) {
            return 200
        }

        return when {
            // IMEI: 15 digits
            isImeiFormat(rawValue) -> PRIORITY_IMEI

            // Serial Number: alphanumeric mix
            isSerialNumberFormat(rawValue) -> PRIORITY_SERIAL

            // Product codes: EAN/UPC
            isProductCode(format, rawValue) -> PRIORITY_PRODUCT_CODE

            // Default
            else -> PRIORITY_OTHER
        }
    }

    /**
     * Check if value looks like an IMEI (14-16 digits, possibly with spaces/dashes)
     */
    private fun isImeiFormat(value: String): Boolean {
        // Remove common separators
        val cleaned = value.replace(" ", "").replace("-", "").replace("/", "")
        // IMEI is typically 15 digits, but can be 14 (without check digit) or 16 (with extra)
        return cleaned.length in 14..16 && cleaned.all { it.isDigit() }
    }

    /**
     * Check if value looks like a serial number (alphanumeric, 8-20 chars)
     */
    private fun isSerialNumberFormat(value: String): Boolean {
        return value.length in 8..20 &&
                value.any { it.isLetter() } &&
                value.any { it.isDigit() }
    }

    /**
     * Check if a barcode is a product code (EAN-13, UPC-A, etc.)
     */
    private fun isProductCode(format: Int, value: String): Boolean {
        return when (format) {
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E -> true
            else -> {
                // Also check by pattern: 12-13 digits (common for UPC/EAN)
                (value.length == 12 || value.length == 13) && value.all { it.isDigit() }
            }
        }
    }

    /**
     * Check if we already have an IMEI detected
     */
    private fun hasImeiDetected(): Boolean {
        return detectedBarcodes.values.any { it.smartLabel == "IMEI" }
    }

    private fun getFormatName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_UPC_A -> "UPC-A"
            Barcode.FORMAT_UPC_E -> "UPC-E"
            Barcode.FORMAT_EAN_13 -> "EAN-13"
            Barcode.FORMAT_EAN_8 -> "EAN-8"
            Barcode.FORMAT_CODE_128 -> "Code 128"
            Barcode.FORMAT_CODE_39 -> "Code 39"
            Barcode.FORMAT_CODE_93 -> "Code 93"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_QR_CODE -> "QR Code"
            Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
            Barcode.FORMAT_CODABAR -> "Codabar"
            Barcode.FORMAT_AZTEC -> "Aztec"
            Barcode.FORMAT_PDF417 -> "PDF417"
            else -> "Unknown"
        }
    }

    private fun getSmartLabel(value: String, format: Int): String {
        // Smart detection based on value characteristics
        return when {
            // IMEI detection: 14-16 digits
            isImeiFormat(value) -> "IMEI"

            // Serial number detection: alphanumeric, typically 8-20 chars
            isSerialNumberFormat(value) -> "Serial Number"

            // UPC/EAN detection based on format
            format == Barcode.FORMAT_UPC_A || format == Barcode.FORMAT_UPC_E -> "UPC (Product Code)"
            format == Barcode.FORMAT_EAN_13 || format == Barcode.FORMAT_EAN_8 -> "EAN (Product Code)"

            // EID detection: typically 32 digits
            value.length == 32 && value.all { it.isDigit() } -> "EID"

            // Default to format name
            else -> getFormatName(format)
        }
    }

    // ============================================================================
    // END OF PART 9: HELPER METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 10: BARCODE ADAPTER
    // ============================================================================

    inner class BarcodeAdapter(
        private val barcodes: List<BarcodeInfo>,
        private val onItemClick: (BarcodeInfo) -> Unit
    ) : RecyclerView.Adapter<BarcodeAdapter.BarcodeViewHolder>() {

        inner class BarcodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val valueText: TextView = itemView.findViewById(R.id.barcodeValueText)
            val typeText: TextView = itemView.findViewById(R.id.barcodeTypeText)
            val formatText: TextView = itemView.findViewById(R.id.barcodeFormatText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BarcodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_barcode_selection, parent, false)
            return BarcodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: BarcodeViewHolder, position: Int) {
            val barcode = barcodes[position]
            holder.valueText.text = barcode.rawValue
            holder.typeText.text = barcode.smartLabel
            holder.formatText.text = "(${barcode.formatName})"

            // Light green background color for matched items
            val lightGreenBg = android.graphics.Color.parseColor("#E8F5E9")

            // Color-code based on barcode type and add green check for IMEI/Serial
            when (barcode.smartLabel) {
                "IMEI" -> {
                    holder.typeText.setTextColor(ContextCompat.getColor(this@BarcodeScannerActivity, R.color.techcity_blue))
                    holder.typeText.text = "✓ IMEI"  // Green check for IMEI
                    holder.itemView.setBackgroundColor(lightGreenBg)
                }
                "Serial Number" -> {
                    holder.typeText.setTextColor(ContextCompat.getColor(this@BarcodeScannerActivity, R.color.teal_700))
                    holder.typeText.text = "✓ Serial Number"  // Green check for Serial
                    holder.itemView.setBackgroundColor(lightGreenBg)
                }
                "UPC (Product Code)", "EAN (Product Code)", "Product Code" -> {
                    holder.typeText.setTextColor(ContextCompat.getColor(this@BarcodeScannerActivity, android.R.color.darker_gray))
                    holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                else -> {
                    holder.typeText.setTextColor(ContextCompat.getColor(this@BarcodeScannerActivity, android.R.color.darker_gray))
                    holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            holder.itemView.setOnClickListener {
                onItemClick(barcode)
            }
        }

        override fun getItemCount() = barcodes.size
    }

    // ============================================================================
    // END OF PART 10: BARCODE ADAPTER
    // ============================================================================
}