package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.techcity.techcitysuite.databinding.ActivityMenuBinding

class MenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Clear all ledgers on app start (for testing purposes)
        LedgerManager.clearAll()

        // Set up click listeners for menu options
        setupMenuButtons()
    }

    private fun setupMenuButtons() {
        // Phone Inventory Checker button (your existing IMEI search)
        binding.inventoryButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        // Cash Transactions button
        binding.transactionsButton.setOnClickListener {
            val intent = Intent(this, TransactionTypeActivity::class.java)
            startActivity(intent)
        }

        // Ledger button
        binding.ledgerButton.setOnClickListener {
            val intent = Intent(this, LedgerViewActivity::class.java)
            startActivity(intent)
        }
    }
}