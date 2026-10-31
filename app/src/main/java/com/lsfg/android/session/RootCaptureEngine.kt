package com.lsfg.android.session

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService

class RootCaptureEngine(private val ctx: Context) {

    fun interface ErrorListener {
        fun onError(message: String)
    }

    @Volatile private var service: IShizukuCaptureService? = null
    @Volatile private var pendingStart: StartArgs? = null

    @Volatile private var errorListener: ErrorListener? = null
    @Volatile private var fpsListener: CaptureEngine.FpsListener? = null
    @Volatile private var graphListener: CaptureEngine.FrameGraphListener? = null
    @Volatile private var everConnected: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            everConnected = true
            service = binder?.takeIf { it.pingBinder() }
                ?.let { IShizukuCaptureService.Stub.asInterface(it) }
            pendingStart?.let { start ->
                startCaptureInternal(start.targetPackage, start.width, start.height, start.maxFps)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val wasConnected = everConnected
            service = null
            if (!wasConnected) {
                errorListener?.onError("Root access denied or su not available")
            }
        }
    }

    fun setErrorListener(listener: ErrorListener?) { errorListener = listener }
    fun setFpsListener(listener: CaptureEngine.FpsListener?) { fpsListener = listener }
    fun setFrameGraphListener(listener: CaptureEngine.FrameGraphListener?) { graphListener = listener }
    private val metrics = FrameMetricsPoller()

    fun startFpsCounter() {
        metrics.startFps { realFps, totalFps ->
            fpsListener?.onFpsUpdate(realFps, totalFps)
        }
        LsfgLog.i(TAG, "Root FPS counter started")
    }

    fun stopFpsCounter() {
        metrics.stopFps()
    }

    fun startFrameGraph() {
        metrics.startGraph { realFps, generatedFps ->
            graphListener?.onFrameGraphSample(realFps, generatedFps)
        }
        LsfgLog.i(TAG, "Root frame graph started")
    }

    fun stopFrameGraph() {
        metrics.stopGraph()
    }


    /**
     * Privileged process-liveness probe via libsu. True/false = probe result;
     * null = no root or probe failed (caller must fail open).
     */
    fun isTargetProcessAlive(pkg: String): Boolean? {
        if (Shell.isAppGrantedRoot() != true) return null
        return runCatching {
            val r = Shell.cmd("pidof -s $pkg").exec()
            r.out.firstOrNull()?.isNotBlank() == true
        }.getOrNull()
    }

    /**
     * True when any visible SurfaceFlinger layer carries HDR metadata
     * (HDR video on screen). Uses libsu shell; false on any failure.
     */
    fun isHdrContentActive(): Boolean {
        if (Shell.isAppGrantedRoot() != true) return false
        return runCatching {
            ForegroundProbe.parseHdrContent(Shell.cmd("dumpsys SurfaceFlinger").exec().out.asSequence())
        }.getOrDefault(false)
    }

    /**
     * Foreground-app probe via libsu dumpsys. Null = no root or probe
     * failed (caller must fail open).
     */
    fun getForegroundPackage(): String? {
        if (Shell.isAppGrantedRoot() != true) return null
        return runCatching {
            ForegroundProbe.parse(Shell.cmd("dumpsys activity activities").exec().out.joinToString("\n"))
                ?: ForegroundProbe.parse(Shell.cmd("dumpsys window windows").exec().out.joinToString("\n"))
        }.getOrNull()
    }

    /** True while a finger is on the touchscreen; null = backend unavailable. */
    fun isTouchDown(): Boolean? {
        val svc = service ?: return null
        return runCatching { svc.isTouchDown() }.getOrNull()
    }

    fun startCapture(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    private fun startCaptureInternal(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        val targetUid = runCatching {
            ctx.packageManager.getApplicationInfo(targetPackage, 0).uid
        }.getOrElse {
            errorListener?.onError("Target package not found: $targetPackage")
            return
        }

        pendingStart = StartArgs(targetPackage, width, height, maxFps)
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
            LsfgLog.i(TAG, "Root capture started pkg=$targetPackage uid=$targetUid ${width}x${height}")
        }.onFailure {
            LsfgLog.w(TAG, "Root startCapture failed", it)
            errorListener?.onError("Root capture start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        pendingStart = null
        stopFpsCounter()
        stopFrameGraph()
        runCatching { service?.stopCapture() }
        runCatching { RootService.unbind(connection) }
        service = null
        everConnected = false
    }

    fun pauseCapture() {
        pendingStart = null
        runCatching { service?.stopCapture() }
    }







    private val mainHandler = Handler(Looper.getMainLooper())

    private fun bind() {
        // libsu's RootService.bind enforces main-thread invocation. start() may
        // be called from the foreground service's worker thread (e.g. on a
        // surface-geometry change), so hop to the main looper if we're not
        // already there. Without this the bind throws IllegalStateException and
        // the root capture path silently never arms.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bind() }
            return
        }
        everConnected = false
        runCatching {
            val intent = Intent(ctx, RootCaptureService::class.java)
            RootService.bind(intent, connection)
        }.onFailure {
            LsfgLog.w(TAG, "RootService.bind failed", it)
            errorListener?.onError("Root service bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long) {
            try {
                NativeBridge.pushFrame(buffer, timestampNs)
            } catch (t: Throwable) {
                LsfgLog.w(TAG, "pushFrame from root failed", t)
            } finally {
                runCatching { buffer.close() }
            }
        }

        override fun onError(message: String?) {
            errorListener?.onError(message ?: "Unknown root capture error")
        }

        override fun onFrameMetrics(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
            NativeBridge.reportShizukuTiming(timestampNs, frameTimeNs, pacingJitterNs)
            if (frameTimeNs > 0L && pacingJitterNs > frameTimeNs) {
                LsfgLog.w(
                    TAG,
                    "Root pacing spike frame=${frameTimeNs / 1_000_000.0}ms jitter=${pacingJitterNs / 1_000_000.0}ms",
                )
            }
        }
    }

    private data class StartArgs(
        val targetPackage: String,
        val width: Int,
        val height: Int,
        val maxFps: Int,
    )

    companion object {
        private const val TAG = "RootCaptureEngine"
    }
}
