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
        // overlay (burn-in protection). Tap the centre to wake so the controls
        // are visible and interactive, matching how a user would use it.
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(inst)
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Uploads").assertIsDisplayed()

        captureScreenshot("1_main_screen")

        // Navigate to Settings (wait on the "Save" button — a plain Text node).
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("Save").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        captureScreenshot("2_settings_screen")
        composeRule.onNodeWithText("Cancel").performClick()

        // Back on the main screen, navigate to Uploads.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("Uploads").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Uploads").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextContains("Queue").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        captureScreenshot("3_uploads_screen")
    }

    /** Best-effort UI screenshot for evidence; never fails the test. Written to
     *  the app's internal files dir so CI can pull it with `run-as`. */
    private fun captureScreenshot(name: String) {
        try {
            val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val file = java.io.File(inst.targetContext.filesDir, "$name.png")
            androidx.test.uiautomator.UiDevice.getInstance(inst).takeScreenshot(file)
        } catch (e: Exception) {
            // ignore — screenshot is diagnostic only
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextContains(
    text: String
) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
