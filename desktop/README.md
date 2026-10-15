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
- Search your own files on the host, and cancel a running transfer.

## Sharing this PC's storage (host)

Open the **Share my storage** tab and switch sharing on. Everything the Android host offers is here:

- Storage to share (5 / 10 / 20 / 50 GB or a custom number); the PC always keeps a 2 GB safety reserve free.
- Where files go: **Private** (hidden, random names inside `%APPDATA%\NearbyStorageShare\host`) or **a folder you choose**
  (one subfolder per guest with real file names, visible in File Explorer).
- Who can connect: **Private** (only devices you approved), **Ask me each time** (code check + Allow), or **Open on this network**.
- Up to 1/2/3/5/8 devices at once, default permissions, and per device: storage limit, access duration, read / upload /
  replace / delete / create-folder, revoke, block, require new pairing, and an activity log.
- Closing the window while sharing keeps it running in the tray; Windows is kept awake while sharing; optional start with Windows.
- Quota is enforced on the host: space is reserved before an upload starts and the file is checksum-verified before it appears.

The first time sharing starts, allow the app through Windows Firewall on **Private networks**.

Not in this version: showing a host as a drive in File Explorer on Windows (the Android app has this).

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
