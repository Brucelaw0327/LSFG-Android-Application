package com.lsfg.android.session

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.util.Log
import android.view.Display
import java.lang.reflect.Method

/**
 * Calls the hidden [android.window.ScreenCapture] / [android.view.SurfaceControl] API
 * via reflection to capture a single frame from a specific app UID.
 *
 * Requires at minimum shell UID (Shizuku) or root UID to call captureDisplay() with
 * a UID filter — both satisfy this requirement.
 */
internal class PrivilegedScreenCapture(
    width: Int,
    height: Int,
    targetUid: Int,
) {
    private val captureDisplay: Method
    private val args: Any
    private val getHardwareBuffer: Method

    init {
        val failures = mutableListOf<String>()
        val backend = listOf(
            "android.window.ScreenCapture",
            "android.view.SurfaceControl",
        ).firstNotNullOfOrNull { className ->
            runCatching { buildBackend(className, width, height, targetUid, failures) }
                .onFailure {
                    Log.w(TAG, "Screen capture backend unavailable: $className", it)
                    failures.add("$className: ${it.javaClass.name}: ${it.message}")
                    it.cause?.let { c -> failures.add("  caused by: ${c.javaClass.name}: ${c.message}") }
                }
                .getOrNull()
        }
            ?: throw IllegalStateException(
                "No privileged ScreenCapture backend with UID filter is available. " +
                    "Tried -> " + failures.joinToString(" || ")
            )
        captureDisplay = backend.captureDisplay
        args = backend.args
        getHardwareBuffer = backend.getHardwareBuffer
    }

    fun captureHardwareBuffer(): HardwareBuffer? {
        val screenshot = captureDisplay.invoke(null, args) ?: return null
        return getHardwareBuffer.invoke(screenshot) as? HardwareBuffer
    }

    private fun buildBackend(
        captureClassName: String,
        width: Int,
        height: Int,
        targetUid: Int,
        failures: MutableList<String>,
    ): Backend {
        val captureClass = Class.forName(captureClassName)
        val builderClass = Class.forName("$captureClassName\$DisplayCaptureArgs\$Builder")
        val argsClass = Class.forName("$captureClassName\$DisplayCaptureArgs")
        val screenshotClass = Class.forName("$captureClassName\$ScreenshotHardwareBuffer")
        val builder = createDisplayCaptureArgsBuilder(captureClassName, builderClass, failures)
        invokeOptional(builderClass, builder, "setSize", intArrayOf(width, height))
        invokeOptional(builderClass, builder, "setPixelFormat", intArrayOf(PixelFormat.RGBA_8888))
        if (!invokeSetUid(builderClass, builder, targetUid.toLong())) {
            throw IllegalStateException("UID filter missing in $captureClassName")
        }
        val builtArgs = builderClass.getMethod("build").invoke(builder)
            ?: throw IllegalStateException("$captureClassName args build returned null")
        return Backend(
            captureDisplay = findSingleArgMethod(captureClass, "captureDisplay", argsClass),
            args = builtArgs,
            getHardwareBuffer = findNoArgMethod(screenshotClass, "getHardwareBuffer"),
        )
    }

    private fun createDisplayCaptureArgsBuilder(
        captureClassName: String,
        builderClass: Class<*>,
        failures: MutableList<String>,
    ): Any {
        val constructors = builderClass.declaredConstructors
        constructors.forEach { it.isAccessible = true }

        constructors.filter { ctor ->
            ctor.parameterTypes.size == 1 && IBinder::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }.forEach { ctor ->
            runCatching {
                val displayToken = findDisplayToken()
                Log.i(TAG, "$captureClassName builder using IBinder display token")
                return ctor.newInstance(displayToken)
            }.onFailure {
                Log.w(TAG, "$captureClassName IBinder builder unavailable", it)
                failures.add("$captureClassName IBinder ctor: ${it.javaClass.name}: ${it.message}")
                it.cause?.let { c -> failures.add("  display token: ${c.javaClass.name}: ${c.message}") }
            }
        }

        constructors.filter { ctor ->
            ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == Int::class.javaPrimitiveType
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder using logical display id ${Display.DEFAULT_DISPLAY}")
                return ctor.newInstance(Display.DEFAULT_DISPLAY)
            }.onFailure {
                Log.w(TAG, "$captureClassName display-id builder unavailable", it)
                failures.add("$captureClassName display-id ctor: ${it.javaClass.name}: ${it.message}")
            }
        }

        constructors.filter { ctor ->
            ctor.parameterTypes.isEmpty()
        }.forEach { ctor ->
            runCatching {
                Log.i(TAG, "$captureClassName builder using no-arg constructor")
                return ctor.newInstance()
            }.onFailure {
                Log.w(TAG, "$captureClassName no-arg builder unavailable", it)
                failures.add("$captureClassName no-arg ctor: ${it.javaClass.name}: ${it.message}")
            }
        }

        throw IllegalStateException(
            "No usable $captureClassName DisplayCaptureArgs.Builder constructor: " +
                constructors.joinToString { ctor ->
                    ctor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                } +
                " details -> " + failures.joinToString(" || ")
        )
    }

    private fun findDisplayToken(): IBinder {
        val pathFailures = mutableListOf<String>()
        // ColorOS Android 16: physical display token moved to the OplusDisplayManager
        // extension service (IOplusDisplayManager.getPhysicalDisplayToken(long)).
        // Verified working on OnePlus Ace 6 / Android 16 (ColorOS 16) as shell/root.
        runCatching {
            val omClass = Class.forName("android.hardware.display.OplusDisplayManager")
            val inst = omClass.getMethod("getInstance").invoke(null)
            val getService = omClass.getDeclaredMethod("getService")
            getService.isAccessible = true
            val svc = getService.invoke(inst)
            val m = svc.javaClass.methods.firstOrNull {
                it.name == "getPhysicalDisplayToken" && it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Long::class.javaPrimitiveType ||
                        it.parameterTypes[0] == Int::class.javaPrimitiveType)
            } ?: throw NoSuchMethodException("IOplusDisplayManager.getPhysicalDisplayToken")
            val id = physicalDisplayId()
            val token = if (m.parameterTypes[0] == Long::class.javaPrimitiveType) {
                m.invoke(svc, id)
            } else {
                m.invoke(svc, id.toInt())
            }
            (token as? IBinder)?.also {
                Log.i(TAG, "Display token resolved from OplusDisplayManager.getPhysicalDisplayToken(id=$id)")
            }
        }.onFailure {
            Log.w(TAG, "OplusDisplayManager token path failed", it)
            pathFailures.add("OplusDisplayManager: ${it.javaClass.name}: ${it.message}")
        }.getOrNull()?.let { return it }
        findDisplayTokenFromDisplayManagerGlobal(pathFailures)?.let { return it }
        findDisplayTokenFromDisplayService(pathFailures)?.let { return it }

        for (className in listOf("android.view.DisplayControl", "android.view.SurfaceControl")) {
            val cls = runCatching { Class.forName(className) }
                .onFailure {
                    Log.w(TAG, "Display token class unavailable: $className", it)
                    pathFailures.add("Class.forName($className): ${it.javaClass.name}: ${it.message}")
                }
                .getOrNull() ?: continue

            findDisplayTokenFromDisplayControlClass(className, cls, pathFailures)?.let { return it }
            // Android 16 fallback: any static *DisplayToken* method on SurfaceControl
            if (className == "android.view.SurfaceControl") {
                runCatching {
                    cls.methods.filter {
                        it.name.contains("DisplayToken") && java.lang.reflect.Modifier.isStatic(it.modifiers)
                    }.forEach { m ->
                        if (m.parameterTypes.isEmpty()) {
                            (m.invoke(null) as? IBinder)?.let { return it }
                        } else if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Long::class.javaPrimitiveType) {
                            runCatching {
                                (m.invoke(null, 0L) as? IBinder)?.let { return it }
                            }
                        }
                    }
                }
                pathFailures.add("SurfaceControl token methods: none callable")
            }
        }
        pathFailures.add(methodDump())
        throw IllegalStateException("No display token API is available. paths -> " + pathFailures.joinToString(" || "))
    }

    /** Dumps candidate display/token-related methods for offline diagnosis. */
    private fun methodDump(): String {
        val names = mutableListOf<String>()
        runCatching {
            val g = Class.forName("android.hardware.display.DisplayManagerGlobal")
            names += g.declaredMethods
                .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}):${it.returnType.simpleName}" }
                .filter { it.contains("isplay", ignoreCase = true) && it.contains("oken", ignoreCase = true) }
        }.onFailure { names += "DMG dump failed: ${it.message}" }
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "display") as? IBinder
            if (binder != null) {
                val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                val dm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                names += dm!!.javaClass.interfaces.flatMap { it.declaredMethods.map { m -> m.name } }
                    .filter { it.contains("oken", ignoreCase = true) || it.contains("isplay", ignoreCase = true) }
                    .map { "IDisplayManager.$it" }
            } else names += "display service binder null"
        }.onFailure { names += "IDM dump failed: ${it.message}" }
        return "methods=" + names.joinToString(",")
    }

    /** Physical display id of the default display via public DisplayInfo.address field. */
    private fun physicalDisplayId(): Long {
        val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val g = dmg.getMethod("getInstance").invoke(null)
        val info = dmg.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(g, Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("getDisplayInfo returned null")
        val addr = info.javaClass.getField("address").get(info)
            ?: throw IllegalStateException("DisplayInfo.address is null")
        return addr.javaClass.getMethod("getPhysicalDisplayId").invoke(addr) as Long
    }

    private fun findDisplayTokenFromDisplayManagerGlobal(failures: MutableList<String>): IBinder? {
        return runCatching {
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            runCatching {
                // Android 16 renamed this to getPhysicalDisplayToken(long); fuzzy-match.
                val m = globalClass.methods.firstOrNull {
                    it.name.contains("DisplayToken") && it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                            it.parameterTypes[0] == Long::class.javaPrimitiveType)
                }
                when {
                    m == null -> null
                    m.parameterTypes[0] == Long::class.javaPrimitiveType ->
                        m.invoke(global, Display.DEFAULT_DISPLAY.toLong()) as? IBinder
                    else -> m.invoke(global, Display.DEFAULT_DISPLAY) as? IBinder
                }
            }.getOrNull()?.let { token ->
                Log.i(TAG, "Display token resolved from DisplayManagerGlobal DisplayToken method")
                return@runCatching token
            }
            runCatching {
                val dmField = globalClass.declaredFields.firstOrNull { it.name == "mDm" }
                    ?: return@runCatching null
                dmField.isAccessible = true
                val dm = dmField.get(global) ?: return@runCatching null
                dm.javaClass.methods.firstOrNull { method ->
                    method.name == "getDisplayToken" &&
                        method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }?.invoke(dm, Display.DEFAULT_DISPLAY) as? IBinder
            }.getOrNull()?.let { token ->
                Log.i(TAG, "Display token resolved from DisplayManagerGlobal.mDm.getDisplayToken")
                return@runCatching token
            }
            val info = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(global, Display.DEFAULT_DISPLAY)
                ?: return@runCatching null
            val tokenField = info.javaClass.declaredFields.firstOrNull { field ->
                IBinder::class.java.isAssignableFrom(field.type) &&
                    field.name.contains("token", ignoreCase = true)
            } ?: return@runCatching null
            tokenField.isAccessible = true
            (tokenField.get(info) as? IBinder)
                ?.also { Log.i(TAG, "Display token resolved from DisplayManagerGlobal.${tokenField.name}") }
        }.onFailure {
            Log.w(TAG, "DisplayManagerGlobal display token unavailable", it)
            failures.add("DisplayManagerGlobal: ${it.javaClass.name}: ${it.message}")
        }
            .getOrNull()
    }

    private fun findDisplayTokenFromDisplayService(failures: MutableList<String>): IBinder? {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val displayBinder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "display") as? IBinder
                ?: return@runCatching null
            val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            val displayManager = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, displayBinder)
                ?: return@runCatching null
            displayManager.javaClass.methods.firstOrNull { method ->
                method.name.contains("DisplayToken") && method.parameterTypes.size == 1 &&
                    (method.parameterTypes[0] == Int::class.javaPrimitiveType ||
                        method.parameterTypes[0] == Long::class.javaPrimitiveType)
            }?.let { method ->
                if (method.parameterTypes[0] == Long::class.javaPrimitiveType) {
                    method.invoke(displayManager, Display.DEFAULT_DISPLAY.toLong()) as? IBinder
                } else {
                    method.invoke(displayManager, Display.DEFAULT_DISPLAY) as? IBinder
                }
            }
        }.onSuccess {
            if (it != null) Log.i(TAG, "Display token resolved from IDisplayManager.getDisplayToken")
        }.onFailure {
            Log.w(TAG, "IDisplayManager display token unavailable", it)
            failures.add("IDisplayManager: ${it.javaClass.name}: ${it.message}")
        }
            .getOrNull()
    }

    private fun findDisplayTokenFromDisplayControlClass(
        className: String,
        cls: Class<*>,
        failures: MutableList<String>,
    ): IBinder? {
        runCatching {
            val ids = cls.methods.firstOrNull { it.name == "getPhysicalDisplayIds" && it.parameterTypes.isEmpty() }
                ?.invoke(null) as? LongArray
                ?: throw NoSuchMethodException("$className.getPhysicalDisplayIds()")
            val tokenMethod = cls.methods.firstOrNull { method ->
                method.name == "getPhysicalDisplayToken" &&
                    method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            } ?: throw NoSuchMethodException("$className.getPhysicalDisplayToken(long)")
            for (id in ids) {
                (tokenMethod.invoke(null, id) as? IBinder)?.let { token ->
                    Log.i(TAG, "Display token resolved from $className.getPhysicalDisplayToken($id)")
                    return token
                }
            }
        }.onFailure {
            Log.w(TAG, "$className physical display token unavailable", it)
            failures.add("$className physical: ${it.javaClass.name}: ${it.message}")
        }

        runCatching {
            cls.methods.firstOrNull { it.name == "getInternalDisplayToken" && it.parameterTypes.isEmpty() }
                ?.invoke(null) as? IBinder
                ?: throw NoSuchMethodException("$className.getInternalDisplayToken()")
        }.onSuccess {
            Log.i(TAG, "Display token resolved from $className.getInternalDisplayToken")
            return it
        }.onFailure {
            Log.w(TAG, "$className internal display token unavailable", it)
            failures.add("$className internal: ${it.javaClass.name}: ${it.message}")
        }

        runCatching {
            cls.methods.firstOrNull { method ->
                method.name == "getBuiltInDisplay" &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }?.invoke(null, 0) as? IBinder
                ?: throw NoSuchMethodException("$className.getBuiltInDisplay(int)")
        }.onSuccess {
            Log.i(TAG, "Display token resolved from $className.getBuiltInDisplay")
            return it
        }.onFailure {
            Log.w(TAG, "$className built-in display token unavailable", it)
            failures.add("$className builtin: ${it.javaClass.name}: ${it.message}")
        }

        return null
    }

    private fun invokeSetUid(builderClass: Class<*>, builder: Any, uid: Long): Boolean {
        val methods = builderClass.methods.filter { it.name == "setUid" && it.parameterTypes.size == 1 }
        for (method in methods) {
            runCatching {
                when (method.parameterTypes[0]) {
                    Long::class.javaPrimitiveType -> method.invoke(builder, uid)
                    Int::class.javaPrimitiveType -> method.invoke(builder, uid.toInt())
                    else -> return@runCatching
                }
                return true
            }
        }
        return false
    }

    private fun invokeOptional(builderClass: Class<*>, builder: Any, name: String, args: IntArray) {
        val types = Array(args.size) { Int::class.javaPrimitiveType }
        runCatching { builderClass.getMethod(name, *types).invoke(builder, *args.toTypedArray()) }
    }

    private fun findSingleArgMethod(cls: Class<*>, name: String, argClass: Class<*>): Method {
        return (cls.methods.asSequence() + cls.declaredMethods.asSequence())
            .firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].isAssignableFrom(argClass)
            }
            ?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException("${cls.name}.$name(${argClass.name})")
    }

    private fun findNoArgMethod(cls: Class<*>, name: String): Method {
        return (cls.methods.asSequence() + cls.declaredMethods.asSequence())
            .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
            ?.also { it.isAccessible = true }
            ?: throw NoSuchMethodException("${cls.name}.$name()")
    }

    private data class Backend(
        val captureDisplay: Method,
        val args: Any,
        val getHardwareBuffer: Method,
    )

    companion object {
        private const val TAG = "PrivilegedCapture"
    }
}
