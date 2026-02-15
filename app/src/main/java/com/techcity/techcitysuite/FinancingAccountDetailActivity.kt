package com.techcity.techcitysuite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.techcity.techcitysuite.databinding.ActivityFinancingAccountDetailBinding

class FinancingAccountDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFinancingAccountDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinancingAccountDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton()
        populateFields()
        setupCopyButtons()
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener { finish() }
    }

    private fun populateFields() {
        val financingCompany = intent.getStringExtra("financing_company") ?: ""
        val customerName = intent.getStringExtra("customer_name") ?: ""
        val accountNumber = intent.getStringExtra("account_number") ?: ""
        val contactNumber = intent.getStringExtra("contact_number") ?: ""

        binding.financingCompanyValue.text = financingCompany
        binding.customerNameValue.text = customerName
        binding.accountNumberValue.text = accountNumber
        binding.contactNumberValue.text = contactNumber

        // Set badge text and color
        binding.financingCompanyBadge.text = financingCompany
        val badgeColor = when (financingCompany) {
            "Home Credit" -> ContextCompat.getColor(this, R.color.red)
            "Skyro" -> ContextCompat.getColor(this, R.color.skyro_light_blue)
            "Samsung Finance" -> ContextCompat.getColor(this, R.color.financing_teal)
            else -> ContextCompat.getColor(this, R.color.gray)
        }
        val badgeDrawable = binding.financingCompanyBadge.background.mutate()
        badgeDrawable.setTint(badgeColor)
        binding.financingCompanyBadge.background = badgeDrawable
    }

    private fun setupCopyButtons() {
        binding.copyCustomerNameButton.setOnClickListener {
            copyToClipboard("Customer Name", binding.customerNameValue.text.toString())
            Toast.makeText(this, getString(R.string.customer_name_copied), Toast.LENGTH_SHORT).show()
        }

        binding.copyAccountNumberButton.setOnClickListener {
            copyToClipboard("Account Number", binding.accountNumberValue.text.toString())
            Toast.makeText(this, getString(R.string.account_number_copied), Toast.LENGTH_SHORT).show()
        }

        binding.copyContactNumberButton.setOnClickListener {
            copyToClipboard("Contact Number", binding.contactNumberValue.text.toString())
            Toast.makeText(this, getString(R.string.contact_number_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
