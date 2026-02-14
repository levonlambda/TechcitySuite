package com.techcity.techcitysuite

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.techcity.techcitysuite.databinding.ActivityFinancingAccountListBinding
import com.techcity.techcitysuite.databinding.ItemFinancingAccountBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class FinancingAccountListActivity : AppCompatActivity() {

    // ============================================================================
    // START OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================

    private lateinit var binding: ActivityFinancingAccountListBinding
    private lateinit var db: FirebaseFirestore
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    enum class FinancingFilter {
        ALL, HOME_CREDIT, SKYRO, SAMSUNG_FINANCE
    }

    private var currentFilter: FinancingFilter = FinancingFilter.ALL
    private var allAccounts: MutableList<FinancingAccount> = mutableListOf()
    private var filteredAccounts: MutableList<FinancingAccount> = mutableListOf()
    private var adapter: FinancingAccountAdapter? = null

    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    private val storageDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinancingAccountListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Firebase.firestore

        setupBackButton()
        setupSearch()
        setupFilterButtons()
        setupRecyclerView()
        setupFab()
        loadAccounts()
    }

    override fun onResume() {
        super.onResume()
        loadAccounts()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ============================================================================
    // END OF PART 1: PROPERTIES AND INITIALIZATION
    // ============================================================================


    // ============================================================================
    // START OF PART 2: SETUP METHODS
    // ============================================================================

    private fun setupBackButton() {
        binding.backButton.setOnClickListener { finish() }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilters()
            }
        })
    }

    private fun setupFilterButtons() {
        binding.filterAllButton.setOnClickListener { selectFilter(FinancingFilter.ALL) }
        binding.filterHomeCreditButton.setOnClickListener { selectFilter(FinancingFilter.HOME_CREDIT) }
        binding.filterSkyroButton.setOnClickListener { selectFilter(FinancingFilter.SKYRO) }
        binding.filterSamsungButton.setOnClickListener { selectFilter(FinancingFilter.SAMSUNG_FINANCE) }

        updateFilterButtonStyles()
    }

    private fun setupRecyclerView() {
        adapter = FinancingAccountAdapter()
        binding.accountsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.accountsRecyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.addButton.setOnClickListener {
            val intent = Intent(this, AddFinancingAccountActivity::class.java)
            startActivity(intent)
        }
    }

    // ============================================================================
    // END OF PART 2: SETUP METHODS
    // ============================================================================


    // ============================================================================
    // START OF PART 3: DATA LOADING
    // ============================================================================

    private fun loadAccounts() {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyMessage.visibility = View.GONE
        binding.accountsRecyclerView.visibility = View.GONE

        scope.launch {
            try {
                val accounts = withContext(Dispatchers.IO) {
                    val snapshot = db.collection(AppConstants.COLLECTION_FINANCING_ACCOUNTS)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .get()
                        .await()

                    snapshot.documents.map { doc ->
                        FinancingAccount(
                            id = doc.id,
                            financingCompany = doc.getString("financingCompany") ?: "",
                            customerName = doc.getString("customerName") ?: "",
                            accountNumber = doc.getString("accountNumber") ?: "",
                            purchaseDate = doc.getString("purchaseDate") ?: "",
                            contactNumber = doc.getString("contactNumber") ?: "",
                            devicePurchased = doc.getString("devicePurchased"),
                            monthlyPayment = doc.getDouble("monthlyPayment"),
                            term = doc.getString("term"),
                            downpayment = doc.getDouble("downpayment"),
                            createdAt = doc.getTimestamp("createdAt"),
                            createdBy = doc.getString("createdBy") ?: "",
                            storeLocation = doc.getString("storeLocation") ?: ""
                        )
                    }
                }

                allAccounts.clear()
                allAccounts.addAll(accounts)
                applyFilters()

            } catch (e: Exception) {
                Toast.makeText(this@FinancingAccountListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    // ============================================================================
    // END OF PART 3: DATA LOADING
    // ============================================================================


    // ============================================================================
    // START OF PART 4: FILTERING
    // ============================================================================

    private fun selectFilter(filter: FinancingFilter) {
        currentFilter = filter
        updateFilterButtonStyles()
        applyFilters()
    }

    private fun applyFilters() {
        val searchText = binding.searchEditText.text?.toString()?.lowercase(Locale.getDefault()) ?: ""

        filteredAccounts.clear()
        filteredAccounts.addAll(allAccounts.filter { account ->
            // Apply company filter
            val matchesFilter = when (currentFilter) {
                FinancingFilter.ALL -> true
                FinancingFilter.HOME_CREDIT -> account.financingCompany == "Home Credit"
                FinancingFilter.SKYRO -> account.financingCompany == "Skyro"
                FinancingFilter.SAMSUNG_FINANCE -> account.financingCompany == "Samsung Finance"
            }

            // Apply search
            val matchesSearch = if (searchText.isEmpty()) {
                true
            } else {
                account.customerName.lowercase(Locale.getDefault()).contains(searchText) ||
                account.accountNumber.lowercase(Locale.getDefault()).contains(searchText) ||
                account.contactNumber.lowercase(Locale.getDefault()).contains(searchText) ||
                account.financingCompany.lowercase(Locale.getDefault()).contains(searchText)
            }

            matchesFilter && matchesSearch
        })

        adapter?.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (filteredAccounts.isEmpty()) {
            binding.accountsRecyclerView.visibility = View.GONE
            binding.emptyMessage.visibility = View.VISIBLE
        } else {
            binding.accountsRecyclerView.visibility = View.VISIBLE
            binding.emptyMessage.visibility = View.GONE
        }
    }

    private fun updateFilterButtonStyles() {
        val activeAlpha = 1.0f
        val inactiveAlpha = 0.5f

        binding.filterAllButton.alpha = if (currentFilter == FinancingFilter.ALL) activeAlpha else inactiveAlpha
        binding.filterHomeCreditButton.alpha = if (currentFilter == FinancingFilter.HOME_CREDIT) activeAlpha else inactiveAlpha
        binding.filterSkyroButton.alpha = if (currentFilter == FinancingFilter.SKYRO) activeAlpha else inactiveAlpha
        binding.filterSamsungButton.alpha = if (currentFilter == FinancingFilter.SAMSUNG_FINANCE) activeAlpha else inactiveAlpha
    }

    // ============================================================================
    // END OF PART 4: FILTERING
    // ============================================================================


    // ============================================================================
    // START OF PART 5: RECYCLER VIEW ADAPTER
    // ============================================================================

    inner class FinancingAccountAdapter : RecyclerView.Adapter<FinancingAccountAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemFinancingAccountBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemFinancingAccountBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = filteredAccounts[position]
            val b = holder.binding

            // Customer name
            b.customerNameText.text = account.customerName

            // Account number
            b.accountNumberText.text = account.accountNumber

            // Contact number
            b.contactNumberText.text = account.contactNumber

            // Date of purchase - format for display
            b.purchaseDateText.text = formatDisplayDate(account.purchaseDate)

            // Device purchased (conditional visibility)
            if (!account.devicePurchased.isNullOrBlank()) {
                b.devicePurchasedText.text = account.devicePurchased
                b.devicePurchasedText.visibility = View.VISIBLE
            } else {
                b.devicePurchasedText.visibility = View.GONE
            }

            // Financing company badge
            b.financingCompanyBadge.text = account.financingCompany
            val badgeColor = when (account.financingCompany) {
                "Home Credit" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.red)
                "Skyro" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.skyro_light_blue)
                "Samsung Finance" -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.financing_teal)
                else -> ContextCompat.getColor(this@FinancingAccountListActivity, R.color.gray)
            }
            val badgeDrawable = b.financingCompanyBadge.background.mutate()
            badgeDrawable.setTint(badgeColor)
            b.financingCompanyBadge.background = badgeDrawable

            // Item click
            holder.itemView.setOnClickListener {
                Toast.makeText(this@FinancingAccountListActivity, getString(R.string.detail_view_coming_soon), Toast.LENGTH_SHORT).show()
            }
        }

        override fun getItemCount(): Int = filteredAccounts.size
    }

    private fun formatDisplayDate(dateString: String): String {
        return try {
            val date = storageDateFormat.parse(dateString)
            if (date != null) displayDateFormat.format(date) else dateString
        } catch (e: Exception) {
            dateString
        }
    }

    // ============================================================================
    // END OF PART 5: RECYCLER VIEW ADAPTER
    // ============================================================================
}
