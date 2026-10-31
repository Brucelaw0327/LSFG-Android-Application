package com.lsfg.android.session

import android.content.Intent
import android.os.IBinder
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import com.topjohnwu.superuser.ipc.RootService

/**
 * Root capture service (runs as root via libsu). All capture behaviour lives
 * in [PrivilegedCaptureBackend], shared verbatim with [ShizukuCaptureUserService].
 */
class RootCaptureService : RootService() {

    override fun onBind(intent: Intent): IBinder = Impl()

    inner class Impl : IShizukuCaptureService.Stub() {

        private val backend = PrivilegedCaptureBackend("Root")

        override fun startCapture(
            targetUid: Int,
            width: Int,
            height: Int,
            maxFps: Int,
            callback: IShizukuFrameCallback,
        ) = backend.startCapture(targetUid, width, height, maxFps, callback)

        override fun stopCapture() = backend.stopCapture()

        override fun isTargetProcessAlive(pkg: String) = backend.isTargetProcessAlive(pkg)

        override fun getForegroundPackage() = backend.getForegroundPackage()

        override fun isHdrContentActive() = backend.isHdrContentActive()

        override fun isTouchDown() = backend.isTouchDown()

        override fun getTargetWindowingMode(pkg: String) = backend.getTargetWindowingMode(pkg)

        override fun describeBackend() = backend.describeBackend()

        override fun destroy() = backend.destroy()
    }
}
