package com.motioncam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.motioncam.util.L
import io.sentry.android.core.SentryAndroid

class MotionCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initSentry()
        createNotificationChannel()
        L.i("App", "MotionCam started (v${BuildConfig.VERSION_NAME})")
    }

    private fun initSentry() {
        SentryAndroid.init(this) { options ->
            options.dsn =
                "https://803c8318db18615acfa54f21531eb7b6@o1341921.ingest.us.sentry.io/4511817458909184"
            options.isAnrEnabled = true
            options.isAttachStacktrace = true
            options.environment = "production"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
        }
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
