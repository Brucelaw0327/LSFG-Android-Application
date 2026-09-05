package com.lsfg.android.session

import android.util.Log
import java.lang.reflect.Method

/**
 * Hidden-API enforcement bypass for the privileged capture path.
 *
 * Strategy (in order):
 *  1. Simple: dalvik.system.VMRuntime.setHiddenApiExemptions via plain
 *     reflection. Works when the method itself is not block-listed.
 *  2. Meta-reflection: obtain a Method handle for getDeclaredMethod FROM
 *     java.lang.Class itself. Reflection initiated by a bootstrap class is
 *     exempt from the hidden-API filter, so methods fetched this way bypass
 *     the check (the classic FreeReflection trick).
 *
 * Returns a status string suitable for embedding in error messages:
 * "ok" on success, otherwise a short failure description.
 */
object HiddenApiBypass {

    @Volatile
    private var applied = false

    @Volatile
    private var status: String = "not attempted"

    fun ensureApplied(): String {
        if (applied) return "ok"
        val detail = runCatching { viaSimple() }
            .getOrElse { "simple: ${it.javaClass.simpleName}: ${it.message}" }
        if (detail == "ok") {
            applied = true
            status = "ok"
            return status
        }
        val meta = runCatching { viaMetaReflection() }
            .getOrElse { "meta: ${it.javaClass.simpleName}: ${it.message}" }
        status = if (meta == "ok") "ok" else "FAILED ($detail; $meta)"
        applied = meta == "ok"
        Log.w("HiddenApiBypass", "ensureApplied -> $status")
        return status
    }

    private fun viaSimple(): String {
        val vm = Class.forName("dalvik.system.VMRuntime")
        val get = vm.getDeclaredMethod("getRuntime")
        get.isAccessible = true
        val set = vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
        set.isAccessible = true
        set.invoke(get.invoke(null), arrayOf("L"))
        return "ok"
    }

    private fun viaMetaReflection(): String {
        val meta = Class.forName("java.lang.Class").getDeclaredMethod(
            "getDeclaredMethod",
            String::class.java,
            emptyArray<Class<*>>().javaClass,
        )
        val vm = Class.forName("dalvik.system.VMRuntime")
        val get = meta.invoke(vm, "getRuntime", emptyArray<Class<*>>()) as Method
        val set = meta.invoke(
            vm,
            "setHiddenApiExemptions",
            arrayOf(Array<String>::class.java),
        ) as Method
        set.invoke(get.invoke(null), arrayOf(arrayOf("L")))
        return "ok"
    }
}
