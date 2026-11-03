package com.capstone.stethoscope.analysis

import kotlinx.coroutines.delay

/**
 * Placeholder classifier for demos and for development with no hardware attached.
 *
 * Derives its answer from the recording's **length**, not its audio, so it is reproducible
 * but tells you nothing clinical. The real implementation is
 * [com.capstone.stethoscope.analysis.segmenter.HeartSoundSegmenter]; switch
 * between them in di/AppContainer.kt.
 */
class MockHeartbeatClassifier : HeartbeatClassifier {

    override suspend fun classify(signal: HeartSignal): HeartSoundResult {
        delay(1_500) // stand in for real processing so the UI's spinner is exercised

        // Arbitrary but deterministic: odd-second recordings come out irregular.
        val irregular = (signal.durationMs / 1000L) % 2L == 1L
        return HeartSoundResult(
            quality = SignalQuality.GOOD,
            rhythm = if (irregular) HeartRhythm.IRREGULAR else HeartRhythm.REGULAR,
            heartRateBpm = if (irregular) 88f else 72f,
            beatCount = 12,
            confidence = 0.95f,
            bpmVariability = if (irregular) 0.31f else 0.03f,
            note = "Placeholder result — derived from recording length, not audio.",
        )
    }
}
