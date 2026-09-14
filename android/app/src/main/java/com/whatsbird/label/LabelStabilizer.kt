package com.whatsbird.label

import com.whatsbird.classify.Prediction
import com.whatsbird.settings.AppSettings

enum class LabelKind {
    /** Not enough look(s) yet, or the last look has gone stale. */
    IDENTIFYING,

    /** A species name that survived the multi-frame vote. */
    CONFIRMED,

    /** We are confident it is a bird but not which one. */
    UNKNOWN,
}

data class TrackLabel(
    val kind: LabelKind,
    val classIndex: Int? = null,
    val score: Float = 0f,
)

/**
 * Turns a stream of per-frame classifier outputs into one label per bird.
 *
 * Three separate guards keep names from flickering, which is the failure mode that makes an
 * on-device identifier feel broken:
 *
 *  1. a sliding vote window instead of the latest frame,
 *  2. a higher agreement bar to *replace* an existing name than to set one,
 *  3. an explicit background class so "not a listed species" is a real answer.
 */
class LabelStabilizer(
    private val backgroundClassIndex: Int,
    private val windowSize: Int = 6,
    /**
     * Votes needed before a name can be shown at all. Public so the pipeline can ask "is this bird
     * still warming up?" and schedule the next classification accordingly — a track that has one
     * vote has no label no matter how confident that vote was.
     */
    val minSamples: Int = 2,
    private val agreementThreshold: Float = 0.5f,
    private val switchAgreement: Float = 0.72f,
    private val stalenessMs: Long = 2200L,
    private val lockTtlMs: Long = 3500L,
) {

    /** Below this the UI says "bird, species unknown" rather than naming a species. */
    @Volatile
    var displayThreshold: Float = AppSettings.DEFAULT_CONFIDENCE_THRESHOLD

    private class State {
        val votes = ArrayDeque<Prediction>()
        var lastUpdateMs = 0L
        var lockedClass: Int? = null
        var lockedAtMs = 0L
    }

    private val states = HashMap<Int, State>()

    /**
     * All four entry points are serialised on this instance.
     *
     * [observe] is called from the classification thread; [snapshot] and [forget] are called from
     * MediaPipe's result thread (via the overlay repaint); [reset] comes from the UI. [states] is a
     * plain [HashMap] holding [ArrayDeque] vote windows, so concurrent access can corrupt the map
     * mid-resize. The resulting throw happens on a thread this class does not own, which makes it
     * fatal to the process rather than a dropped frame.
     */
    @Synchronized
    fun observe(trackId: Int, predictions: List<Prediction>, timestampMs: Long): TrackLabel {
        if (predictions.isEmpty()) return snapshot(trackId, timestampMs)
        val state = states.getOrPut(trackId) { State() }
        state.votes.addLast(predictions.first())
        while (state.votes.size > windowSize) state.votes.removeFirst()
        state.lastUpdateMs = timestampMs
        return decide(state, trackId, timestampMs)
    }

    /** Current label without adding an observation — used for frames that skipped classification. */
    @Synchronized
    fun snapshot(trackId: Int, timestampMs: Long): TrackLabel {
        val state = states[trackId] ?: return TrackLabel(LabelKind.IDENTIFYING)
        if (state.votes.isEmpty()) return TrackLabel(LabelKind.IDENTIFYING)
        if (timestampMs - state.lastUpdateMs > stalenessMs) return TrackLabel(LabelKind.IDENTIFYING)
        return decide(state, trackId, timestampMs, mutateLock = false)
    }

    /**
     * How many votes this track has accumulated. Read by the pipeline's scheduler to tell a bird
     * that is still short of [minSamples] from one that merely has a stale answer.
     */
    @Synchronized
    fun voteCount(trackId: Int): Int = states[trackId]?.votes?.size ?: 0

    @Synchronized
    fun forget(trackId: Int) {
        states.remove(trackId)
    }

    @Synchronized
    fun reset() = states.clear()

    private fun decide(
        state: State,
        trackId: Int,
        timestampMs: Long,
        mutateLock: Boolean = true,
    ): TrackLabel {
        if (state.votes.size < minSamples) return TrackLabel(LabelKind.IDENTIFYING)

        val evidence = HashMap<Int, Float>()
        for (vote in state.votes) {
            evidence[vote.classIndex] = (evidence[vote.classIndex] ?: 0f) + vote.score
        }
        val total = evidence.values.sum()
        if (total <= 0f) return TrackLabel(LabelKind.UNKNOWN)

        val (topClass, topEvidence) = evidence.maxByOrNull { it.value } ?: return TrackLabel(LabelKind.UNKNOWN)
        val agreement = topEvidence / total
        // Averaged over the votes that actually chose this class, not over the whole window. Dividing
        // by every vote conflates "how often the model agreed with itself" (already [agreement]) with
        // "how confident it was", and squares the cost of a single dissenting frame: five 0.70 votes
        // for one species plus one 0.70 vote for another gave 3.5/6 = 0.58, i.e. below the 0.55 bar,
        // so a bird that was identified five frames out of six still never got its name shown.
        val topVotes = state.votes.count { it.classIndex == topClass }
        val meanScore = if (topVotes > 0) topEvidence / topVotes else 0f

        // Keep an existing name unless the challenger is clearly better — this is what stops two
        // similar species from trading the label back and forth every frame.
        val locked = state.lockedClass
        if (locked != null && locked != topClass && timestampMs - state.lockedAtMs < lockTtlMs) {
            if (agreement < switchAgreement) {
                val lockedEvidence = evidence[locked]
                if (lockedEvidence != null) {
                    val lockedVotes = state.votes.count { it.classIndex == locked }
                    return TrackLabel(
                        LabelKind.CONFIRMED,
                        locked,
                        if (lockedVotes > 0) lockedEvidence / lockedVotes else 0f,
                    )
                }
            }
        }

        if (topClass == backgroundClassIndex) {
            if (mutateLock) state.lockedClass = null
            return TrackLabel(LabelKind.UNKNOWN, score = meanScore)
        }

        val confirms = agreement >= agreementThreshold && meanScore >= displayThreshold
        if (confirms) {
            if (mutateLock) {
                state.lockedClass = topClass
                state.lockedAtMs = timestampMs
            }
            return TrackLabel(LabelKind.CONFIRMED, topClass, meanScore)
        }

        return TrackLabel(LabelKind.UNKNOWN, score = meanScore)
    }
}
