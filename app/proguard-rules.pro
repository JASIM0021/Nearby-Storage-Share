# Room / Nearby ship their own consumer rules. Add app-specific keep rules here if needed.

# lan-core ships a desktop-only identity generator that uses BouncyCastle; the Android app never loads it.
-dontwarn org.bouncycastle.**
