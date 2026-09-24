# Panlong OBD Scanner (Android)

A full-featured OBD-II scanner for the Panlong ELM327 Bluetooth adapter. It saves a complete report to your phone and sends it to Claude, which writes back an easy-to-read vehicle health report.

## Features
- **Bluetooth connection** to a paired Panlong/ELM327 adapter, with fallbacks for clone adapters
- **Full scan**: VIN, calibration IDs, ECU names, stored/pending/permanent codes, freeze frame, emissions readiness monitors, every supported live sensor, repeated key-sensor sampling (min/avg/max), and Mode 06 on-board test results (CAN cars)
- **Live data dashboard** with a sensor picker, min/max tracking, and CSV recording
- **Quick code read** and **clear codes** (with a readiness warning)
- **Saves to your phone** in `Downloads/OBD Reports/` (scan `.json` and `.txt`, AI report `.html`, live `.csv`)
- **Claude analysis**: plain-English report with an overall verdict, whether it's safe to drive, issues ranked by severity, likely causes, DIY steps, cost estimates, smog readiness, and a sensor check-up
- **Save as PDF** (Android print, then "Save as PDF") and share
- **Vehicle profiles**: Generic, 2007 Trailblazer, 2010 F-150 4WD, and 2025 Sportage, so Claude can factor in known model-specific problems
- **Demo mode** (a simulated car), so you can try every screen without a vehicle

## Prerequisites
- Android phone running **Android 10 or newer**
- Panlong adapter **paired** in Android Bluetooth settings (PIN usually `1234` or `0000`)
- An **Anthropic API key** from https://console.anthropic.com (each analysis costs a few cents)
- To build it, you need **one** of these:
  - a GitHub account (a free Actions runner builds it), or
  - **Android Studio** (Koala 2024.1.1 or newer) with JDK 17 and Android SDK 34 (installed by Android Studio)

## Build option A: GitHub Actions (no local setup)
1. Create a new repo on GitHub (for example `PanlongOBDScanner`) and push this folder:
   ```
   cd PanlongOBDScanner
   git init && git add . && git commit -m "Initial"
   git branch -M main
   git remote add origin https://github.com/NALPAKD/PanlongOBDScanner.git
   git push -u origin main
   ```
2. On GitHub, open **Actions** and select **Build APK**. When it finishes, download the **PanlongOBDScanner-apk** artifact (a zip that contains `app-debug.apk`).

## Build option B: Android Studio
1. **File > Open**, then pick the `PanlongOBDScanner` folder. Let Gradle sync; it downloads the SDK pieces it needs.
2. **Build > Build App Bundle(s)/APK(s) > Build APK(s)**. The output is `app/build/outputs/apk/debug/app-debug.apk`.
   From the command line: `gradlew.bat assembleDebug` (Windows) or `./gradlew assembleDebug`.
3. To run the unit tests: `gradlew.bat testDebugUnitTest`

## Install on the phone
1. Copy `app-debug.apk` to the phone (USB, Google Drive, or email) and tap it.
2. Allow **Install unknown apps** for the app you opened it from.
3. Or with USB debugging on: `adb install -r app-debug.apk`

## Using it
1. Plug the adapter into the OBD port and turn the ignition **ON** (engine running gives the best data).
2. Open the app, then go to **Settings**, paste your API key, and tap **Save & test API key**.
3. Choose your vehicle, then tap **Connect Bluetooth** and pick the adapter (Android asks for the "Nearby devices" permission).
4. Tap **Full scan + save report**. It takes about 30–90 seconds.
5. Optionally type any symptoms you've noticed, then tap **Analyze with Claude**. Use **Save PDF** or **Share** on the report.
6. Past scans are under **Saved reports**. You can re-analyze any of them.

## Notes
- Generic OBD-II only reaches the engine and emissions computers. ABS, airbag, and body modules need manufacturer-specific tools.
- The API key is stored in the app's private storage on the phone and is sent only to `api.anthropic.com`.
- To change the Claude model, go to Settings. The default is `claude-sonnet-5`, and you can also enter a custom model ID.

## Code layout
- `core/`: pure Kotlin with no Android dependencies, unit tested (`app/src/test`). It contains the ELM327 parser, PID decoders, DTC parsing and database, the scanner, the demo simulator, the Claude prompt, and the HTML report renderer.
- `data/`: Bluetooth transport, session, settings, file storage/export, and the Claude HTTP client.
- `ui/`: screens (built in code, with no XML layouts).
