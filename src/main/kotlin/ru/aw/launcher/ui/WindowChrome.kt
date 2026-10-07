package ru.aw.launcher.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.CallbackReference
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import ru.aw.launcher.core.Log
import ru.aw.launcher.core.Shell
import ru.aw.launcher.core.Settings
import ru.aw.launcher.core.ThemeMode
import ru.aw.launcher.ui.components.Brand
import java.awt.Window
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Canvas
import java.awt.Container
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.concurrent.ConcurrentHashMap

private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

private interface WindowFunctions : StdCallLibrary {
    fun IsZoomed(hwnd: HWND): Boolean
    fun ScreenToClient(hwnd: HWND, point: POINT): Boolean
    fun GetDpiForWindow(hwnd: HWND): Int
    fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
}

object WindowChrome {
    private val nativeFrames = ConcurrentHashMap<Window, NativeFrame>()
    private val retainedCallbacks = ConcurrentHashMap<Pointer, Any>()
    private val windowFunctions by lazy { Native.load("user32", WindowFunctions::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    internal const val HT_CLIENT = 1
    internal const val HT_CAPTION = 2
    internal const val HT_MAXBUTTON = 9
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCDESTROY = 0x0082
    private const val WM_NCLBUTTONDOWN = 0x00A1
    private const val WM_NCLBUTTONUP = 0x00A2
    private const val WM_NCLBUTTONDBLCLK = 0x00A3
    private const val WM_NCRBUTTONUP = 0x00A5
    private const val WM_SYSCOMMAND = 0x0112
    private const val WM_GETMINMAXINFO = 0x0024

    private const val USE_IMMERSIVE_DARK_MODE = 20
    private const val USE_IMMERSIVE_DARK_MODE_LEGACY = 19
    private const val BORDER_COLOR = 34
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36

    fun applyTheme(window: Window, dark: Boolean) {
        if (!Shell.isWindows) return
        runCatching {
            val hwnd = Native.getComponentPointer(window) ?: return
            val dwm = Native.load("dwmapi", Dwmapi::class.java)
            val background = when {
                Settings.current.themeMode == ThemeMode.OLED -> 0x00000000
                dark -> 0x0010171A
                else -> 0x00F2F6F3
            }
            val text = if (dark) 0x00F0F5F2 else 0x0017251D

            if (dwm.set(hwnd, USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0) != 0) {
                dwm.set(hwnd, USE_IMMERSIVE_DARK_MODE_LEGACY, if (dark) 1 else 0)
            }

            dwm.set(hwnd, CAPTION_COLOR, colorRef(background))
            dwm.set(hwnd, TEXT_COLOR, colorRef(text))
            dwm.set(hwnd, BORDER_COLOR, colorRef(background))
            Log.debug("window chrome applied, dark=$dark")
        }.onFailure { Log.debug("window chrome unavailable: ${it.message}") }
    }

    fun applyIcon(window: Window) {
        runCatching { window.iconImages = Brand.windowIcons }
            .onFailure { Log.debug("window icon unavailable: ${it.message}") }
    }

    fun configureFramelessBounds(window: Window): () -> Unit {
        val frame = window as? Frame ?: return {}
        var lastConfiguration: java.awt.GraphicsConfiguration? = null
        fun updateBounds() {
            if (usesNativeFrame(frame)) {
                if (frame.maximizedBounds != null) frame.maximizedBounds = null
                return
            }
            val configuration = frame.graphicsConfiguration ?: return
            if (configuration === lastConfiguration) return
            lastConfiguration = configuration
            val screen = configuration.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            frame.maximizedBounds = Rectangle(
                screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom,
            )
        }
        val listener = object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) = updateBounds()
        }
        frame.addComponentListener(listener)
        updateBounds()
        if (Shell.isWindows && Native.POINTER_SIZE == 8) {
            runCatching {
                val native = NativeFrame(frame)
                native.install()
                nativeFrames[window] = native
                updateBounds()
            }.onFailure { Log.warn("native window frame unavailable", it) }
        }
        return {
            nativeFrames.remove(window)?.close()
            frame.removeComponentListener(listener)
        }
    }

    fun usesNativeFrame(window: Window): Boolean = nativeFrames.containsKey(window)

    fun captionRegion(window: Window, key: Any, bounds: Rectangle?, maximizeButton: Boolean = false) {
        nativeFrames[window]?.region(key, bounds, maximizeButton)
    }

    fun captionEnabled(window: Window, enabled: Boolean) { nativeFrames[window]?.captionEnabled = enabled }

    private data class Region(val bounds: Rectangle, val maximizeButton: Boolean)

