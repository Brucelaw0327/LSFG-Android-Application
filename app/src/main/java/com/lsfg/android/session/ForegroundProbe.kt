package com.lsfg.android.session

import android.util.Log

/**
 * Foreground-app probe that runs inside a privileged (shell / root uid)
 * process, where `dumpsys` is allowed. The LSFG app process itself cannot
 * see other apps' activities, which is why this lives on the service side.
 *
 * All paths fail open: null = "unknown", never a wrong answer.
 */
object ForegroundProbe {

    /** Runs dumpsys and parses the resumed activity. Null = unknown. */
    fun probe(): String? = try {
        parse(exec("dumpsys", "activity", "activities"))
            ?: parse(exec("dumpsys", "window", "windows"))
    } catch (e: Exception) {
        Log.w(TAG, "foreground probe failed", e)
        null
    }

    /**
     * Parses dumpsys output for the foreground package. Understands both
     * `topResumedActivity=ActivityRecord{... u0 pkg/.Act ...}` (API 29+) and
     * `mCurrentFocus=Window{... u0 pkg/...}` (legacy fallback).
     */
    fun parse(out: String): String? {
        for (line in out.lineSequence()) {
            if ("topResumedActivity=" in line || "mCurrentFocus=Window{" in line) {
                parseComponent(line)?.let { return it }
            }
        }
        return null
    }

    /**
     * Probes whether [pkg]'s window covers the whole display, via
     * `dumpsys window windows`. Returns "fullscreen", "small-window" or
     * null (unknown / not found). All failures fail open.
     *
     * ColorOS 小窗 keeps `mWindowingMode=fullscreen` and instead shrinks the
     * window bounds (and may even double-report topResumedActivity), so the
     * reliable signal is bounds vs max bounds — not the mode string.
     */
    fun probeWindowingState(pkg: String): String? = try {
        parseWindowingState(exec("dumpsys", "window", "windows"), pkg)
    } catch (e: Exception) {
        Log.w(TAG, "windowing probe failed", e)
        null
    }

    /**
     * Scans dumpsys `window windows` output: finds the first Window block
     * owned by [pkg] and compares its `mBounds` against `mMaxBounds`.
     */
    fun parseWindowingState(out: String, pkg: String): String? {
        val boundsRe = Regex("mBounds=Rect\\((\\d+), (\\d+) - (\\d+), (\\d+)\\)")
        val maxRe = Regex("mMaxBounds=Rect\\((\\d+), (\\d+) - (\\d+), (\\d+)\\)")
        var inTargetWindow = false
        for (line in out.lineSequence()) {
            if ("Window #" in line) {
                inTargetWindow = parseComponent(line) == pkg
            }
            if (!inTargetWindow) continue
            val bounds = boundsRe.find(line)
            if (bounds != null) {
                val max = maxRe.find(line) ?: return null
                val same = (1..4).all { bounds.groupValues[it] == max.groupValues[it] }
                return if (same) "fullscreen" else "small-window"
            }
            val mode = Regex("mWindowingMode=(\\S+)").find(line)?.groupValues?.get(1)
            if (mode != null && mode != "undefined") return mode
        }
        return null
    }

    private fun exec(vararg args: String): String {
        val p = Runtime.getRuntime().exec(args)
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }

    // Extracts "com.foo.bar" from a line like
    // "topResumedActivity=ActivityRecord{1a2b u0 com.foo.bar/.Main t42}".
    private fun parseComponent(line: String): String? {
        val m = Regex("u\\d+\\s+([^\\s/]+)/").find(line) ?: return null
        return m.groupValues[1].takeIf { it.contains('.') }
    }

    private const val TAG = "ForegroundProbe"
}
