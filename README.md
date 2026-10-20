# Screenshot Notes (Screenshotter)

An Android app that notices every screenshot you take and pops up a small note bar so you
can type some context for it. Each screenshot and its note are saved into **one running
document** inside the app, which you can export as a **PDF** or a **Word (.docx)** file.
The .docx opens in Google Docs as well as Word.

## How it works

1. Turn on **Detect screenshots** in the app and grant the permissions it asks for.
2. Take a screenshot anywhere. A note bar slides up over the app you're in.
3. Type your context and tap **Save**, or tap **Discard**.
4. Open the app to see the document. You can edit or delete notes there, or export
   (share icon → *Save as PDF / Word*, or *Share as PDF / Word* to send it to Drive, Docs, email and so on).

If the app can't draw over other apps, you get a notification instead. You can type the note
straight into the notification with **Add note**, tap **Save without note**, or tap the
notification to open the note bar.

### Under the hood

- `ScreenshotWatcherService` is a foreground service with a `ContentObserver` on
  `MediaStore.Images`. When a new image lands in a folder whose name contains "Screenshot"
  (`Pictures/Screenshots`, `DCIM/Screenshots`, and so on), it opens `NoteActivity`.
- `NoteActivity` is a transparent activity that shows the note bar, with the keyboard open.
- `NotesRepository` copies each screenshot into the app's private storage and keeps the
  document in `document.json`, so deleting a screenshot from your gallery doesn't break the document.
- `Exporter` builds the PDF with Android's `PdfDocument`. `DocxWriter` builds the .docx by hand
  (it's just zipped XML), so there are no heavy dependencies.

## Install

**From GitHub Actions (no Android Studio needed):**
1. Open the repo's **Actions** tab, then the latest green **Build APK** run.
2. Download the `screenshot-notes-apk` artifact, unzip it, and copy `app-release.apk` to your phone.
3. Open it on the phone and allow "Install unknown apps" when asked.

**From source:** open the project in Android Studio and press Run, or run `./gradlew assembleRelease`.

> Note: CI builds are signed with a throwaway debug key that changes between runs. To update
> to a newer CI build you may have to uninstall first, **which deletes the document**, so export it
> first. To get seamless updates, build locally from Android Studio, or add your own signing key.

## Permissions

| Permission | Why |
|---|---|
| Photos (`READ_MEDIA_IMAGES`) | To see new screenshots. On Android 14+ choose **Allow all**, not "Select photos". |
| Display over other apps | So the note bar can pop up on top of the app you screenshotted |
| Notifications | For the watcher's ongoing notification and the fallback "Add note" reminder |
| Ignore battery optimisation | Optional. Stops aggressive OEM battery savers (Xiaomi, Samsung, OnePlus…) killing the watcher |

Requires Android 8.0 (API 26) or newer.
