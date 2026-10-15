package com.nearbyshare.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The per-OS command lines and paths, checked on any machine. */
class PlatformTest {
    @Test
    fun detectsTheOperatingSystem() {
        assertEquals(Os.WINDOWS, Platform.detect("Windows 11"))
        assertEquals(Os.MAC, Platform.detect("Mac OS X"))
        assertEquals(Os.LINUX, Platform.detect("Linux"))
    }

    @Test
    fun dataLivesInTheUsualPlacePerOs() {
        val none: (String) -> String? = { null }
        assertEquals(File("C:/Roam/NearbyStorageShare"), Platform.dataDir(Os.WINDOWS, { if (it == "APPDATA") "C:/Roam" else null }, "C:/u"))
        assertEquals(File("/Users/a/Library/Application Support/NearbyStorageShare"), Platform.dataDir(Os.MAC, none, "/Users/a"))
        assertEquals(File("/home/a/.config/NearbyStorageShare"), Platform.dataDir(Os.LINUX, none, "/home/a"))
        assertEquals(File("/x/cfg/NearbyStorageShare"), Platform.dataDir(Os.LINUX, { if (it == "XDG_CONFIG_HOME") "/x/cfg" else null }, "/home/a"))
    }

    @Test
    fun revealAndOpenCommands() {
        val f = File("/tmp/My Files/a b.pdf")
        assertEquals(listOf("open", "-R", f.absolutePath), Platform.revealCommand(f, Os.MAC))
        assertEquals(listOf("explorer.exe", "/select,", f.absolutePath), Platform.revealCommand(f, Os.WINDOWS))
        assertEquals(listOf("xdg-open", f.absoluteFile.parentFile.absolutePath), Platform.revealCommand(f, Os.LINUX))
        assertEquals(listOf("xdg-open", f.absolutePath), Platform.openCommand(f, Os.LINUX))
    }

    @Test
    fun keepAwakeExistsOnEveryOs() {
        assertEquals("caffeinate", Platform.keepAwakeCommand(Os.MAC)!![0])
        assertEquals("systemd-inhibit", Platform.keepAwakeCommand(Os.LINUX)!![0])
        assertNotNull(Platform.keepAwakeCommand(Os.WINDOWS))
    }

    @Test
    fun autostartFilesAreWellFormed() {
        val plist = Platform.macPlist("/Applications/Nearby Storage Share.app/Contents/MacOS/Nearby & Co")
        assertTrue(plist.contains("<string>--tray</string>") && plist.contains("RunAtLoad") && plist.contains("Nearby &amp; Co"))
        val entry = Platform.linuxDesktopEntry("/opt/nearby-storage-share/bin/NearbyStorageShare")
        assertTrue(entry.contains("Exec=\"/opt/nearby-storage-share/bin/NearbyStorageShare\" --tray"))
        assertTrue(entry.startsWith("[Desktop Entry]"))
    }
}
