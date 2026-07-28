package com.motioncam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class MotionCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Motion Camera",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuous motion-triggered recording"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "motioncam_service"
    }
}
