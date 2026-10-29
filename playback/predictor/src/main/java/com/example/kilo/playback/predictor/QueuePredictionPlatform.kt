package com.example.kilo.playback.predictor

import androidx.media3.common.Player
import com.example.kilo.playbackoptimization.PreparedTrack
import com.example.kilo.playbackoptimization.QueuePredictionManager as ExistingQueuePredictor
import com.example.kilo.playbackoptimization.TrackTransitionManager

object QueuePredictor {
    const val LOOKAHEAD = 5
    fun nextTracks(player: Player): List<PreparedTrack> = ExistingQueuePredictor.nextTracks(player, LOOKAHEAD)
}

object NextTrackPredictor {
    fun next(player: Player): PreparedTrack? = QueuePredictor.nextTracks(player).firstOrNull()
}

object TransitionPlanner {
    fun newSession(): TrackTransitionManager = TrackTransitionManager()
}

/** Read-only prepared candidates for the current queue; it does not modify queue contents. */
object QueuePreparationManager {
    fun candidates(player: Player): List<PreparedTrack> = QueuePredictor.nextTracks(player)
}
