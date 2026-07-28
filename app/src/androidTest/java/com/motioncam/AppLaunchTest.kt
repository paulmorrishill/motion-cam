package com.motioncam

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.motioncam.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: the app launches with permissions granted, starts the
 * camera service and renders the main recording UI (status header + controls).
 * Runs on a real Android emulator in CI.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainScreenShowsControls() {
        // The service connects asynchronously; wait for the UI to settle.
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("Settings").fetchSemanticsNodes().isNotEmpty()
        }

        // Default keep-screen mode is OFF, which shows an all-black "asleep"
        // overlay (burn-in protection) that intercepts taps. Deterministically
        // dismiss it (tap to wake) before interacting with the controls.
        dismissSleepOverlay()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Uploads").assertIsDisplayed()

        captureScreenshot("1_main_screen")

        // Visit Uploads first — its "Back" button is at the top (always on screen),
        // so returning to the main screen is reliable.
        composeRule.onNodeWithText("Uploads").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("Queue").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        captureScreenshot("2_uploads_screen")
        composeRule.onNodeWithText("Back").performClick()

        // Back on main, open Settings and capture it last (no return needed).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("Settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("FTP upload").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        captureScreenshot("3_settings_screen")
    }

    /** Taps the centre of the screen until the black "asleep" overlay is gone,
     *  so subsequent control taps land on the actual buttons. */
    private fun dismissSleepOverlay() {
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(inst)
        repeat(10) {
            val present = composeRule
                .onAllNodes(androidx.compose.ui.test.hasTestTag("sleep_overlay"))
                .fetchSemanticsNodes().isNotEmpty()
            if (!present) return
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            composeRule.waitForIdle()
            Thread.sleep(300)
        }
    }

    /** Best-effort UI screenshot for evidence; never fails the test. Written via
     *  MediaStore to shared Pictures/motioncam so it survives the post-test app
     *  uninstall and CI can pull it from /sdcard without run-as. */
    private fun captureScreenshot(name: String) {
        try {
            val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val bmp = inst.uiAutomation.takeScreenshot() ?: return
            val resolver = inst.targetContext.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/motioncam")
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
        } catch (e: Exception) {
            // ignore — screenshot is diagnostic only
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextContains(
    text: String
) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
