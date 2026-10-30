#  CacheTune — Full Ecosystem Build (Android App & Docker Engine)

Welcome to **CacheTune**, a high-performance lossless music streaming ecosystem featuring a **1:1 YouTube Music Native Android App** (Kotlin + Jetpack Compose) and an automated **Docker FairPlay CBCS Decryption Engine**.

---

## 📁 Repository Structure

```
Cache-Tune-APK_Build/
├── android/                         # [Mobile Client] Android Studio Project
│   ├── app/                         # App Module (Screens, ViewModels, Decryptors, ExoPlayer Media3)
│   │   ├── src/main/                # Jetpack Compose UI, Converters, Services & C++ Bridge
│   │   ├── build.gradle.kts         # Dependency and build configuration
│   │   └── proguard-rules.pro       # Optimization and obfuscation rules
│   ├── gradle/wrapper/              # Gradle wrapper files
│   ├── build.gradle.kts             # Root Gradle build script
│   └── settings.gradle.kts          # Project settings
│
├── docker/                          # [Decryption Engine & Backend Orchestrator]
│   ├── backend/                     # FastAPI orchestrator, home feed router, and priority worker
│   ├── Downloader/                  # Go lossless downloader source & FairPlay CBCS CDM wrapper
│   │   ├── apple-music-downloader/  # Go binary source with JWT token bypass
│   │   └── wrapper/                 # CDM wrapper container configuration
│   ├── apple_music_search/          # Apple Music catalog search microservice
│   ├── yt-music/                    # YouTube Music helpers & browser header templates
│   ├── docker-compose.yml           # Single-command Docker Compose deployment
│   ├── start_docker.bat             # One-click start script for Docker Desktop
│   ├── stop_docker.bat              # One-click stop script
│   └── .env.example                 # Sanitized environment template
│
├── apk/                             # [Pre-built App]
│   ├── CacheTune.apk                # Ready-to-install debug APK
│   └── README.md                    # Quick installation & ADB commands
│
├── .gitignore                       # Master gitignore protecting credentials & build caches
├── LICENSE                          # License and usage terms
└── README.md                        # Master documentation and step-by-step initialization guide
```

---

##  Step-by-Step Initialization Procedure

Follow this guide to get the complete system running from scratch.

### Prerequisites
1. **Laptop / Server**: [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running.
2. **Android Phone**: Running Android 8.0 or higher (or an Android Emulator via Android Studio).
3. **Local Network**: Laptop and Android phone connected to the same Wi-Fi network.

---

### Step 1: Start the Docker Backend Engine

1. Open a terminal or Command Prompt in the `docker/` directory:
   ```cmd
   cd docker
   ```
2. Copy `.env.example` to `.env`:
   ```cmd
   copy .env.example .env
   ```
3. Start the Docker containers:
   - On Windows: Double-click `start_docker.bat` or run:
     ```cmd
     docker compose up -d --build
     ```
   - On Linux / macOS:
     ```bash
     docker compose up -d --build
     ```
4. Verify the backend is online:
   - Open your browser to: `http://localhost:8000/api/health`
   - You should receive: `{"status": "ok"}`
   - Interactive API documentation is available at: `http://localhost:8000/docs`

> **Note**: Find your laptop's local Wi-Fi IP address (e.g. `192.168.1.15`) by running `ipconfig` (Windows) or `ifconfig` / `ip a` (Linux/Mac). You will need this to link the mobile app.

---

### Step 2: Install the Android Application

You can either install the pre-compiled APK directly or build from source using Android Studio.

#### Option A: Install Pre-Built APK (Fastest)
1. Navigate to the `apk/` directory where `CacheTune.apk` is located.
2. Install via **ADB** (with USB debugging enabled):
   ```bash
   adb install -r apk/CacheTune.apk
   ```
   *Or transfer `CacheTune.apk` to your phone via USB or local file sharing and tap to install.*

#### Option B: Build from Source
1. Open **Android Studio** (Giraffe or newer recommended with JDK 21).
2. Choose **Open** and select the `android/` directory.
3. Allow Gradle to sync.
4. Connect your Android phone or start an emulator.
5. Click the green **Run (▶)** button or build from CLI:
   ```bash
   cd android
   ./gradlew assembleDebug
   ```

---

### Step 3: Configure the App (Config Tab)

Open the CacheTune app on your phone and tap the **Config** tab (bottom right):

1. **Link to Laptop / Server Backend**:
   - In the **Cloud Server Link** field, enter your laptop's Wi-Fi IP:
     ```
     http://192.168.1.15:8000
     ```
   - Tap **Test Link** to verify instant latency.
   - Tap **Save URL**.

2. **Personalized YouTube Music Feed**:
   - Paste your DevTools raw request headers (or valid JSON) into the YouTube Music configuration box.
   - Tap **▶ SAVE & AUTHENTICATE**.
   - Your Home tab will immediately populate with your **Speed dial** and full 15-shelf home feed.
   - *If skipped, the app automatically defaults to rich guest recommendations.*

3. **Apple Music Lossless Decryption**:
   - Enter your Apple ID and password under the Apple Music section.
   - Tap ** START WRAPPER**.
   - Credentials are encrypted with AES-256 and stored **100% locally on device**; they are used to initialize the FairPlay wrapper session when playing studio tracks.

---

### Step 4: Lossless Playback & Offline FLAC Downloads

- **Instant Streaming**: Tap any track from **Speed dial**, **Quick picks**, or the **Search** tab.
- **Queue Control**: Tap the bottom queue handle to drag and reorder songs on the fly.
- **Play Next**: Use the "Play Next" button on any track item to queue it immediately after the current song.
- **Save Offline FLAC**:
  - Tap the **Download** button in the player or search results.
  - An informative dialog displays real-time status: **Downloading**, **Already Downloaded**, or **Download Complete**.
  - All downloaded tracks are saved directly to your phone's storage in `Music/CacheTune/` as lossless 24-bit FLAC audio, fully indexed in the **Library** tab and accessible in any offline music player.

---

##  Security & Privacy

- **No Secrets in Repo**: All personal credentials, user tokens, cookies, and database files are excluded via `.gitignore`.
- **Client-Side Encryption**: Authentication credentials entered in the mobile app are encrypted locally using Android KeyStore and AES-256.
- **Container Isolation**: The FairPlay decryption wrapper runs in an isolated Docker container bridge (`wrapper:10020`), preventing external exposure.

---

##  Credits & Acknowledgements

Special thanks and full credit to [**zhaarey**](https://github.com/zhaarey) ([https://github.com/zhaarey](https://github.com/zhaarey)) for their pioneering research, implementation, and open-source contributions to the Apple Music downloader and FairPlay decryption wrapper components utilized in the lossless decryption pipeline.

---

## 📄 License

Copyright © 2025 CacheTune. All Rights Reserved.  
Published for portfolio, demonstration, and evaluation purposes only. See [`LICENSE`](LICENSE) for terms.
