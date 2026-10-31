package com.lsfg.android.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for config changes.
 *
 * Every writer — the setup screens, the in-game drawer and the foreground
 * service — mutates the same SharedPreferences file. Previously the UI only
 * re-read it when the *UI itself* called `refreshConfigState`, so a value
 * written by the service (e.g. `setLsfgEnabled(false)` on auto-stop) stayed
 * invisible until the screen was recreated.
 *
 * One preference listener per process fixes that: any write, from any thread or
 * component, re-reads the config and pushes it to whoever subscribed.
 */
object ConfigRepository {

    /** Strong reference: SharedPreferences stores listeners weakly. */
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Synchronized
    fun install(ctx: Context, onChanged: (LsfgConfig) -> Unit) {
        if (listener != null) return
        val app = ctx.applicationContext
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            onChanged(LsfgPreferences(app).load())
        }
        app.getSharedPreferences(LsfgPreferences.FILE, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(l)
        listener = l
    }
}
