package com.motioncam.util

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Central logging facade. Writes to Logcat and forwards to Sentry as breadcrumbs
 * (so the last actions are attached to any crash/report) and, for warnings/errors,
 * as captured events. Keep tags short and per-subsystem.
 */
object L {

    fun d(tag: String, msg: String) {
        Log.d(full(tag), msg)
        breadcrumb(tag, msg, SentryLevel.DEBUG)
    }

    fun i(tag: String, msg: String) {
        Log.i(full(tag), msg)
        breadcrumb(tag, msg, SentryLevel.INFO)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(full(tag), msg, t)
        breadcrumb(tag, msg, SentryLevel.WARNING)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(full(tag), msg, t)
        breadcrumb(tag, msg, SentryLevel.ERROR)
        try {
            if (t != null) Sentry.captureException(t) else Sentry.captureMessage(msg, SentryLevel.ERROR)
        } catch (_: Throwable) {
        }
    }

    private fun breadcrumb(tag: String, msg: String, level: SentryLevel) {
        try {
            val crumb = io.sentry.Breadcrumb().apply {
                category = tag
                message = msg
                this.level = level
            }
            Sentry.addBreadcrumb(crumb)
        } catch (_: Throwable) {
        }
    }

    private fun full(tag: String) = "MotionCam/$tag"
}
