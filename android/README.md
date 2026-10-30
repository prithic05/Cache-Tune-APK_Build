# Cache Tune Android Application (100% On-Device Engine)

Cache Tune is a native Android music streaming and downloading application built with **Kotlin**, **Jetpack Compose**, and **AndroidX Media3 (ExoPlayer)**.

Unlike the server-based version (which required Python, FastAPI, Docker, and PostgreSQL on a PC/cloud server), this Android app runs **all 5 core modules directly on the user's Android device with zero server dependency**:

---

## ⚡ The 5 Core On-Device Modules

```
 1. Apple Music Search Engine
    └── Directly queries iTunes Search API on Android.
    └── Cleans soundtrack & movie suffixes, applies Levenshtein artist matching (>0.85).
    └── Ported from apple_music_search/scraper.py.

 2. Recommendations & Radio Queue
    └── Directly queries YouTube Music's InnerTube API (FEmusic_home / next).
    └── Delivers Quick Picks, Trending Lossless, and infinite Radio queues.
    └── Ported from backend/services/ytmusic_service.py.

 3. High-Speed Stream Downloader & Pre-caching
    └── Progressive chunk downloader using OkHttp.
    └── In-memory/file ring buffer starts playback after first 256KB (<300ms).
    └── PrecacheManager automatically pre-buffers upcoming tracks for 0ms gapless playback.
    └── Ported from backend/services/download_manager.py.

 4. On-Device FairPlay Decryption
    └── Native C++ NDK library (cachetune_decryptor) via JNI.
    └── Direct in-memory CBCS subsample decryption without Docker.
    └── Ported from Downloader/wrapper/main.c and runv2.go.

 5. Playback Engine & Background FLAC Converter
    └── AndroidX Media3 ExoPlayer natively decodes ALAC (.m4a) and FLAC (.flac).
    └── Playback starts in sub-second time without waiting for audio conversion!
    └── FlacConverter runs in the background to export archival .flac files to Music/CacheTune.
```

---

## 🎨 Aesthetic UI & Smooth Transitions

- **Dynamic Ambient Mesh Gradient**: Real-time Palette API color extraction from album artwork creates a living, morphing glow behind the UI.
- **Glassmorphic Cards**: Translucent surfaces with frosted blur highlights.
- **Spring-Physics MiniPlayer**: Draggable floating pill that seamlessly morphs and expands into the full-screen player.
- **Synced Karaoke Lyrics**: LRC timestamp synchronization with glowing active line emphasis and smooth inertia auto-scroll.
- **Equalizer Waveforms**: Animated frequency visualizer bars reactive to music playback.

---

## 🚀 How to Run the App

1. Open **Android Studio** (Hedgehog, Iguana, or Koala).
2. Click **Open** and select the folder:
   ```
   D:\Cache Tune app\CacheTune-Android
   ```
3. Android Studio will automatically sync the Gradle dependencies (`Media3`, `Compose`, `Coil`, `OkHttp`).
4. Connect an Android phone (Android 8.0 / API 26+) or launch an Android Emulator.
5. Click **Run** (green triangle `▶`) to build and launch the app!
