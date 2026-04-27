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


## Project structure

- `app/` - main Android application module
- `gradle/` - Gradle configuration used by the Android project

## Current version

- Version name: `3.2.0`

## Requirements

- Android Studio
- JDK 17
- Android SDK 35
- Android API 26+

## Getting started

1. Open the `croc-app` folder in Android Studio.
2. Let Gradle sync and download dependencies.
3. Run the `app` configuration on a device or emulator.

Notes:

- This repo currently does not include Gradle wrapper scripts, so Android Studio is the easiest local workflow.
- The app currently uses destructive Room migrations during development, so local history data can reset when the schema changes.

## Project status

`croc-app` is under active development. Current focus areas include:

- transfer reliability
- quick-transfer UX polish
- better history and file actions
- overall Android-specific usability

## Contributing

Contributions are welcome.

If you want to help:

1. Open an issue for the bug, idea, or improvement.
2. If you plan to work on it, leave a short comment so effort does not overlap.
3. Keep pull requests focused and easy to review.
4. Include screenshots or recordings for UI changes when possible.
5. Mention migration impact, behavior changes, and testing notes in the PR description.

Areas where help is especially useful:

- transfer edge cases
- Android file handling
- accessibility improvements
- UI refinement
- test coverage
- documentation

## Design goals

The app tries to stay true to `croc` while feeling native on Android:

- fast to use
- easy to understand
- touch friendly
- practical for real file sharing
- polished enough for daily use

## Related project

- [`croc-gui`](https://github.com/howeyc/crocgui) - earlier mobile-oriented GUI work around `croc`
- [`croc`](https://github.com/schollz/croc) - croc
