package com.github.krpkbhdn.astrolabe.ui

import java.awt.Toolkit

/** The one X11 WM_CLASS; equals the Linux packageName (jpackage's .desktop id) and StartupWMClass in packaging/linux/install.sh. */
const val LINUX_WM_CLASS = "astrolabe-studio"

/**
 * Makes the X11 window class a fixed name instead of one derived from the main class (which breaks Alt+Tab name and icon matching).
 * Must run before the first AWT window. Needs --add-opens java.desktop/sun.awt.X11; a failure leaves the derived name.
 * shortcut: Wayland-native WLToolkit has no such field (app_id unconfirmed), only the XWayland/X11 path is handled.
 */
internal fun applyLinuxWmClass() {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, LINUX_WM_CLASS)
    }
}
