package com.ryan.scanme.core.imaging

import org.opencv.core.Mat

/** One named intermediate image, shown side by side on the debug screen. */
class DebugStage(val name: String, val image: Mat)

/** Receives intermediate images. Pass `null` instead of a sink to skip all debug copies. */
fun interface DebugSink {
    fun stage(name: String, image: Mat)
}

class DebugCollector : DebugSink {
    private val _stages = mutableListOf<DebugStage>()
    val stages: List<DebugStage> get() = _stages

    override fun stage(name: String, image: Mat) {
        _stages += DebugStage(name, image.clone())
    }

    fun release() {
        _stages.forEach { it.image.release() }
        _stages.clear()
    }
}
