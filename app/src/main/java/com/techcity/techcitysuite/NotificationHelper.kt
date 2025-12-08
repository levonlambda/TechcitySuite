package com.techcity.techcitysuite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Helper class for managing notifications
 */
object NotificationHelper {

    private const val CHANNEL_ID_DEVICE_TRANSACTIONS = "device_transactions_channel"
    private const val CHANNEL_NAME_DEVICE_TRANSACTIONS = "Device Transactions"
    private const val CHANNEL_DESC_DEVICE_TRANSACTIONS = "Notifications for new device transactions"

    private var notificationId = 1000

    /**
     * Create notification channels (call this once at app startup)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_DEVICE_TRANSACTIONS,
                CHANNEL_NAME_DEVICE_TRANSACTIONS,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESC_DEVICE_TRANSACTIONS
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Check if notifications are enabled in settings
     */
    fun areDeviceTransactionNotificationsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(AppConstants.KEY_DEVICE_TRANSACTION_NOTIFICATIONS_ENABLED, true)
    }

    /**
     * Check if we have notification permission (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission not required before Android 13
        }
    }

    /**
     * Show notification for a new device transaction
     */
    fun showDeviceTransactionNotification(
        context: Context,
        model: String,
        price: Double,
        transactionType: String
    ) {
        // Check if notifications are enabled
        if (!areDeviceTransactionNotificationsEnabled(context)) {
            return
        }

        // Check permission
        if (!hasNotificationPermission(context)) {
            return
        }

        // Format price
        val formattedPrice = "₱${String.format("%,.2f", price)}"

        // Create intent to open app when notification is tapped
        val intent = Intent(context, DeviceTransactionListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DEVICE_TRANSACTIONS)
            .setSmallIcon(R.drawable.ic_assessment)
            .setContentTitle("New Device Transaction")
            .setContentText("$model • $formattedPrice • $transactionType")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Model: $model\nPrice: $formattedPrice\nType: $transactionType"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Show notification
        try {
            NotificationManagerCompat.from(context).notify(notificationId++, notification)
        } catch (e: SecurityException) {
            // Permission denied
            e.printStackTrace()
        }
    }
}