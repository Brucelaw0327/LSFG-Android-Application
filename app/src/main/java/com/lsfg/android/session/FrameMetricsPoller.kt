package com.lsfg.android.session

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * FPS / frame-graph sampling shared by all three capture backends
 * (MediaProjection, Shizuku, Root).
 *
 * Ground-truth counters come from the native render loop:
 *  - uniqueCaptures  — frames with distinct pixel content (target render rate)
 *  - postedFrames    — frames actually posted to the overlay (CPU or WSI path)
 *  - generatedFrames — LSFG-interpolated output frames
 *
 * Two windows run independently so the 1 Hz FPS readout and the 5 Hz graph
 * don't steal each other's deltas. The graph applies an EMA (α=0.35 → ~600 ms
 * settling) because a 200 ms window catches an integer number of worker cycles
 * (typically 5-7 of ~33 ms) and raw counts jitter by ~20 % even at a steady
 * rate; seeding with the first real value avoids starting the curve at zero.
 *
 * Counters are read through JNI; after [stopFps]/[stopGraph] the sampling
 * thread is fully torn down, so no JNI calls happen against a destroyed
 * context.
 */
class FrameMetricsPoller {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var fpsPoller: Runnable? = null
    private var graphPoller: Runnable? = null

    private var fpsWindowStartMs: Long = 0L
    private var lastUniqueCaptureCount: Long = 0L
    private var lastPostedCount: Long = 0L

    private var graphWindowStartMs: Long = 0L
    private var graphLastUniqueCaptureCount: Long = 0L
    private var graphLastGeneratedCount: Long = 0L
    private var graphRealEma: Float = 0f
    private var graphGenEma: Float = 0f

    private fun ensureThread(): Handler {
        handler?.let { return it }
        val t = HandlerThread("lsfg-metrics").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        return h
    }

    private fun maybeQuitThread() {
        if (fpsPoller == null && graphPoller == null) {
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    @Synchronized
    fun startFps(onTick: (realFps: Float, totalFps: Float) -> Unit) {
        if (fpsPoller != null) return
        val h = ensureThread()
        fpsWindowStartMs = SystemClock.elapsedRealtime()
        lastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        lastPostedCount = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - fpsWindowStartMs).coerceAtLeast(1L)
                fpsWindowStartMs = now

                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - lastUniqueCaptureCount).coerceAtLeast(0L)
                lastUniqueCaptureCount = uniqNow
                val realFps = uniqDelta * 1000f / elapsed

                val postedNow = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
                val postedDelta = (postedNow - lastPostedCount).coerceAtLeast(0L)
                lastPostedCount = postedNow
                val totalFps = postedDelta * 1000f / elapsed

                onTick(realFps, totalFps)
                handler?.postDelayed(this, 1000L)
            }
        }
        fpsPoller = poll
        h.postDelayed(poll, 1000L)
    }

    @Synchronized
    fun stopFps() {
        fpsPoller?.let { handler?.removeCallbacks(it) }
        fpsPoller = null
        maybeQuitThread()
    }

    @Synchronized
    fun startGraph(onSample: (realFps: Float, generatedFps: Float) -> Unit) {
        if (graphPoller != null) return
        val h = ensureThread()
        graphWindowStartMs = SystemClock.elapsedRealtime()
        graphLastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        graphLastGeneratedCount = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
        graphRealEma = 0f
        graphGenEma = 0f
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - graphWindowStartMs).coerceAtLeast(1L)
                graphWindowStartMs = now

                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - graphLastUniqueCaptureCount).coerceAtLeast(0L)
                graphLastUniqueCaptureCount = uniqNow
                val realFpsRaw = uniqDelta * 1000f / elapsed

                val genNow = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
                val genDelta = (genNow - graphLastGeneratedCount).coerceAtLeast(0L)
                graphLastGeneratedCount = genNow
                val genFpsRaw = genDelta * 1000f / elapsed

                val alpha = 0.35f
                graphRealEma = if (graphRealEma <= 0.01f) realFpsRaw
                               else alpha * realFpsRaw + (1f - alpha) * graphRealEma
                graphGenEma = if (graphGenEma <= 0.01f) genFpsRaw
                              else alpha * genFpsRaw + (1f - alpha) * graphGenEma

                onSample(graphRealEma, graphGenEma)
                handler?.postDelayed(this, 200L)
            }
        }
        graphPoller = poll
        h.postDelayed(poll, 200L)
    }

    @Synchronized
    fun stopGraph() {
        graphPoller?.let { handler?.removeCallbacks(it) }
        graphPoller = null
        maybeQuitThread()
    }
}
