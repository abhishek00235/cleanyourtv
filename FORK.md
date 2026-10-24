# CleanYourTV local fork

This fork carries personal launcher and Photo Prints changes on top of CleanYourTV.

## Changes in this fork

- An on-device theme picker and the `cinematic-sunset` and `kids` theme presets.
- An optional Kids Mode entry point in the launcher.
- An optional Pixabay source for Photo Prints, which downloads and caches landscape images.

This fork does not read streaming-account data or implement automatic watch history. Streaming apps do not provide a shared watch-history feed to the launcher.

## Privacy and network use

- The Pixabay API key is entered on the TV and stored in Photo Prints' private app preferences. The key is not included in this repository or APK source.
- Photo Prints uses the key only when the owner enables the optional Pixabay image source. Downloaded images are cached on the TV; personal photos are not uploaded.
- The launcher has an INTERNET permission in this fork, but no analytics or account-reading code.

## Build

Build the launcher with JDK 17 and Android SDK 34 from `launcher/` using `gradlew.bat assembleRelease`. Build Photo Prints from `addons/photo-prints/` with the same SDK.

The release builds completed locally. No TV was connected for a physical verification run when this fork was prepared.
