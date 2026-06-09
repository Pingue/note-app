# PenNotes

A pen-first note-taking app for Android phones and tablets. Write with a stylus
or your finger, organise notes into multi-page files, and sync everything to a
folder in your Google Drive with a local cache for offline use.

This app is intended for personal/sideloaded use — it is **not** meant to be
published to the Play Store.

## Features

- **Pen and finger input** — one pointer draws; two fingers scroll and zoom.
  Stylus pressure modulates pen stroke width.
- **Continuous vertical scrolling** through all pages of a notebook, on a
  full-screen canvas with a floating overlay toolbar.
- **Three tools**
  - **Pen** — adjustable colour and size, pressure-sensitive.
  - **Highlighter** — adjustable colour and size, semi-transparent so text
    underneath stays readable.
  - **Eraser** — adjustable size, with two modes: **delete objects** (removes
    whole strokes it touches) or **rub out to white** (paints opaque white).
- **Pen mode** toggle — when on, only a stylus draws and a single finger
  scrolls; when off, touch draws and two fingers scroll/zoom.
- **Files & pages** — create, open, rename and delete notebook files. Each
  notebook holds multiple pages, each independently **portrait or landscape**.
- **Undo / redo** for strokes and erases.
- **Google Drive sync** — two-way sync to a `PenNotes` folder in your Drive,
  backed by a local cache so the app works fully offline. Conflicts resolve
  last-write-wins by modification time.
- **Export** — save a notebook **PDF** to a chosen location via the system file
  picker, or share it; export each page as a **JPG**.
- **Responsive** — a grid library on the home screen and a canvas that fits any
  screen, so it works on both phones and tablets.
- **Light/dark theme**, with Material You dynamic colour on Android 12+.

## Tech overview

- Kotlin, Jetpack Compose for the UI shell, with a custom `View`
  (`DrawingView`) for the drawing surface (reliable `MotionEvent`/stylus
  handling).
- Notebooks are stored as JSON (kotlinx.serialization). The save format is
  deliberately simple — see `model/Notebook.kt`. Files live in the app's private
  storage (`filesDir/notebooks/*.pennote`), which doubles as the offline cache.
- Drive access uses Google Sign-In with the `drive.file` scope and the Drive v3
  REST API over OkHttp. The app can only see files it creates.
- Export uses Android's `PdfDocument` and `Bitmap` APIs. On-screen and exported
  rendering share one code path (`drawing/StrokeRenderer.kt`).

```
app/src/main/java/app/pennotes/
├── MainActivity.kt            # Compose host + sign-in launcher
├── model/Notebook.kt          # Notebook / Page / Stroke data model
├── drawing/
│   ├── DrawingView.kt         # custom canvas: input, pan/zoom, undo/redo
│   └── StrokeRenderer.kt      # shared stroke painting (editor + export)
├── storage/NotebookRepository.kt   # JSON load/save + offline cache
├── sync/DriveSync.kt          # Google Drive two-way sync
├── export/Exporter.kt         # PDF + JPG export
└── ui/                        # Compose screens, view model, theme
```

## Building

You need JDK 17 and the Android SDK (`compileSdk` 35). The Gradle wrapper is
checked in.

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install on a connected device
./gradlew installDebug
```

Open the folder in Android Studio (Koala or newer) for day-to-day development.

## Continuous integration

`.github/workflows/build.yml` runs on every push and pull request (and via
manual dispatch). It:

1. sets up JDK 17 and the Android SDK,
2. builds the debug APK (`assembleDebug`),
3. runs Android Lint (`lintDebug`),
4. uploads the APK as a build artifact (`pennotes-debug-apk`).

Download the APK from the **Artifacts** section of a completed run and sideload
it onto your device.

## Enabling Google Drive sync

The app works without sign-in; sync is optional. To enable it you must create
your own OAuth client, because Google ties OAuth credentials to your app's
signing certificate.

1. In the [Google Cloud Console](https://console.cloud.google.com/) create a
   project and **enable the Google Drive API**.
2. Configure the **OAuth consent screen** (External is fine for personal use)
   and add your Google account as a **test user**. Add the
   `.../auth/drive.file` scope.
3. Create an **OAuth client ID → Android**:
   - **Package name:** `app.pennotes`
   - **SHA-1:** the fingerprint of the keystore the APK is signed with. For the
     debug build:
     ```bash
     keytool -list -v -keystore ~/.android/debug.keystore \
       -alias androiddebugkey -storepass android -keypass android
     ```
     (CI-built APKs are signed with the runner's debug keystore, so register the
     SHA-1 of whichever keystore you actually install from — typically build and
     install locally for a stable fingerprint.)
4. Install the app, open the overflow menu on the home screen, and choose
   **Sign in to Google Drive**. Use the cloud icon to sync on demand.

No API keys or secrets are stored in the repo — Android OAuth clients are
identified by package name + signing certificate, not a secret.

### Troubleshooting sign-in

If the account picker appears, flickers, and closes without signing in, the
app will show **"Drive sign-in failed (error 10)"**. Error 10 is
`DEVELOPER_ERROR`: the certificate the installed APK is signed with has no
matching Android OAuth client in your Google Cloud project. Register the SHA-1
of the exact keystore you installed from (see step 3 above) and add your
account as a test user on the consent screen. Other codes: `12501` =
cancelled, `7` = network error.

## Notes & limitations

- The object eraser removes whole strokes it intersects (vector erase). The
  "rub out to white" eraser paints opaque white strokes on top rather than
  splitting the underlying ink, which is simple and looks correct on white
  pages.
- Sync is last-write-wins per notebook; it is not designed for simultaneous
  editing of the same notebook on two devices.
- Pages use an A4-proportioned coordinate space, so PDF/JPG exports come out at a
  consistent aspect ratio regardless of device.
