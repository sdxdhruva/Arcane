package dev.arcane

import android.app.Application
import dev.arcane.data.AppGraph

/**
 * The process owns exactly one [AppGraph], built before the first frame, because a second copy of the vault
 * wiring would mean a second master key that nobody remembers to wipe.
 *
 * The stale-plaintext sweep runs here rather than in an activity: a viewer that was open when the process
 * died leaves a readable file on flash, and the next run must not wait for a screen to be shown before it
 * goes away.
 */
class ArcaneApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.of(this)
        this.graph = graph
        runCatching { graph.device.tempPlaintext.sweepStale() }
        graph.startLifecycle()
    }
}
