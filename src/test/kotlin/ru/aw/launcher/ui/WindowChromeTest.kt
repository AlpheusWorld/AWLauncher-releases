package ru.aw.launcher.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Canvas
import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import javax.swing.SwingUtilities

class WindowChromeTest {
    @Test
    fun `caption and window controls remain distinct from resize borders and client content`() {
        val captions = listOf(Rectangle(20, 0, 240, 44))
        val maximize = Rectangle(808, 0, 46, 44)
        fun hit(x: Int, y: Int, maximized: Boolean = false) = WindowChrome.hitTest(x, y, 900, 640, 8, maximized, captions, maximize)
        assertEquals(2, hit(30, 20))
        assertEquals(1, hit(300, 20))
        assertEquals(9, hit(820, 20))
        assertEquals(13, hit(1, 1))
        assertEquals(14, hit(899, 1))
        assertEquals(16, hit(1, 639))
        assertEquals(17, hit(899, 639))
        assertEquals(12, hit(450, 1))
        assertEquals(1, hit(1, 1, maximized = true))
        assertEquals(2, hit(30, 1, maximized = true))
        assertEquals(1, hit(-1, 40))
        assertEquals(1, hit(30, 80))
    }

    @Test
    fun `normal window styles retain unrelated flags while enabling shell snapping`() {
        val style = WindowChrome.normalWindowStyle(WinUser.WS_POPUP or WinUser.WS_CLIPCHILDREN)
        assertEquals(0, style and WinUser.WS_POPUP)
        assertEquals(WinUser.WS_OVERLAPPEDWINDOW, style and WinUser.WS_OVERLAPPEDWINDOW)
        assertEquals(WinUser.WS_CLIPCHILDREN, style and WinUser.WS_CLIPCHILDREN)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `hidden native window and canvas route caption hits to Windows and restore their procedures`() {
        if (Native.POINTER_SIZE != 8) return
        SwingUtilities.invokeAndWait {
            val frame = Frame().apply {
                isUndecorated = true
                minimumSize = Dimension(600, 420)
                setBounds(100, 100, 900, 640)
            }
            val canvas = Canvas()
            frame.add(canvas)
            frame.addNotify()
            val user = User32.INSTANCE
            val hwnd = HWND(Native.getComponentPointer(frame))
            val child = HWND(Native.getComponentPointer(canvas))
            val style = user.GetWindowLong(hwnd, WinUser.GWL_STYLE)
            val procedure = user.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC).toLong()
            val release = WindowChrome.configureFramelessBounds(frame)
            try {
                assertFalse(frame.isVisible)
                assertTrue(WindowChrome.usesNativeFrame(frame))
                val key = Any()
                WindowChrome.captionRegion(frame, key, Rectangle(20, 0, 240, 44))
                val rect = RECT()
                assertTrue(user.GetWindowRect(hwnd, rect))
                fun hit(handle: HWND, x: Int, y: Int): Long {
                    val packed = ((rect.top + y).toLong() and 0xffff shl 16) or ((rect.left + x).toLong() and 0xffff)
                    return user.SendMessage(handle, 0x0084, WPARAM(0), LPARAM(packed)).toLong()
                }
                assertEquals(2L, hit(hwnd, 30, 20))
                assertEquals(-1L, hit(child, 30, 20))
                assertEquals(1L, hit(hwnd, 300, 20))
                frame.setLocation(-700, 100)
                assertTrue(user.GetWindowRect(hwnd, rect))
                assertEquals(2L, hit(hwnd, 30, 20))
                Memory(40).use { minMax ->
                    minMax.clear()
                    user.SendMessage(hwnd, 0x0024, WPARAM(0), LPARAM(Pointer.nativeValue(minMax)))
                    assertTrue(minMax.getInt(8) > 0)
                    assertTrue(minMax.getInt(12) > 0)
                }
                WindowChrome.captionEnabled(frame, false)
                assertEquals(1L, hit(hwnd, 30, 20))
                assertEquals(WinUser.WS_OVERLAPPEDWINDOW, user.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WinUser.WS_OVERLAPPEDWINDOW)
            } finally {
                release()
                assertFalse(WindowChrome.usesNativeFrame(frame))
                assertEquals(style, user.GetWindowLong(hwnd, WinUser.GWL_STYLE))
                assertEquals(procedure, user.GetWindowLongPtr(hwnd, WinUser.GWL_WNDPROC).toLong())
                frame.dispose()
            }
        }
    }
}
