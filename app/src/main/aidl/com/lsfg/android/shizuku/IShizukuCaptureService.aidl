package com.lsfg.android.shizuku;

import com.lsfg.android.shizuku.IShizukuFrameCallback;

interface IShizukuCaptureService {
    void startCapture(int targetUid, int width, int height, int maxFps, IShizukuFrameCallback callback) = 1;
    void stopCapture() = 2;
    String describeBackend() = 3;
    boolean isTargetProcessAlive(String pkg) = 4;
    String getForegroundPackage() = 5;
    String getTargetWindowingMode(String pkg) = 6;
    boolean isTouchDown() = 7;
    void destroy() = 16777114;
}
