package com.whatsbird.label

import com.whatsbird.classify.Prediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These tests exist because the failure they describe is the one users notice immediately: a label
 * that flips between two similar species every frame, or a species name confidently attached to a
 * bird the model has never seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelStabilizerTest {

    private val backgroundIndex = 3

    private fun stabilizer() = LabelStabilizer(
        backgroundClassIndex = backgroundIndex,
        windowSize = 6,
        minSamples = 2,
        agreementThreshold = 0.5f,
        switchAgreement = 0.72f,
        stalenessMs = 2_000,
        lockTtlMs = 3_000,
    ).apply { displayThreshold = 0.55f }

    private fun confident(classIndex: Int, score: Float = 0.9f) =
        listOf(Prediction(classIndex, score), Prediction(backgroundIndex, 1f - score))

    @Test
    fun `one look is not enough to name a species`() {
        val label = stabilizer().observe(1, confident(0), timestampMs = 1_000)

        assertEquals(LabelKind.IDENTIFYING, label.kind)
        assertNull(label.classIndex)
    }

    @Test
    fun `repeated agreement confirms a species`() {
        val stabilizer = stabilizer()
        stabilizer.observe(1, confident(0), 1_000)
        val label = stabilizer.observe(1, confident(0), 1_100)

        assertEquals(LabelKind.CONFIRMED, label.kind)
        assertEquals(0, label.classIndex)
    }

    @Test
    fun `the background class is never shown as a species`() {
        val stabilizer = stabilizer()
        stabilizer.observe(1, confident(backgroundIndex, 0.95f), 1_000)
        val label = stabilizer.observe(1, confident(backgroundIndex, 0.95f), 1_100)

        assertEquals(LabelKind.UNKNOWN, label.kind)
        assertNull(label.classIndex)
    }

    @Test
    fun `low confidence reports unknown rather than a guess`() {
        val stabilizer = stabilizer()
        // 0.4 is below the 0.55 display threshold
        stabilizer.observe(1, listOf(Prediction(0, 0.4f), Prediction(backgroundIndex, 0.35f)), 1_000)
        val label = stabilizer.observe(1, listOf(Prediction(0, 0.4f), Prediction(backgroundIndex, 0.35f)), 1_100)

        assertEquals(LabelKind.UNKNOWN, label.kind)
        assertNull(label.classIndex)
    }

    @Test
    fun `a weak challenger does not steal an established name`() {
        val stabilizer = stabilizer()
        repeat(3) { step -> stabilizer.observe(1, confident(0), 1_000L + step * 100) }

        // A single frame that prefers another species, without enough agreement to justify a switch.
        val label = stabilizer.observe(
            1,
            listOf(Prediction(1, 0.62f), Prediction(0, 0.30f)),
            timestampMs = 1_400,
        )

        assertEquals(LabelKind.CONFIRMED, label.kind)
        assertEquals(0, label.classIndex)
    }

    @Test
    fun `a sustained challenger does replace the name`() {
        val stabilizer = stabilizer()
        repeat(3) { step -> stabilizer.observe(1, confident(0), 1_000L + step * 100) }

        var label = stabilizer.snapshot(1, 1_300)
        repeat(6) { step ->
            label = stabilizer.observe(1, listOf(Prediction(1, 0.95f), Prediction(0, 0.03f)), 1_500L + step * 100)
        }

        assertEquals(LabelKind.CONFIRMED, label.kind)
        assertEquals(1, label.classIndex)
    }

    @Test
    fun `one dissenting frame does not veto a species seen in five`() {
        // Five frames at 0.60 for species 0, one frame at 0.60 for species 1 — a bird with a clear
        // majority behind it. The score average must be taken over the frames that picked the winner
        // (0.60, above the 0.55 bar). Averaging over the whole window instead yields 3.0 / 6 = 0.50
        // and refuses to name it, which is how a well-identified bird ended up showing "bird,
        // species unknown" no matter how long the user held the camera on it.
        val stabilizer = stabilizer()
        repeat(5) { step ->
            stabilizer.observe(1, listOf(Prediction(0, 0.60f), Prediction(1, 0.60f)), 1_000L + step * 100)
        }
        val label = stabilizer.observe(1, listOf(Prediction(1, 0.60f), Prediction(0, 0.60f)), 1_600)

        assertEquals(LabelKind.CONFIRMED, label.kind)
        assertEquals(0, label.classIndex)
    }

    @Test
    fun `a label goes stale when the bird stops being classified`() {
        val stabilizer = stabilizer()
        stabilizer.observe(1, confident(0), 1_000)
        stabilizer.observe(1, confident(0), 1_100)

        val stale = stabilizer.snapshot(1, timestampMs = 5_000)

        assertEquals(LabelKind.IDENTIFYING, stale.kind)
    }

    @Test
    fun `forgetting a track drops its history so a new bird starts clean`() {
        val stabilizer = stabilizer()
        stabilizer.observe(7, confident(0), 1_000)
        stabilizer.observe(7, confident(0), 1_100)

        stabilizer.forget(7)
        val fresh = stabilizer.snapshot(7, 1_150)

        assertEquals(LabelKind.IDENTIFYING, fresh.kind)
    }

    @Test
    fun `a newer bird reusing a recycled track id does not inherit the old name`() {
        val stabilizer = stabilizer()
        // Track 1 is a bulbul for a while, then that bird leaves and the tracker recycles the id.
        repeat(4) { step -> stabilizer.observe(1, confident(0), 1_000L + step * 100) }
        stabilizer.forget(1)

        val label = stabilizer.observe(1, confident(2), 3_000)

        assertEquals(LabelKind.IDENTIFYING, label.kind)
    }
}
