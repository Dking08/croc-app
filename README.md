# croc-app

`croc-app` is an Android client for [`croc`](https://github.com/schollz/croc) with a modern, mobile-first UI built in Kotlin and Jetpack Compose.

It is designed to make file and text sharing feel much more natural on Android: quick send, quick receive, QR-assisted flows, saved codes, transfer history, and better handling for the small details that matter on phones.

## Why this project exists

This project was inspired by [`croc-gui`](https://github.com/howeyc/crocgui), which showed that a mobile-friendly `croc` experience could work.

But `croc-gui` is older, written in Go, and lacks a number of features and UX improvements we wanted in a modern Android app. `croc-app` is our attempt to build a more polished, more maintainable, and more feature-complete Android experience around `croc`.

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

- Version name: `3.0.0`

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

## License

No license file has been added to this repository yet. If you are making the repo public, adding a `LICENSE` file would be a good next step.
