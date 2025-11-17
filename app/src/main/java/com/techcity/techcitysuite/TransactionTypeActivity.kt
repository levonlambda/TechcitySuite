package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.techcity.techcitysuite.databinding.ActivityTransactionTypeBinding

class TransactionTypeActivity : AppCompatActivity() {


    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityTransactionTypeBinding


    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================



    // ============================================================================
    // START OF PART 2: LIFECYCLE METHODS
    // ============================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityTransactionTypeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up click listeners
        binding.cashInButton.setOnClickListener {
            openTransactionDetails("Cash In")
        }

        binding.cashOutButton.setOnClickListener {
            openTransactionDetails("Cash Out")
        }

        binding.mobileLoadingButton.setOnClickListener {
            openTransactionDetails("Mobile Loading Service")
        }

        binding.skyroButton.setOnClickListener {
            openTransactionDetails("Skyro Payment")
        }

        binding.homeCreditButton.setOnClickListener {
            openTransactionDetails("Home Credit Payment")
        }

        binding.miscPaymentButton.setOnClickListener {
            openTransactionDetails("Misc Payment")
        }
    }


    // ============================================================================
    // END OF PART 2: LIFECYCLE METHODS
    // ============================================================================



    // ============================================================================
    // START OF PART 3: NAVIGATION METHODS
    // ============================================================================

    private fun openTransactionDetails(transactionType: String) {
        val intent = Intent(this, TransactionDetailsActivity::class.java)
        intent.putExtra("TRANSACTION_TYPE", transactionType)
        startActivity(intent)
    }

    // ============================================================================
    // END OF PART 3: NAVIGATION METHODS
    // ============================================================================


}