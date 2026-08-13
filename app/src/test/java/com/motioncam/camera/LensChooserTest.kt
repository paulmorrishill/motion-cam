package com.motioncam.camera

import com.google.common.truth.Truth.assertThat
import com.motioncam.camera.LensChooser.BackLens
import org.junit.Test

class LensChooserTest {

    @Test
    fun picksMainAndUltraWide_fromMultipleBackCameras() {
        // S21 FE-like: id 0 main (5.4mm), id 2 ultra-wide (1.74mm), id 3 tele (7.12mm).
        val lenses = listOf(
            BackLens("0", 5.4f),
            BackLens("2", 1.74f),
            BackLens("3", 7.12f)
        )
        val toggle = LensChooser.toggleLenses(defaultBackId = "0", backLenses = lenses)

        assertThat(toggle.map { it.label }).containsExactly("Main", "Wide").inOrder()
        assertThat(toggle[0].cameraId).isEqualTo("0")
        assertThat(toggle[1].cameraId).isEqualTo("2") // smallest focal length = ultra-wide
    }

    @Test
    fun singleBackCamera_yieldsOnlyMain() {
        val toggle = LensChooser.toggleLenses("0", listOf(BackLens("0", 4.3f)))
        assertThat(toggle.map { it.label }).containsExactly("Main")
    }

    @Test
    fun ultraWideIsTheDefault_yieldsOnlyMain() {
        // If the default back camera already is the shortest focal length, there is no
        // separate wide lens to offer.
        val lenses = listOf(BackLens("0", 1.7f), BackLens("3", 7.0f))
        val toggle = LensChooser.toggleLenses("0", lenses)
        assertThat(toggle.map { it.label }).containsExactly("Main")
    }

    @Test
    fun emptyList_yieldsOnlyMain() {
        val toggle = LensChooser.toggleLenses("0", emptyList())
        assertThat(toggle.map { it.label }).containsExactly("Main")
        assertThat(toggle[0].cameraId).isEqualTo("0")
    }
}
