# PDF Editor

An Android app that lets you open a PDF, tap a paragraph, retype it, and save a new copy. It works fully
offline: it asks for no permissions, has no internet access, and your files never leave your phone.

What it does:

- Open a PDF from your phone (through the normal Android file picker).
- Tap a paragraph to edit its text. The paragraph is re-flowed and the rest of the page moves with it.
- Add a blank page or delete a page.
- Undo and redo.
- "Save copy" writes a **new** file. Your original file is never changed.

Version 1 edits English (Latin-script) text only. Text in other languages is left untouched.

## What it can't do yet

- **Other languages.** Text in other scripts (for example Hindi, Russian or Chinese) can't be edited. It stays
  exactly as it is and still moves with the page.
- **Scanned PDFs.** A scanned page is a picture of text, so there is nothing to edit.
- **Text in tables and columns can't grow.** You can fix words there, but if the new text needs more room than
  the old text had, the app asks you to shorten it.
- **Underlines don't move.** If you rewrite underlined or highlighted text, the underline or highlight stays where
  it was (the app asks you first).
- **Clickable links don't move.** If text moves to a new position, a link that was on it stays at the old spot.
- **The font may change to a similar one.** If the document's font doesn't include a letter you type, the
  paragraph switches to a similar-looking font. You'll be asked first.

## What you need on your computer

Everything below is for building the app yourself. Commands are typed in the Terminal app.

1. **Android Studio** (free, from developer.android.com/studio). It also installs the Android SDK.
2. **JDK 17** (Java 17). Android Studio's own bundled Java is too new for this project, so use JDK 17.
   On this machine it is already installed at
   `~/Library/Java/JavaVirtualMachines/jdk-17.0.20.1+1`.
   Tell the terminal to use it (do this in every new Terminal window):

   ```
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

   Check with `java -version`; it should say 17.

## Open the project

1. Start Android Studio and choose **Open**.
2. Pick the `pdf-editor` folder (the one containing this README).
3. Wait for "Gradle sync" to finish. The first time it downloads things, so it can take a few minutes.

## Run the tests

In Terminal, from the `pdf-editor` folder:

```
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew testDebugUnitTest
```

"BUILD SUCCESSFUL" means everything passed. The tests include a "corpus" of real PDFs in
`app/src/test/resources/fixtures/corpus/`. If you have PDFs from Word, Google Docs or LibreOffice (non-sensitive
ones only), copy them into that folder: the tests pick them up automatically. See the README in that folder.

## Build the app

```
./gradlew assembleDebug
```

The app file (an APK) is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Try it on the emulator

1. Start the virtual phone:

   ```
   ~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_36
   ```

   (Or start `Pixel_8_API_36` from Android Studio's Device Manager.) Wait until the home screen appears.
2. Install the app on it:

   ```
   ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open "PDF Editor" in the emulator's app list.

## The privacy promise, and how it is checked

The app declares no permissions at all, in particular not internet. To verify this after building, run:

```
scripts/check-no-permissions.sh
```

It should print `OK: no permissions`. If it ever prints anything else, something has been added that breaks the
promise.

## Credits

Based on PDF Inspector by Shardul Vikram Singh (MIT License). The full licence is in the `LICENSE` file and in the
app's About screen (three-dot menu, then About). The app bundles fonts under open licences (Liberation, Carlito,
Caladea, Gelasio, Latin Modern, URW base 35, Selawik); their licence texts are shown in the same About screen.
PDF handling uses PdfBox-Android (Apache 2.0).
