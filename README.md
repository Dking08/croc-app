<div align="center">

<img src="https://github.com/Dking08/croc-app/blob/master/others/croc-icon.png" alt="Croc app icon" width="150" />

# `croc-app`

<br/>
</div>

`croc-app` is an Android client for [`croc`](https://github.com/schollz/croc) with a modern, mobile-first UI built in Kotlin and Jetpack Compose.

It is designed to make file and text sharing feel much more natural on Android: quick send, quick receive, QR-assisted flows, saved codes, transfer history, and better handling for the small details that matter on phones.

<div align="center">
<h1><a id="download-now"></a>Download Now</h1>
<table>
  <tr>
    <th align="center">F-Droid</th>
    <th align="center">GitHub Release</th>
  </tr>
  <tr>
    <td align="center">
      <a href="https://f-droid.org/en/packages/com.dking.crocapp/">
  <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">
</a>
    </td>
    <td align="center">
      <a href="https://github.com/Dking08/croc-app/releases/latest/download/croc-app.apk">
        <img src="https://github.com/machiav3lli/oandbackupx/blob/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Download from GitHub" height="75">
      </a>
    </td>
  </tr>
</table>
</div>

## Why this project exists

This project was inspired by [`croc-gui`](https://github.com/howeyc/crocgui), which showed that a mobile-friendly `croc` experience could work.

But `croc-gui` is older, written in Go, and lacks a number of features and UX improvements we wanted in a modern Android app. `croc-app` is our attempt to build a more polished, more maintainable, and more feature-complete Android experience around `croc`.

---
<div align="center">

<h1><a id="screenshots"></a>Screenshots</h1>

<img src="https://github.com/Dking08/croc-app/blob/master/Screenshots/send.jpg" alt="Send screen" width="30%" />
<img src="https://github.com/Dking08/croc-app/blob/master/Screenshots/quick.jpg" alt="Quick screen" width="30%" />
<img src="https://github.com/Dking08/croc-app/blob/master/Screenshots/recieve.jpg" alt="Recieve screen" width="30%" />
<img src="https://github.com/Dking08/croc-app/blob/master/Screenshots/send-files.jpg" alt="Send Mutliple Files" width="30%" />
<img src="https://github.com/Dking08/croc-app/blob/master/Screenshots/quick-send.jpg" alt="Quick Send - Clipboard" width="30%" />
<img src="https://github.com/Dking08/croc-app/blob/master/Screenshots/quick-rec.jpg" alt="Quick Recieve" width="30%" />

</div>

---

## Features

- Quick send and quick receive flows
- Send files or clipboard text
- QR code generation for fast pairing
- QR scanning for receive
- Saved send and receive codes
- Transfer history and favorites
- Android share-sheet support
- Received files published to `Downloads/croc-received`
- Material 3 UI designed specifically for Android

## Current version

- Version name: `4.1.0`

## Project status

`croc-app` is under active development.

## How I use croc-app

My primary use case is simple: **quick clipboard transfer between phone and desktop** — notes, code snippets, links, anything.

On my desktop, I use a small PowerShell helper (`ccs`) that:

* starts `croc` in sending mode with my saved code
* automatically sends text from the clipboard

### Desktop -> Phone workflow

1. Run `ccs` in the desktop terminal
2. Open **croc-app** and tap **Receive**
3. The text appears on the phone, ready to copy or open

A similar flow works in reverse for sending text from my phone to my desktop.

## Project structure

- `app/` - main Android application module
- `gradle/` - Gradle configuration used by the Android project

## Building

### Standard build (quick)

If you just want to build and run the app locally:

```bash
./gradlew assembleRelease
```

Or open the project in Android Studio and run the `app` configuration.

This produces a working APK, but it may **not be reproducible**.

---

### Reproducible build

For deterministic builds (matching CI / F-Droid), use the scripts in:

```
reproducible-build/
```

This setup uses Docker to ensure:

* consistent environment
* reproducible dependency resolution
* deterministic signing

#### Steps (Linux/macOS)

```bash
cd reproducible-build

docker build -t croc-app-build .
docker run --rm \
  -e REPO_BRANCH=main \
  -e KEYSTORE_PASS=your_keystore_password \
  -e KEY_ALIAS=your_key_alias \
  -e KEY_PASS=your_key_password \
  -v $(pwd)/key.jks:/secrets/release.jks:ro \
  -v $(pwd)/output:/output \
  croc-app-builder \
  bash /build/build.sh
```

Output:

```
output/croc-app-release.apk
```

---

### Reproducible build (Windows)

You can use the provided batch script:

```
reproducible-build/run-build.bat
```

Before running, edit the script and configure:

```bat
set "KEYSTORE_FILE=%~dp0key.jks"
set "KEY_ALIAS=ALIAS"
set /p KEYSTORE_PASS=<kspass.txt
set /p KEY_PASS=<kpass.txt
```

Then:

1. Place your keystore:

```
reproducible-build/key.jks
```

2. Add passwords in:

```
reproducible-build/kspass.txt
reproducible-build/kpass.txt
```

3. Run:

```bat
run-build.bat
```

The signed APK will be generated in the `output/` directory.

---

## Contributing

Contributions are welcome, checkout [CONTRIBUTING.md](https://github.com/Dking08/croc-app/blob/master/CONTRIBUTING.md).

## Design goals

The app tries to stay true to `croc` while feeling native on Android:

- fast to use
- easy to understand
- polished enough for daily use

## Related project

- [`croc-gui`](https://github.com/howeyc/crocgui) - earlier mobile-oriented GUI work around `croc`
- [`croc`](https://github.com/schollz/croc) - croc
