package com.lsfg.android.session

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import com.lsfg.android.BuildConfig
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import rikka.shizuku.Shizuku

class ShizukuCaptureEngine(
    private val ctx: Context,
) {
    fun interface ErrorListener {
        fun onError(message: String)
    }

    @Volatile
    private var service: IShizukuCaptureService? = null
    @Volatile
    private var pendingStart: StartArgs? = null
    @Volatile
    private var errorListener: ErrorListener? = null

    @Volatile
    private var fpsListener: CaptureEngine.FpsListener? = null
    @Volatile
    private var graphListener: CaptureEngine.FrameGraphListener? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IShizukuCaptureService.Stub.asInterface(it) }
            val start = pendingStart
            if (start != null) {
                startCapture(start.targetPackage, start.width, start.height, start.maxFps)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    fun setFpsListener(listener: CaptureEngine.FpsListener?) {
        fpsListener = listener
    }

    /**
     * Privileged process-liveness probe via the shell-uid user service.
     * True/false = probe result; null = service not bound or probe failed
     * (caller must fail open and keep the session).
     */
    fun isTargetProcessAlive(pkg: String): Boolean? {
        val svc = service ?: return null
        return runCatching { svc.isTargetProcessAlive(pkg) }.getOrNull()
    }

    /**
     * Foreground-app probe via the shell-uid user service. Null = service
     * not bound or probe failed (caller must fail open).
     */
    fun getForegroundPackage(): String? {
        val svc = service ?: return null
        return runCatching { svc.getForegroundPackage() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Windowing mode of [pkg]'s current window via the shell-uid user
     * service ("fullscreen"/"freeform"/…). Null = not bound or probe
     * failed (caller must fail open).
     */
    fun getTargetWindowingMode(pkg: String): String? {
        val svc = service ?: return null
        return runCatching { svc.getTargetWindowingMode(pkg) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * True when any visible SurfaceFlinger layer carries HDR metadata
     * (HDR video on screen). False = no HDR / service not bound (fail-open).
     */
    fun isHdrContentActive(): Boolean {
        val svc = service ?: return false
        return runCatching { svc.isHdrContentActive() }.getOrDefault(false)
    }

    /** True while a finger is on the touchscreen; null = backend unavailable. */
    fun isTouchDown(): Boolean? {
        val svc = service ?: return null
        return runCatching { svc.isTouchDown() }.getOrNull()
    }

    fun setFrameGraphListener(listener: CaptureEngine.FrameGraphListener?) {
        graphListener = listener
    }
    private val metrics = FrameMetricsPoller()

    fun startFpsCounter() {
        metrics.startFps { realFps, totalFps ->
            fpsListener?.onFpsUpdate(realFps, totalFps)
        }
        LsfgLog.i(TAG, "Shizuku FPS counter started (native counters)")
    }

    fun stopFpsCounter() {
        metrics.stopFps()
    }

    fun startFrameGraph() {
        metrics.startGraph { realFps, generatedFps ->
            graphListener?.onFrameGraphSample(realFps, generatedFps)
        }
        LsfgLog.i(TAG, "Shizuku frame graph started (native counters)")
    }

    fun stopFrameGraph() {
        metrics.stopGraph()
    }


    fun isReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * Bind the shell-uid user service without starting a capture, so probe
     * methods (isTargetProcessAlive/getForegroundPackage) become available
     * for the auto-overlay watcher while no session is running.
     */
    fun bindForProbe() {
        if (service != null) return
        bind()
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

        val args = StartArgs(targetPackage, width, height, maxFps)
        pendingStart = args
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
            LsfgLog.i(TAG, "Shizuku capture started package=$targetPackage uid=$targetUid ${width}x${height}")
        }.onFailure {
            LsfgLog.w(TAG, "Shizuku startCapture failed", it)
            errorListener?.onError("Shizuku start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        pendingStart = null
        stopFpsCounter()
        stopFrameGraph()
        runCatching { service?.stopCapture() }
        runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        service = null
    }

    fun pauseCapture() {
        pendingStart = null
        runCatching { service?.stopCapture() }
    }







    private fun bind() {
        if (!isReady()) {
            errorListener?.onError("Shizuku is not running or permission is missing")
            return
        }
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure {
            LsfgLog.w(TAG, "bindUserService failed", it)
            errorListener?.onError("Shizuku bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuCaptureUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("capture")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("lsfg_capture")

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long) {
            try {
                NativeBridge.pushFrame(buffer, timestampNs)
            } catch (t: Throwable) {
                LsfgLog.w(TAG, "pushFrame from Shizuku failed", t)
            } finally {
                runCatching { buffer.close() }
            }
        }

        override fun onError(message: String?) {
            errorListener?.onError(message ?: "Unknown Shizuku capture error")
        }

        override fun onFrameMetrics(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
            NativeBridge.reportShizukuTiming(timestampNs, frameTimeNs, pacingJitterNs)
            if (frameTimeNs > 0L && pacingJitterNs > frameTimeNs) {
                LsfgLog.w(
                    TAG,
                    "Shizuku pacing spike frame=${frameTimeNs / 1_000_000.0}ms jitter=${pacingJitterNs / 1_000_000.0}ms",
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
        private const val TAG = "ShizukuCapture"

        fun requestPermission(requestCode: Int) {
            if (Shizuku.isPreV11()) throw RemoteException("Shizuku pre-v11 is not supported")
            Shizuku.requestPermission(requestCode)
        }
    }
}
