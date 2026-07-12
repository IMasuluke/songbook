# Songbook

A small native Android app for keeping a personal list of songs with lyrics, chords, key/capo notes, and drafts.

## Features

- Searchable song library
- Song detail view with monospaced lyrics and chord formatting
- Add, edit, and delete songs
- Save a source URL with each song
- Reopen a saved source URL in the built-in WebView
- Create a new song from the current WebView page URL
- Record, name, rename, play, and delete voice recordings on each saved song
- Autosave a new-song draft and resume it later
- Settings page with Google Drive backup and restore
- Local offline storage using app preferences
- Built-in WebView for looking up tabs/chords online
- Starter sample songs so the app is usable on first launch

## Architecture

The app is moving toward Clean Architecture in small, safe steps:

- `domain/model`: framework-free app entities such as `Song`, `SongVersion`, `Recording`, and chord tokens.
- `domain/usecase`: business rules such as song version branching and switching.
- `data/local`: local persistence and JSON mapping.
- `MainActivity`: Android UI composition, navigation, permissions, and platform hand-offs.

New business rules should go in `domain/usecase`. New persistence or serialization code should go in `data/local`. `MainActivity` should coordinate UI only and delegate behavior to those layers.

## Build

```sh
/Users/itumelengmasuluke/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
