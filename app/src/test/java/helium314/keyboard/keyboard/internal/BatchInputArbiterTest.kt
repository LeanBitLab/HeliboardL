package helium314.keyboard.keyboard.internal

import helium314.keyboard.latin.LatinIME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class BatchInputArbiterTest {
    @Before
    fun setUp() {
        Robolectric.setupService(LatinIME::class.java)
        ShadowLog.setupLogging()
    }

    @Test
    fun ignoreFastTypingCooldownBypassesAfterFastTypingGate() {
        val normal = BatchInputArbiter(0, GestureStrokeRecognitionParams.DEFAULT)
        normal.addDownEventPoint(
            10,
            10,
            1_000L,
            990L,
            1,
            false,
        )
        assertTrue(normal.afterFastTyping())

        val bypassed = BatchInputArbiter(0, GestureStrokeRecognitionParams.DEFAULT)
        bypassed.addDownEventPoint(
            10,
            10,
            1_000L,
            990L,
            1,
            true,
        )
        assertFalse(bypassed.afterFastTyping())
    }

    @Test
    fun lastCodeInputLetterGuardTreatsSpaceAsWordBoundary() {
        val recorder = TypingTimeRecorder(500, 0)
        recorder.onCodeInput('h'.code, 1_000L)
        assertTrue(recorder.wasLastCodeInputLetter())

        recorder.onCodeInput(' '.code, 1_050L)
        assertFalse(recorder.wasLastCodeInputLetter())
        assertTrue(
            "space remains part of upstream fast-typing suppression but must not keep same-word tap/gesture combining alive",
            recorder.isInFastTyping(1_060L),
        )
    }

    private fun BatchInputArbiter.afterFastTyping(): Boolean {
        val recognitionPointsField = BatchInputArbiter::class.java.getDeclaredField("mRecognitionPoints")
        recognitionPointsField.isAccessible = true
        val recognitionPoints = recognitionPointsField.get(this)
        val afterFastTypingField = GestureStrokeRecognitionPoints::class.java.getDeclaredField("mAfterFastTyping")
        afterFastTypingField.isAccessible = true
        return afterFastTypingField.getBoolean(recognitionPoints)
    }
}
