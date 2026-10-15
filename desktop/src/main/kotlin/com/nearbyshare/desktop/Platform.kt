package com.nearbyshare.desktop

import java.io.File

enum class Os { WINDOWS, MAC, LINUX }

/**
 * Everything that differs between Windows, macOS and Linux in one place: where data lives, how to keep the computer awake,
 * start at login, open a file and show it in the file manager. The command lines are built by pure functions so they can be
 * tested on any machine.
 */
object Platform {
    val os: Os = detect(System.getProperty("os.name").orEmpty())

    fun detect(name: String): Os = when {
        name.startsWith("Windows", ignoreCase = true) -> Os.WINDOWS
        name.startsWith("Mac", ignoreCase = true) || name.contains("Darwin", ignoreCase = true) -> Os.MAC
        else -> Os.LINUX
    }

    /** "Windows", "macOS" or "Linux", for texts shown to the user. */
    val osName: String get() = when (os) { Os.WINDOWS -> "Windows"; Os.MAC -> "macOS"; Os.LINUX -> "Linux" }
    val fileManager: String get() = when (os) { Os.WINDOWS -> "File Explorer"; Os.MAC -> "Finder"; Os.LINUX -> "your file manager" }
    val startAtLoginLabel: String get() = when (os) { Os.WINDOWS -> "Start with Windows"; Os.MAC -> "Start when I log in"; Os.LINUX -> "Start when I log in" }

    /** The one-time note about the system firewall, or null if there is nothing to tell the user. */
    val firewallNote: String? get() = when (os) {
        Os.WINDOWS -> "The first time, Windows asks to allow the app through the firewall: choose Private networks."
        Os.MAC -> "If macOS asks to allow incoming connections, choose Allow."
        Os.LINUX -> "If your firewall is on, allow TCP port 45820 and UDP port 45821 for devices on your local network."
    }

    fun dataDir(os: Os = this.os, env: (String) -> String? = System::getenv, home: String = System.getProperty("user.home")): File = when (os) {
        Os.WINDOWS -> File(env("APPDATA") ?: home, "NearbyStorageShare")
        Os.MAC -> File(home, "Library/Application Support/NearbyStorageShare")
        Os.LINUX -> File(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "NearbyStorageShare")
    }

    /** A normal folder the host shares from the first time: Documents/Nearby Storage Share. */
    fun defaultShareFolder(home: String = System.getProperty("user.home")): File = File(home, "Documents/Nearby Storage Share")

    // ---- keep awake -----------------------------------------------------------------------------------------------------------------

    /** A helper process that keeps the computer from sleeping until it is destroyed, or null if there is no way to do that here. */
    fun keepAwakeCommand(os: Os = this.os): List<String>? = when (os) {
        Os.WINDOWS -> listOf(
            "powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
            "Add-Type -Name P -Namespace W -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);';" +
                "while (\$true) { [W.P]::SetThreadExecutionState(0x80000001) | Out-Null; Start-Sleep -Seconds 30 }",
        )
        Os.MAC -> listOf("caffeinate", "-i")
        Os.LINUX -> listOf("systemd-inhibit", "--what=idle:sleep", "--who=Nearby Storage Share", "--why=Sharing storage with nearby devices", "sleep", "infinity")
    }

    // ---- start at login --------------------------------------------------------------------------------------------------------------

    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val RUN_NAME = "NearbyStorageShare"
    const val MAC_LABEL = "in.todayintech.nearbystorageshare"

    fun macLaunchAgent(home: String = System.getProperty("user.home")) = File(home, "Library/LaunchAgents/$MAC_LABEL.plist")
    fun linuxAutostart(env: (String) -> String? = System::getenv, home: String = System.getProperty("user.home")) =
        File(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "autostart/nearby-storage-share.desktop")

    fun macPlist(exe: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$MAC_LABEL</string>
  <key>ProgramArguments</key>
  <array><string>${xml(exe)}</string><string>--tray</string></array>
  <key>RunAtLoad</key><true/>
</dict>
</plist>
"""

    fun linuxDesktopEntry(exe: String) = """[Desktop Entry]
Type=Application
Name=Nearby Storage Share
Comment=Share and use storage on nearby phones and computers
Exec="${exe.replace("\"", "\\\"")}" --tray
Terminal=false
X-GNOME-Autostart-enabled=true
"""

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun exePath(): String? = ProcessHandle.current().info().command().orElse(null)

    fun autostartEnabled(): Boolean = runCatching {
        when (os) {
            Os.WINDOWS -> ProcessBuilder("reg", "query", RUN_KEY, "/v", RUN_NAME).redirectErrorStream(true).start().waitFor() == 0
            Os.MAC -> macLaunchAgent().isFile
            Os.LINUX -> linuxAutostart().isFile
        }
    }.getOrDefault(false)

    /** Starts the app (minimised to the tray) when the user logs in. */
    fun setAutostart(on: Boolean) {
        runCatching {
            when (os) {
                Os.WINDOWS ->
                    if (on) {
                        val exe = exePath() ?: return
                        ProcessBuilder("reg", "add", RUN_KEY, "/v", RUN_NAME, "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f").start().waitFor()
                    } else {
                        ProcessBuilder("reg", "delete", RUN_KEY, "/v", RUN_NAME, "/f").start().waitFor()
                    }
                Os.MAC -> macLaunchAgent().let { f ->
                    if (on) { f.parentFile.mkdirs(); f.writeText(macPlist(exePath() ?: return)) } else f.delete()
                }
                Os.LINUX -> linuxAutostart().let { f ->
                    if (on) { f.parentFile.mkdirs(); f.writeText(linuxDesktopEntry(exePath() ?: return)) } else f.delete()
                }
            }
        }
    }

    // ---- files --------------------------------------------------------------------------------------------------------------------------

    /** Command that opens [file] with its default app, used where java.awt.Desktop cannot (common on Linux). */
    fun openCommand(file: File, os: Os = this.os): List<String> = when (os) {
        Os.WINDOWS -> listOf("cmd", "/c", "start", "", file.absolutePath)
        Os.MAC -> listOf("open", file.absolutePath)
        Os.LINUX -> listOf("xdg-open", file.absolutePath)
    }

    /** Command that shows [file] in the file manager (selected where the system supports it; Linux opens the folder). */
    fun revealCommand(file: File, os: Os = this.os): List<String> = when (os) {
        Os.WINDOWS -> listOf("explorer.exe", "/select,", file.absolutePath)
        Os.MAC -> listOf("open", "-R", file.absolutePath)
        Os.LINUX -> listOf("xdg-open", (file.absoluteFile.parentFile ?: file).absolutePath)
    }

    /** Opens a file in its default app. Returns an error text or null. */
    fun open(file: File): String? = runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
            java.awt.Desktop.getDesktop().open(file)
        } else {
            ProcessBuilder(openCommand(file)).start()
        }
        null
    }.getOrElse { "No app on this computer can open ${file.name}." }

    /** Shows a file in the file manager. Returns an error text or null. */
    fun reveal(file: File?): String? {
        if (file == null) return "That file is missing on this computer."
        return runCatching { ProcessBuilder(revealCommand(file)).start(); null }.getOrElse { "Could not open $fileManager." }
    }
}