    /** OS hit testing, rather than setLocation(), starts Windows' move/resize loop. */
    private class NativeFrame(private val frame: Frame) {
        private val user = User32.INSTANCE
        private val hwnd = HWND(Native.getComponentPointer(frame))
        private val regions = ConcurrentHashMap<Any, Region>()
        @Volatile private var hitRegions: Pair<List<Rectangle>, Rectangle?> = emptyList<Rectangle>() to null
        @Volatile var captionEnabled = true
        private var originalStyle = 0
        private val hooks = ArrayList<Hook>()
        private val childListeners = ArrayList<Pair<Canvas, HierarchyListener>>()

        private inner class Hook(val handle: HWND, val child: Boolean) {
            @Volatile var alive = true
            @Volatile var previous: Pointer? = null
            var loggedFailure = false
            val callback = WinUser.WindowProc { target, message, wParam, lParam ->
                try {
                    when (message) {
                        WM_GETMINMAXINFO -> if (!child) {
                            user.CallWindowProc(previous, target, message, wParam, lParam)
                            val monitor = user.MonitorFromWindow(hwnd, 2)
                            val info = WinUser.MONITORINFO()
                            if (user.GetMonitorInfo(monitor, info).booleanValue()) {
                                val border = resizeBorder()
                                val data = Pointer(lParam.toLong())
                                // MINMAXINFO: reserved POINT, max size POINT, max position POINT.
                                data.setInt(8, info.rcWork.right - info.rcWork.left + 2 * border)
                                data.setInt(12, info.rcWork.bottom - info.rcWork.top + 2 * border)
                                data.setInt(16, info.rcWork.left - info.rcMonitor.left - border)
                                data.setInt(20, info.rcWork.top - info.rcMonitor.top - border)
                            }
                            return@WindowProc LRESULT(0)
                        }
                        WM_NCCALCSIZE -> if (!child) {
                            // The first RECT is shared by RECT and NCCALCSIZE_PARAMS.
                            // Keep the hidden resize frame outside the maximized client.
                            if (windowFunctions.IsZoomed(hwnd)) {
                                val rect = Pointer(lParam.toLong())
                                val border = resizeBorder()
                                rect.setInt(0, rect.getInt(0) + border)
                                rect.setInt(4, rect.getInt(4) + border)
                                rect.setInt(8, rect.getInt(8) - border)
                                rect.setInt(12, rect.getInt(12) - border)
                            }
                            return@WindowProc LRESULT(0)
                        }
                        WM_NCHITTEST -> {
                            val screen = POINT(lParam.toInt().toShort().toInt(), (lParam.toLong() shr 16).toShort().toInt())
                            val client = POINT(screen.x, screen.y)
                            if (windowFunctions.ScreenToClient(hwnd, client)) {
                                val rect = RECT()
                                if (user.GetClientRect(hwnd, rect)) {
                                    val areas = hitRegions
                                    val result = hitTest(client.x, client.y, rect.right, rect.bottom,
                                        resizeBorder(), windowFunctions.IsZoomed(hwnd),
                                        if (captionEnabled) areas.first else emptyList(),
                                        if (captionEnabled) areas.second else null)
                                    if (result != HT_CLIENT) return@WindowProc LRESULT(if (child) -1L else result.toLong())
                                }
                            }
                            if (!child) return@WindowProc LRESULT(HT_CLIENT.toLong())
                        }
                        WM_NCLBUTTONDOWN, WM_NCLBUTTONDBLCLK -> if (!child) {
                            if (wParam.toInt() == HT_MAXBUTTON) return@WindowProc LRESULT(0)
                            if (wParam.toInt() == HT_CAPTION || wParam.toInt() in 10..17)
                                return@WindowProc user.DefWindowProc(target, message, wParam, lParam)
                        }
                        WM_NCRBUTTONUP -> if (!child && wParam.toInt() == HT_CAPTION)
                            return@WindowProc user.DefWindowProc(target, message, wParam, lParam)
                        WM_NCLBUTTONUP -> if (!child && wParam.toInt() == HT_MAXBUTTON) {
                            user.PostMessage(hwnd, WM_SYSCOMMAND, WPARAM(if (windowFunctions.IsZoomed(hwnd)) 0xF120L else 0xF030L), LPARAM(0))
                            return@WindowProc LRESULT(0)
                        }
                    }
                } catch (error: Throwable) {
                    if (!loggedFailure) { loggedFailure = true; Log.warn("native window message failed", error) }
                }
                val result = user.CallWindowProc(previous, target, message, wParam, lParam)
                if (message == WM_NCDESTROY) { alive = false; retainedCallbacks.remove(pointer) }
                result
            }
            val pointer: Pointer = CallbackReference.getFunctionPointer(callback)
            fun install() {
                previous = Pointer(user.GetWindowLongPtr(handle, WinUser.GWL_WNDPROC).toLong())
                Native.setLastError(0)
                val old = user.SetWindowLongPtr(handle, WinUser.GWL_WNDPROC, pointer)
                check(old != null && Pointer.nativeValue(old) != 0L) { "SetWindowLongPtr failed: ${Native.getLastError()}" }
                previous = old
            }
            fun close() {
                if (alive && user.GetWindowLongPtr(handle, WinUser.GWL_WNDPROC).toLong() == Pointer.nativeValue(pointer)) {
                    user.SetWindowLongPtr(handle, WinUser.GWL_WNDPROC, previous)
                } else if (alive) {
                    // Another subclass may still call us. Keep its callback alive until HWND destruction.
                    retainedCallbacks[pointer] = this
                }
            }
        }

