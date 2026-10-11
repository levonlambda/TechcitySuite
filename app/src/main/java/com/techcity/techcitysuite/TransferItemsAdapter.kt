package com.techcity.techcitysuite

import android.content.Context
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.techcity.techcitysuite.databinding.ItemTransferAccessoryBinding
import com.techcity.techcitysuite.databinding.ItemTransferPhoneBinding
import com.techcity.techcitysuite.databinding.ItemTransferSectionHeaderBinding

/**
 * RecyclerView adapter shared by the New Stock Transfer (draft) and Stock Transfer Detail
 * screens. Renders a flat list: [Phones header, phones..., Accessories header, accessories...].
 */
class TransferItemsAdapter(
    private val context: Context,
    private val mode: Mode,
    private val onPhoneClick: ((TransferPhoneItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Mode { DRAFT, DETAIL }

    sealed class Row {
        data class Header(val title: CharSequence, val colorRes: Int = R.color.techcity_blue) : Row()
        data class Phone(val item: TransferPhoneItem) : Row()
        data class Accessory(
            val item: TransferAccessoryItem,
            val availableOnHand: Int,
            val availableOnDisplay: Int
        ) : Row()
    }

    private val rows = mutableListOf<Row>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHONE = 1
        private const val TYPE_ACCESSORY = 2
    }

    // ============================================================================
    // START OF PART 1: DATA
    // ============================================================================

    /**
     * Rebuild the flat row list. availability (sku -> onHand/onDisplay at origin) is only
     * used in DRAFT mode.
     */
    fun submit(
        phones: List<TransferPhoneItem>,
        accessories: List<TransferAccessoryItem>,
        availability: Map<String, Pair<Int, Int>> = emptyMap()
    ) {
        rows.clear()
        if (phones.isNotEmpty()) {
            rows.add(Row.Header(highlightNumbers("PHONES (${phones.size})")))
            phones.forEach { rows.add(Row.Phone(it)) }
        }
        if (accessories.isNotEmpty()) {
            val units = accessories.sumOf { it.quantity }
            rows.add(Row.Header(highlightNumbers("ACCESSORIES (${accessories.size} items · $units units)"), R.color.orange))
            accessories.forEach {
                val avail = availability[it.sku] ?: Pair(0, 0)
                rows.add(Row.Accessory(it, avail.first, avail.second))
            }
        }
        notifyDataSetChanged()
    }

    fun rowAt(position: Int): Row? = rows.getOrNull(position)

    fun isSwipeable(position: Int): Boolean = rows.getOrNull(position) !is Row.Header

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Phone -> TYPE_PHONE
        is Row.Accessory -> TYPE_ACCESSORY
    }

    // ============================================================================
    // END OF PART 1: DATA
    // ============================================================================


    // ============================================================================
    // START OF PART 2: VIEW HOLDERS
    // ============================================================================

    inner class HeaderViewHolder(val binding: ItemTransferSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    inner class PhoneViewHolder(val binding: ItemTransferPhoneBinding) : RecyclerView.ViewHolder(binding.root)
    inner class AccessoryViewHolder(val binding: ItemTransferAccessoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemTransferSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_PHONE -> PhoneViewHolder(ItemTransferPhoneBinding.inflate(inflater, parent, false))
            else -> AccessoryViewHolder(ItemTransferAccessoryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> {
                val title = (holder as HeaderViewHolder).binding.sectionTitle
                title.text = row.title
                title.setTextColor(color(row.colorRes))
            }
            is Row.Phone -> bindPhone((holder as PhoneViewHolder).binding, row.item)
            is Row.Accessory -> bindAccessory((holder as AccessoryViewHolder).binding, row)
        }
    }

    private fun bindPhone(binding: ItemTransferPhoneBinding, item: TransferPhoneItem) {
        with(binding) {
            manufacturerText.text = item.manufacturer
            modelText.text = item.model

            val ramDisplay = if (item.ram.endsWith("GB", ignoreCase = true)) item.ram else "${item.ram}GB"
            val storageDisplay = if (item.storage.endsWith("GB", ignoreCase = true)) item.storage else "${item.storage}GB"
            ramStorageText.text = "$ramDisplay + $storageDisplay"
            colorText.text = item.color

            // Status badge shows the status the phone had at the origin
            when (item.originalStatus) {
                AppConstants.INVENTORY_STATUS_ON_HAND -> {
                    statusBadge.text = "In-Stock"
                    statusBadge.backgroundTintList = ColorStateList.valueOf(color(R.color.techcity_blue))
                    statusBadge.setTextColor(color(R.color.white))
                }
                AppConstants.INVENTORY_STATUS_ON_DISPLAY -> {
                    statusBadge.text = "On-Display"
                    statusBadge.backgroundTintList = ColorStateList.valueOf(color(R.color.yellow))
                    statusBadge.setTextColor(color(R.color.black))
                }
                else -> {
                    statusBadge.text = item.originalStatus
                    statusBadge.backgroundTintList = ColorStateList.valueOf(color(R.color.gray))
                    statusBadge.setTextColor(color(R.color.white))
                }
            }

            if (item.imei1.isNotEmpty()) {
                imei1Row.visibility = View.VISIBLE
                imei1Text.text = item.imei1
            } else {
                imei1Row.visibility = View.GONE
            }
            if (item.imei2.isNotEmpty()) {
                imei2Row.visibility = View.VISIBLE
                imei2Text.text = item.imei2
            } else {
                imei2Row.visibility = View.GONE
            }
            if (item.serialNumber.isNotEmpty()) {
                serialRow.visibility = View.VISIBLE
                serialText.text = item.serialNumber
            } else {
                serialRow.visibility = View.GONE
            }

            if (mode == Mode.DRAFT) {
                verificationFooter.visibility = View.GONE
                cardView.strokeColor = color(R.color.light_gray)
                cardView.strokeWidth = 2
            } else {
                verificationFooter.visibility = View.VISIBLE
                when {
                    item.missing -> {
                        cardView.strokeColor = color(android.R.color.holo_red_dark)
                        cardView.strokeWidth = 4
                        notVerifiedText.visibility = View.GONE
                        verifiedLayout.visibility = View.GONE
                        missingText.visibility = View.VISIBLE
                    }
                    item.verified -> {
                        cardView.strokeColor = color(R.color.cash_dark_green)
                        cardView.strokeWidth = 4
                        notVerifiedText.visibility = View.GONE
                        verifiedLayout.visibility = View.VISIBLE
                        missingText.visibility = View.GONE
                        verifiedByText.text = "Verified by ${item.verifiedBy}"
                    }
                    else -> {
                        cardView.strokeColor = color(R.color.light_gray)
                        cardView.strokeWidth = 2
                        notVerifiedText.visibility = View.VISIBLE
                        verifiedLayout.visibility = View.GONE
                        missingText.visibility = View.GONE
                    }
                }
            }
            verifiedCheck.visibility =
                if (mode == Mode.DETAIL && item.verified && !item.missing) View.VISIBLE else View.GONE

            root.setOnClickListener { onPhoneClick?.invoke(item) }
        }
    }

    private fun bindAccessory(binding: ItemTransferAccessoryBinding, row: Row.Accessory) {
        val item = row.item
        with(binding) {
            nameText.text = item.displayName.ifBlank { item.sku }
            skuText.text = "SKU: ${item.sku}"
            barcodeText.text = item.barcode

            if (mode == Mode.DRAFT) {
                quantityText.text = highlightNumbers("Qty: ${item.quantity} · Available: ${row.availableOnHand} on hand / ${row.availableOnDisplay} on display")
                receivedCheck.visibility = View.GONE
                receivedByText.visibility = View.GONE
                missingText.visibility = View.GONE
                cardView.strokeColor = color(R.color.light_gray)
                cardView.strokeWidth = 2
            } else {
                quantityText.text = highlightNumbers("Received ${item.receivedQuantity} / ${item.quantity}")
                val complete = item.quantity > 0 && item.receivedQuantity >= item.quantity
                receivedCheck.visibility = if (complete) View.VISIBLE else View.GONE
                if (item.receivedQuantity > 0 && item.lastReceivedBy.isNotBlank()) {
                    receivedByText.visibility = View.VISIBLE
                    receivedByText.text = "Verified by ${item.lastReceivedBy}"
                } else {
                    receivedByText.visibility = View.GONE
                }
                if (item.missingQuantity > 0) {
                    missingText.visibility = View.VISIBLE
                    missingText.text = "Missing: ${item.missingQuantity}"
                    cardView.strokeColor = color(android.R.color.holo_red_dark)
                    cardView.strokeWidth = 4
                } else {
                    missingText.visibility = View.GONE
                    cardView.strokeColor = if (complete) color(R.color.cash_dark_green) else color(R.color.light_gray)
                    cardView.strokeWidth = if (complete) 4 else 2
                }
            }

            root.setOnClickListener(null)
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    /** Colors every number in the text black so quantities stand out from the surrounding label. */
    private fun highlightNumbers(text: String): CharSequence {
        val spannable = SpannableString(text)
        Regex("\\d+").findAll(text).forEach { match ->
            spannable.setSpan(
                ForegroundColorSpan(color(R.color.black)),
                match.range.first,
                match.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    // ============================================================================
    // END OF PART 2: VIEW HOLDERS
    // ============================================================================
}


// ============================================================================
// START OF PART 3: SHARED QUANTITY DIALOG
// ============================================================================

/**
 * Quantity dialog shared by the sending flow ("Transfer Quantity") and the receiving flow
 * ("Received Quantity"). Validates a whole number between 1 and max.
 */
object TransferQuantityDialog {

    fun show(
        activity: android.app.Activity,
        title: String,
        itemName: String,
        stockInfo: String,
        prefill: Int,
        max: Int,
        submitLabel: String,
        onConfirm: (Int) -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_transfer_quantity, null)

        val titleText = dialogView.findViewById<android.widget.TextView>(R.id.titleText)
        val itemNameText = dialogView.findViewById<android.widget.TextView>(R.id.itemNameText)
        val stockInfoText = dialogView.findViewById<android.widget.TextView>(R.id.stockInfoText)
        val quantityInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.quantityInput)
        val errorMessage = dialogView.findViewById<android.widget.TextView>(R.id.errorMessage)
        val submitButton = dialogView.findViewById<android.widget.Button>(R.id.submitButton)
        val cancelButton = dialogView.findViewById<android.widget.Button>(R.id.cancelButton)

        titleText.text = title
        itemNameText.text = itemName
        stockInfoText.text = stockInfo
        submitButton.text = submitLabel
        if (prefill > 0) {
            quantityInput.setText(prefill.toString())
            quantityInput.setSelection(quantityInput.text?.length ?: 0)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }

        submitButton.setOnClickListener {
            val value = quantityInput.text.toString().trim().toIntOrNull()
            if (value == null || value < 1 || value > max) {
                errorMessage.text = "Enter a quantity between 1 and $max"
                errorMessage.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirm(value)
        }

        quantityInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitButton.performClick()
                true
            } else {
                false
            }
        }

        dialog.show()
        quantityInput.requestFocus()
    }
}

// ============================================================================
// END OF PART 3: SHARED QUANTITY DIALOG
// ============================================================================
