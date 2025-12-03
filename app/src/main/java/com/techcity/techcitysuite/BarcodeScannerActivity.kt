package com.techcity.techcitysuite

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import com.techcity.techcitysuite.databinding.ActivityBarcodeScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BarcodeScannerActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityBarcodeScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    private var camera: Camera? = null
    private var isProcessingFrame = false
    private var isDialogShowing = false

    // Store detected barcodes from continuous scanning
    private val detectedBarcodes = mutableMapOf<String, BarcodeInfo>()

    // Track how many times each barcode has been detected (for confidence)
    private val barcodeDetectionCount = mutableMapOf<String, Int>()

    // Minimum detection count before considering a barcode as "confirmed"
    private val MIN_DETECTION_COUNT = 2

    // Data class to hold barcode information
    data class BarcodeInfo(
        val rawValue: String,
        val format: Int,
        val formatName: String,
        val smartLabel: String
    )

    companion object {
        private const val TAG = "BarcodeScannerActivity"
        const val RESULT_BARCODE_VALUE = "barcode_value"
        const val RESULT_BARCODE_TYPE = "barcode_type"
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBarcodeScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize barcode scanner with multiple formats
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Setup UI
        setupUI()

        // Check camera permission
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: PERMISSION HANDLING
    // ============================================================================

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required for barcode scanning", Toast.LENGTH_LONG).show()
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

        // Capture button - now shows detected barcodes
        binding.captureButton.setOnClickListener {
            showDetectedBarcodes()
        }

        // Flash toggle button
        binding.flashButton.setOnClickListener {
            toggleFlash()
        }

        // Update instruction text
        binding.instructionText.text = "Point camera at barcode\nScanning automatically..."
    }

    private fun toggleFlash() {
        camera?.let { cam ->
            val currentState = cam.cameraInfo.torchState.value == TorchState.ON
            cam.cameraControl.enableTorch(!currentState)

            // Update flash icon
            binding.flashButton.setImageResource(
                if (!currentState) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
        }
    }

    private fun updateBarcodeDisplay() {
        val confirmedCount = detectedBarcodes.size
        runOnUiThread {
            if (confirmedCount > 0) {
                // Build the display text with barcode list
                val sb = StringBuilder()
                sb.append("✓ $confirmedCount barcode(s) found:\n")

                // Add each barcode to the list
                detectedBarcodes.values.forEachIndexed { index, barcodeInfo ->
                    // Truncate long values for display
                    val displayValue = if (barcodeInfo.rawValue.length > 20) {
                        barcodeInfo.rawValue.take(17) + "..."
                    } else {
                        barcodeInfo.rawValue
                    }
                    sb.append("• ${barcodeInfo.smartLabel}: $displayValue")
                    if (index < detectedBarcodes.size - 1) {
                        sb.append("\n")
                    }
                }

                binding.instructionText.text = sb.toString()
                binding.captureButton.alpha = 1.0f
            } else {
                binding.instructionText.text = "Point camera at barcode\nScanning automatically..."
                binding.captureButton.alpha = 0.6f
            }
        }
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

            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image analysis use case for continuous scanning
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ============================================================================
    // END OF PART 5: CAMERA SETUP WITH CONTINUOUS SCANNING
    // ============================================================================


    // ============================================================================
    // START OF PART 6: CONTINUOUS BARCODE SCANNING
    // ============================================================================

    @androidx.camera.core.ExperimentalGetImage
    private fun processFrame(imageProxy: ImageProxy) {
        // Skip if already processing or dialog is showing
        if (isProcessingFrame || isDialogShowing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessingFrame = true

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                processBarcodeResults(barcodes)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Barcode scanning failed", e)
            }
            .addOnCompleteListener {
                isProcessingFrame = false
                imageProxy.close()
            }
    }

    private fun processBarcodeResults(barcodes: List<Barcode>) {
        if (barcodes.isEmpty()) {
            return
        }

        var newBarcodesAdded = false

        for (barcode in barcodes) {
            val rawValue = barcode.rawValue ?: continue

            // Increment detection count
            val currentCount = barcodeDetectionCount.getOrDefault(rawValue, 0) + 1
            barcodeDetectionCount[rawValue] = currentCount

            // Only add to confirmed list if detected multiple times (reduces false positives)
            if (currentCount >= MIN_DETECTION_COUNT && !detectedBarcodes.containsKey(rawValue)) {
                val format = barcode.format
                val formatName = getFormatName(format)
                val smartLabel = getSmartLabel(rawValue, format)

                detectedBarcodes[rawValue] = BarcodeInfo(rawValue, format, formatName, smartLabel)
                newBarcodesAdded = true

                Log.d(TAG, "Confirmed barcode: $rawValue ($smartLabel)")
            }
        }

        if (newBarcodesAdded) {
            updateBarcodeDisplay()
        }
    }

    // ============================================================================
    // END OF PART 6: CONTINUOUS BARCODE SCANNING
    // ============================================================================


    // ============================================================================
    // START OF PART 7: BARCODE SELECTION DIALOG
    // ============================================================================

    private fun showDetectedBarcodes() {
        if (detectedBarcodes.isEmpty()) {
            Toast.makeText(this, "No barcodes detected yet. Keep scanning...", Toast.LENGTH_SHORT).show()
            return
        }

        isDialogShowing = true

        val barcodeList = detectedBarcodes.values.toList()

        // If only one barcode, return it directly
        if (barcodeList.size == 1) {
            returnBarcodeResult(barcodeList[0])
            return
        }

        // Show selection dialog for multiple barcodes
        val dialogView = layoutInflater.inflate(R.layout.dialog_barcode_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.barcodeRecyclerView)
        val countText = dialogView.findViewById<TextView>(R.id.barcodeCountText)

        countText.text = "${barcodeList.size} barcodes detected - tap to select"

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

    private fun returnBarcodeResult(barcodeInfo: BarcodeInfo) {
        val resultIntent = Intent().apply {
            putExtra(RESULT_BARCODE_VALUE, barcodeInfo.rawValue)
            putExtra(RESULT_BARCODE_TYPE, barcodeInfo.smartLabel)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // ============================================================================
    // END OF PART 7: BARCODE SELECTION DIALOG
    // ============================================================================


    // ============================================================================
    // START OF PART 8: HELPER METHODS
    // ============================================================================

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
            else -> "Unknown"
        }
    }

    private fun getSmartLabel(value: String, format: Int): String {
        // Smart detection based on value characteristics
        return when {
            // IMEI detection: 15 digits
            value.length == 15 && value.all { it.isDigit() } -> "IMEI"

            // Serial number detection: alphanumeric, typically 8-12 chars
            value.length in 8..12 && value.any { it.isLetter() } && value.any { it.isDigit() } -> "Serial Number"

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
    // END OF PART 8: HELPER METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 9: BARCODE ADAPTER
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

            // Highlight IMEI entries
            if (barcode.smartLabel == "IMEI") {
                holder.typeText.setTextColor(ContextCompat.getColor(this@BarcodeScannerActivity, R.color.techcity_blue))
            } else {
                holder.typeText.setTextColor(ContextCompat.getColor(this@BarcodeScannerActivity, android.R.color.darker_gray))
            }

            holder.itemView.setOnClickListener {
                onItemClick(barcode)
            }
        }

        override fun getItemCount() = barcodes.size
    }

    // ============================================================================
    // END OF PART 9: BARCODE ADAPTER
    // ============================================================================
}