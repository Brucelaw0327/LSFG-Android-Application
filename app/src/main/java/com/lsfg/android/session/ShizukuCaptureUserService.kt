package com.lsfg.android.session

import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback

/**
 * Shizuku user service (runs as shell uid). All capture behaviour lives in
 * [PrivilegedCaptureBackend], shared verbatim with [RootCaptureService].
 */
class ShizukuCaptureUserService : IShizukuCaptureService.Stub() {

    private val backend = PrivilegedCaptureBackend("Shizuku")

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
