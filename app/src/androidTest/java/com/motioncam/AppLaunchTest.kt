package com.motioncam

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Uploads").assertIsDisplayed()

        captureScreenshot("main_screen")
    }

    /** Best-effort UI screenshot for evidence; never fails the test. */
    private fun captureScreenshot(name: String) {
        try {
            val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val dir = inst.targetContext.getExternalFilesDir(null)
            val file = java.io.File(dir, "$name.png")
            androidx.test.uiautomator.UiDevice.getInstance(inst).takeScreenshot(file)
        } catch (e: Exception) {
            // ignore — screenshot is diagnostic only
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextContains(
    text: String
) = onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