        fun region(key: Any, bounds: Rectangle?, maximize: Boolean) {
            if (bounds == null) regions.remove(key) else regions[key] = Region(Rectangle(bounds), maximize)
            hitRegions = regions.values.filter { !it.maximizeButton }.map { it.bounds } to
                regions.values.firstOrNull { it.maximizeButton }?.bounds
        }

        fun install() {
            originalStyle = user.GetWindowLong(hwnd, WinUser.GWL_STYLE)
            try {
                Hook(hwnd, false).also { it.install(); hooks += it }
                Native.setLastError(0)
                val previous = user.SetWindowLong(hwnd, WinUser.GWL_STYLE, normalWindowStyle(originalStyle))
                check(previous != 0 || Native.getLastError() == 0) { "SetWindowLong failed: ${Native.getLastError()}" }
                fun children(container: Container) {
                    container.components.forEach { component ->
                        if (component is Canvas) {
                            fun attach() {
                                if (!component.isDisplayable) return
                                val handle = HWND(Native.getComponentPointer(component))
                                if (hooks.none { it.alive && it.handle == handle }) Hook(handle, true).also { it.install(); hooks += it }
                            }
                            val listener = HierarchyListener { event ->
                                if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) runCatching { attach() }
                                    .onFailure { Log.warn("native canvas frame unavailable", it) }
                            }
                            component.addHierarchyListener(listener)
                            childListeners += component to listener
                            attach()
                        }
                        if (component is Container) children(component)
                    }
                }
                children(frame)
                refreshFrame()
            } catch (error: Throwable) { close(); throw error }
        }

        private fun resizeBorder(): Int = runCatching {
            val dpi = windowFunctions.GetDpiForWindow(hwnd).coerceAtLeast(96)
            windowFunctions.GetSystemMetricsForDpi(WinUser.SM_CXSIZEFRAME, dpi) +
                windowFunctions.GetSystemMetricsForDpi(WinUser.SM_CXPADDEDBORDER, dpi)
        }.getOrElse { user.GetSystemMetrics(WinUser.SM_CXSIZEFRAME) + user.GetSystemMetrics(WinUser.SM_CXPADDEDBORDER) }.coerceAtLeast(4)

        private fun refreshFrame() {
            check(user.SetWindowPos(hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or WinUser.SWP_FRAMECHANGED))
        }

        fun close() {
            childListeners.forEach { (canvas, listener) -> canvas.removeHierarchyListener(listener) }
            if (hooks.firstOrNull()?.alive == true) user.SetWindowLong(hwnd, WinUser.GWL_STYLE, originalStyle)
            hooks.asReversed().forEach { it.close() }
            if (hooks.firstOrNull()?.alive == true) runCatching { refreshFrame() }
        }
    }

    internal fun normalWindowStyle(style: Int): Int = (style and WinUser.WS_POPUP.inv()) or WinUser.WS_OVERLAPPEDWINDOW

    internal fun hitTest(x: Int, y: Int, width: Int, height: Int, border: Int, maximized: Boolean, captions: List<Rectangle>, maximize: Rectangle?): Int {
        if (x !in 0 until width || y !in 0 until height) return HT_CLIENT
        if (!maximized) {
            val left = x < border; val right = x >= width - border
            val top = y < border; val bottom = y >= height - border
            if (top && left) return 13
            if (top && right) return 14
            if (bottom && left) return 16
            if (bottom && right) return 17
            if (left) return 10
            if (right) return 11
            if (top) return 12
            if (bottom) return 15
        }
        if (maximize?.contains(x, y) == true) return HT_MAXBUTTON
        return if (captions.any { it.contains(x, y) }) HT_CAPTION else HT_CLIENT
    }

    private fun Dwmapi.set(hwnd: Pointer, attribute: Int, value: Int): Int =
        DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)

    private fun colorRef(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
