package com.lsfg.android.session

import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicBoolean

class RootCaptureService : RootService() {

    override fun onBind(intent: Intent): IBinder = Impl()

    inner class Impl : IShizukuCaptureService.Stub() {

        private val running = AtomicBoolean(false)
        private var worker: Thread? = null

        override fun startCapture(
            targetUid: Int,
            width: Int,
            height: Int,
            maxFps: Int,
            callback: IShizukuFrameCallback,
        ) {
            stopCapture()
            val periodMs = (1000L / maxFps.coerceIn(15, 120)).coerceAtLeast(8L)
            running.set(true)
            worker = Thread(
                { runCaptureLoop(targetUid, width, height, periodMs, callback) },
                "lsfg-root-capture",
            ).also { it.start() }
        }

        override fun stopCapture() {
            running.set(false)
            worker?.interrupt()
            worker = null
        }

        override fun describeBackend(): String =
            "root uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}"

        override fun isTargetProcessAlive(pkg: String): Boolean {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("pidof", pkg))
                val out = p.inputStream.bufferedReader().use { it.readText().trim() }
                p.waitFor()
                out.isNotEmpty()
            } catch (e: Exception) {
                true // fail-open: a probe failure must never kill an active session
            }
        }

        override fun getForegroundPackage(): String {
            return ForegroundProbe.probe().orEmpty()
        }

        override fun isHdrContentActive(): Boolean {
            return ForegroundProbe.probeHdrContent()
        }

    // ---- Touch monitor: stream getevent and track BTN_TOUCH / tracking id ----
        private val touchDownState = java.util.concurrent.atomic.AtomicBoolean(false)
        private val lastTouchEventMs = java.util.concurrent.atomic.AtomicLong(0L)
        private val touchMonitorStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun ensureTouchMonitor() {
            if (!touchMonitorStarted.compareAndSet(false, true)) return
            Thread {
                try {
                    val proc = ProcessBuilder("/system/bin/getevent").start()
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        lastTouchEventMs.set(android.os.SystemClock.uptimeMillis())
                        val p = line.trim().split(Regex("\\s+"))
                        if (p.size >= 3) {
                            val type = p[p.size - 3]
                            val code = p[p.size - 2]
                            val value = p[p.size - 1]
                            when {
                                // BTN_TOUCH: 1 while at least one finger is down
                                type == "0001" && code == "014a" -> touchDownState.set(value != "00000000")
                                // ABS_MT_TRACKING_ID: ffffffff = slot released
                                type == "0003" && code == "0039" ->
                                    touchDownState.set(!value.equals("ffffffff", ignoreCase = true))
                            }
                        }
                    }
                } catch (_: Exception) {
                    // getevent unavailable - isTouchDown() fails open (reports up)
                }
            }.apply { isDaemon = true; name = "lsfg-touch-monitor"; start() }
        }

        override fun isTouchDown(): Boolean {
            ensureTouchMonitor()
            if (!touchDownState.get()) return false
            // Stale stream (no events for 3s) -> assume the finger lifted.
            return android.os.SystemClock.uptimeMillis() - lastTouchEventMs.get() < 3000L
        }

        override fun getTargetWindowingMode(pkg: String): String {
            return ForegroundProbe.probeWindowingState(pkg).orEmpty()
        }

        override fun destroy() {
            stopCapture()
            System.exit(0)
        }

        private fun runCaptureLoop(
            targetUid: Int,
            width: Int,
            height: Int,
            periodMs: Long,
            callback: IShizukuFrameCallback,
        ) {
            val bypass = HiddenApiBypass.ensureApplied()
            val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid) }
                .getOrElse { e ->
                    Log.w(TAG, "Unable to initialize privileged capture", e)
                    callback.onError(
                        "Root capture unavailable: ${e.message ?: e.javaClass.simpleName} " +
                            "[hiddenApiBypass=$bypass]"
                    )
                    running.set(false)
                    return
                }

            var lastFrameNs = 0L
            val targetPeriodNs = periodMs * 1_000_000L
            var frameLogCount = 0
            while (running.get()) {
                val started = SystemClock.uptimeMillis()
                val hb = runCatching { capture.captureHardwareBuffer() }
                    .onFailure {
                        Log.w(TAG, "captureHardwareBuffer failed", it)
                        callback.onError("Root capture failed: ${it.message ?: it.javaClass.simpleName}")
                    }
                    .getOrNull()

                if (hb != null) {
                    if (frameLogCount < 8) {
                        frameLogCount++
                        Log.i(TAG, "root frame #$frameLogCount uid=$targetUid ${hb.width}x${hb.height} fmt=${hb.format}")
                    }
                    val timestampNs = System.nanoTime()
                    val frameTimeNs = if (lastFrameNs > 0L) timestampNs - lastFrameNs else 0L
                    val pacingJitterNs = if (frameTimeNs > 0L) kotlin.math.abs(frameTimeNs - targetPeriodNs) else 0L
                    lastFrameNs = timestampNs
                    try {
                        callback.onFrameMetrics(timestampNs, frameTimeNs, pacingJitterNs)
                        callback.onFrame(hb, timestampNs)
                    } catch (t: Throwable) {
                        Log.w(TAG, "frame callback failed", t)
                        running.set(false)
                    } finally {
                        runCatching { hb.close() }
                    }
                }

                val elapsed = SystemClock.uptimeMillis() - started
                val sleepMs = periodMs - elapsed
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RootCaptureService"
    }
}
