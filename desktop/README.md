# Nearby Storage Share for Windows

A desktop app (Compose Multiplatform for Desktop, Kotlin/JVM) that connects to a host phone **over the local network** and lets you
browse, upload, download, open, rename and delete the files you have stored there. It uses the same `lan-core` module as the Android app,
so the protocol, pairing and encryption are identical.

## What it does (version 1.0.0)

- Finds hosts on the same Wi-Fi or hotspot automatically, or connect by typing `ip:port` (the host shows it on its Home screen).
- First connection: both screens show a 6-digit code; confirm it on this PC and tap **Allow** on the host. After that the host is
  remembered (its certificate is pinned) and reconnecting needs no code.
- Browse folders, upload (several files), save to the PC, open a file in its default app (a cached copy is downloaded first),
  create folders, rename and delete. Buttons appear only for what the host has allowed this PC to do.

Not in this version: **hosting from Windows** (a PC cannot share its storage yet) and showing the host as a drive in File Explorer.

## Using it

1. On the phone: Settings are as usual; switch on **Share my storage**. Note the address under the status chip.
2. On the PC: start `NearbyStorageShare.exe`. The first time, Windows Firewall asks whether to allow it on private networks:
   **allow** (it listens for the host's announcements on UDP port 45821; without it, use *Connect by address*).
3. Pick the host, compare the code, confirm on both devices.

Both devices must be on the same network. Some routers and guest Wi-Fi networks stop devices from seeing each other
("client isolation"); a phone connected to the PC's own Windows hotspot always works.

Your PC's identity and the hosts it has paired with are stored in `%APPDATA%\NearbyStorageShare`
(delete that folder to forget everything).

## Build and run

```
.\gradlew :desktop:run                       # start the app from source
.\gradlew :desktop:test                      # logic tests (pair, transfer, reconnect...) and off-screen screenshots
.\gradlew :desktop:createDistributable       # self-contained folder with NearbyStorageShare.exe and its own Java runtime
```

The distributable is written to `desktop/build/compose/binaries/main/app/NearbyStorageShare/`. An installer (`packageMsi`) needs the
WiX Toolset installed. Screens rendered by the tests land in `desktop/build/screenshots/`.

To try the app without a phone: `.\gradlew :lan-core:demoHost` starts a stand-in host (in-memory files) that the app will find.
