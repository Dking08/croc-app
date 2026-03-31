package com.crocworks.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TransferService : Service() {

    companion object {
        const val CHANNEL_ID = "croc_transfer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SEND = "com.crocworks.app.START_SEND"
        const val ACTION_START_RECEIVE = "com.crocworks.app.START_RECEIVE"
        const val ACTION_CANCEL = "com.crocworks.app.CANCEL"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SEND, ACTION_START_RECEIVE -> {
                val notification = createNotification(
                    if (intent.action == ACTION_START_SEND) "Sending files..."
                    else "Receiving files..."
                )
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_CANCEL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows ongoing file transfer progress"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("croc-app")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    fun updateProgress(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("croc-app")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun showComplete(fileName: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transfer Complete")
            .setContentText("$fileName transferred successfully")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)

        stopSelf()
    }
}
