package com.motioncam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the camera service after the phone reboots so recording resumes unattended. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CameraService.start(context)
        }
    }
}
