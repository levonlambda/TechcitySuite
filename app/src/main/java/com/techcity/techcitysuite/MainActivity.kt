package com.techcity.techcitysuite

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    // View Binding
    private lateinit var binding: ActivityMainBinding

    // Firebase Firestore instance
    private lateinit var db: FirebaseFirestore

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firestore
        db = Firebase.firestore

        // Set up search button click listener
        binding.searchButton.setOnClickListener {
            searchByIMEI()
        }

        // Optional: Allow Enter key to trigger search
        binding.imeiInput.setOnEditorActionListener { _, _, _ ->
            searchByIMEI()
            true
        }
    }

    private fun searchByIMEI() {
        // Get IMEI from input field
        val imei = binding.imeiInput.text.toString().trim()

        // Validate input
        if (imei.isEmpty()) {
            showMessage("Please enter an IMEI number", isError = true)
            return
        }

        if (imei.length != 15) {
            showMessage("IMEI must be exactly 15 digits", isError = true)
            return
        }

        // Hide keyboard
        hideKeyboard()

        // Reset UI
        hideAllResults()
        binding.progressBar.visibility = View.VISIBLE

        // Search in Firestore
        scope.launch {
            try {
                searchInFirestore(imei)
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                showMessage("Error: ${e.message}", isError = true)
            }
        }
    }

    private suspend fun searchInFirestore(imei: String) {
        withContext(Dispatchers.IO) {
            try {
                // Search in imei1 field
                val querySnapshot1 = db.collection("inventory")
                    .whereEqualTo("imei1", imei)
                    .get()
                    .await()

                // Search in imei2 field if not found in imei1
                val querySnapshot2 = if (querySnapshot1.isEmpty) {
                    db.collection("inventory")
                        .whereEqualTo("imei2", imei)
                        .get()
                        .await()
                } else {
                    querySnapshot1
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE

                    if (!querySnapshot1.isEmpty || !querySnapshot2.isEmpty) {
                        // Get the first matching document
                        val document = if (!querySnapshot1.isEmpty) {
                            querySnapshot1.documents[0]
                        } else {
                            querySnapshot2.documents[0]
                        }

                        // Convert to InventoryItem object
                        val item = document.toObject(InventoryItem::class.java)

                        if (item != null) {
                            displayResult(item)
                        } else {
                            showMessage("Error parsing phone data", isError = true)
                        }
                    } else {
                        showMessage("No phone found with IMEI: $imei", isError = false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    showMessage("Search failed: ${e.message}", isError = true)
                }
            }
        }
    }

    private fun displayResult(item: InventoryItem) {
        // Hide message and show result card
        binding.messageText.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE

        // Display the data
        binding.modelText.text = "${item.manufacturer} ${item.model}"
        binding.ramText.text = item.ram
        binding.storageText.text = item.storage
        binding.colorText.text = item.color
        binding.statusText.text = item.status

        // Set status color based on value
        when (item.status.lowercase()) {
            "on-hand", "available" -> {
                binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            }
            "sold" -> {
                binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            else -> {
                binding.statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            }
        }
    }

    private fun showMessage(message: String, isError: Boolean) {
        binding.messageText.visibility = View.VISIBLE
        binding.messageText.text = message
        binding.resultCard.visibility = View.GONE

        if (isError) {
            binding.messageText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            binding.messageText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun hideAllResults() {
        binding.resultCard.visibility = View.GONE
        binding.messageText.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.imeiInput.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel() // Cancel coroutines when activity is destroyed
    }
}