package com.techcity.techcitysuite

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.techcity.techcitysuite.databinding.ActivityLedgerViewBinding
import com.techcity.techcitysuite.databinding.ItemLedgerEntryBinding

class LedgerViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLedgerViewBinding
    private var currentLedgerType: LedgerType = LedgerType.CASH
    private var showingAllCredits = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding
        binding = ActivityLedgerViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up UI
        setupUI()

        // Load initial ledger (Cash)
        loadLedger(LedgerType.CASH)
    }

    override fun onResume() {
        super.onResume()
        // Refresh the current ledger view when returning to this activity
        if (!showingAllCredits) {
            loadLedger(currentLedgerType)
        } else {
            showAllCredits()
        }
    }

    private fun setupUI() {
        // Set up ledger selection buttons
        binding.cashButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.CASH)
        }

        binding.gcashButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.GCASH)
        }

        binding.paymayaButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.PAYMAYA)
        }

        binding.othersButton.setOnClickListener {
            showingAllCredits = false
            loadLedger(LedgerType.OTHERS)
        }

        // Set up All Credits button
        binding.allCreditsButton.setOnClickListener {
            showAllCredits()
        }

        // Set up RecyclerView
        binding.ledgerRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun loadLedger(ledgerType: LedgerType) {
        currentLedgerType = ledgerType
        showingAllCredits = false

        // Update button states
        updateButtonStates(ledgerType)

        // Get ledger
        val ledger = LedgerManager.getLedger(ledgerType)

        // Update title
        binding.ledgerTitle.text = "${ledgerType.name} Ledger"

        // Calculate totals
        val entries = ledger.getEntriesSorted()
        val creditTotal = entries.filter { it.entryType == LedgerEntryType.CREDIT }
            .sumOf { it.amount }
        val debitTotal = entries.filter { it.entryType == LedgerEntryType.DEBIT }
            .sumOf { it.amount }

        // Update amounts
        binding.totalAmount.text = formatCurrency(ledger.balance)
        binding.creditAmount.text = formatCurrency(creditTotal)
        binding.debitAmount.text = formatCurrency(debitTotal)

        // Set total color based on value
        if (ledger.balance >= 0) {
            binding.totalAmount.setTextColor(
                ContextCompat.getColor(this, android.R.color.black)
            )
        } else {
            binding.totalAmount.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
        }

        // Load entries
        if (entries.isEmpty()) {
            binding.emptyMessage.visibility = View.VISIBLE
            binding.ledgerRecyclerView.visibility = View.GONE
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.ledgerRecyclerView.visibility = View.VISIBLE

            // Set adapter with showPaymentSource = true for all views now
            val adapter = LedgerEntriesAdapter(entries, showPaymentSource = true)
            binding.ledgerRecyclerView.adapter = adapter
        }
    }

    private fun showAllCredits() {
        showingAllCredits = true

        // Reset button states (none selected)
        binding.cashButton.alpha = 0.6f
        binding.gcashButton.alpha = 0.6f
        binding.paymayaButton.alpha = 0.6f
        binding.othersButton.alpha = 0.6f

        // Update title
        binding.ledgerTitle.text = "All Credit Transactions"

        // Collect all credit entries from all ledgers
        val allCreditEntries = mutableListOf<LedgerEntry>()
        val allLedgers = LedgerManager.getAllLedgers()

        allLedgers.forEach { (_, ledger) ->
            val creditEntries = ledger.entries.filter { it.entryType == LedgerEntryType.CREDIT }
            allCreditEntries.addAll(creditEntries)
        }

        // Sort by transaction number (descending)
        allCreditEntries.sortByDescending { it.transactionNumber }

        // Calculate totals
        val creditTotal = allCreditEntries.sumOf { it.amount }

        // Update amounts
        binding.totalAmount.text = formatCurrency(creditTotal)
        binding.creditAmount.text = formatCurrency(creditTotal)
        binding.debitAmount.text = formatCurrency(0.0)

        // Set total color
        binding.totalAmount.setTextColor(
            ContextCompat.getColor(this, android.R.color.black)
        )

        // Load entries
        if (allCreditEntries.isEmpty()) {
            binding.emptyMessage.text = "No credit transactions yet"
            binding.emptyMessage.visibility = View.VISIBLE
            binding.ledgerRecyclerView.visibility = View.GONE
        } else {
            binding.emptyMessage.visibility = View.GONE
            binding.ledgerRecyclerView.visibility = View.VISIBLE

            // Set adapter with showPaymentSource = true
            val adapter = LedgerEntriesAdapter(allCreditEntries, showPaymentSource = true)
            binding.ledgerRecyclerView.adapter = adapter
        }
    }

    private fun updateButtonStates(selectedType: LedgerType) {
        // Reset all button alphas
        binding.cashButton.alpha = 0.6f
        binding.gcashButton.alpha = 0.6f
        binding.paymayaButton.alpha = 0.6f
        binding.othersButton.alpha = 0.6f

        // Highlight selected button
        when (selectedType) {
            LedgerType.CASH -> binding.cashButton.alpha = 1.0f
            LedgerType.GCASH -> binding.gcashButton.alpha = 1.0f
            LedgerType.PAYMAYA -> binding.paymayaButton.alpha = 1.0f
            LedgerType.OTHERS -> binding.othersButton.alpha = 1.0f
        }
    }

    private fun formatCurrency(amount: Double): String {
        return "₱${String.format("%,.2f", amount)}"
    }

    /**
     * RecyclerView Adapter for Ledger Entries
     */
    inner class LedgerEntriesAdapter(
        private val entries: List<LedgerEntry>,
        private val showPaymentSource: Boolean = false
    ) : RecyclerView.Adapter<LedgerEntriesAdapter.ViewHolder>() {

        inner class ViewHolder(private val binding: ItemLedgerEntryBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: LedgerEntry) {
                // Set transaction number
                binding.transactionNumber.text = "#${entry.transactionNumber.toString().padStart(3, '0')}"

                // Set transaction type with color coding
                binding.transactionType.text = entry.transactionType

                // Apply color to transaction type based on the type
                val transactionTypeColor = when (entry.transactionType) {
                    "Cash In" -> ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                    "Cash Out" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Mobile Loading Service" -> ContextCompat.getColor(itemView.context, R.color.mobile_loading_purple)
                    "Skyro Payment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
                    "Home Credit Payment" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    "Misc Payment" -> ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                    else -> ContextCompat.getColor(itemView.context, android.R.color.black)
                }
                binding.transactionType.setTextColor(transactionTypeColor)

                // Show payment source badge - always visible now
                if (showPaymentSource) {
                    binding.paymentSource.visibility = View.VISIBLE
                    binding.paymentSource.text = entry.ledgerSource.name

                    // Create rounded background with appropriate color
                    val shape = GradientDrawable()
                    shape.cornerRadius = 20f
                    when (entry.ledgerSource) {
                        LedgerType.CASH -> shape.setColor(ContextCompat.getColor(itemView.context, R.color.cash_dark_green))
                        LedgerType.GCASH -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark))
                        LedgerType.PAYMAYA -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_light))
                        LedgerType.OTHERS -> shape.setColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                    }
                    binding.paymentSource.background = shape
                } else {
                    binding.paymentSource.visibility = View.GONE
                }

                // Set entry type (CREDIT or DEBIT)
                binding.entryType.text = entry.entryType.name

                // Set entry type color and background
                if (entry.entryType == LedgerEntryType.CREDIT) {
                    binding.entryType.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.green)
                    )
                    binding.entryType.setBackgroundResource(R.drawable.rounded_background_green)
                } else {
                    binding.entryType.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.red)
                    )
                    binding.entryType.setBackgroundResource(R.drawable.rounded_background_red)
                }

                // Set amount with proper formatting
                binding.amount.text = formatCurrency(entry.amount)

                // Set amount color based on entry type
                if (entry.entryType == LedgerEntryType.CREDIT) {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.green)
                    )
                } else {
                    binding.amount.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.red)
                    )
                }

                // Set notes if available
                if (entry.notes.isNotEmpty()) {
                    binding.notes.visibility = View.VISIBLE
                    binding.notes.text = "Notes: ${entry.notes}"
                } else {
                    binding.notes.visibility = View.GONE
                }

                // Set date and time
                binding.date.text = entry.date
                binding.time.text = entry.time
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLedgerEntryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size
    }
}