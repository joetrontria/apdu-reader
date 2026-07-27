# APDU Reader

Android app where the phone acts as a **contactless reader only**. Two things it does:

1. **Send APDU** — type any command APDU as hex, tap a tag, see the raw response and decoded status word.
2. **Read Type 4 tag** — runs the full NFC Forum Type 4 read sequence and decodes the NDEF message.

No card emulation, no HCE.

## Build

Drop the `app/` folder into a new Android Studio project (Empty Views Activity, Java), or add a
top-level `settings.gradle` with `include ':app'`. `minSdk` is 21, `compileSdk` 34.

## How it works

Type 4 tags speak ISO 7816-4 over ISO-DEP, so everything is plain APDUs:

| Step | APDU | Meaning |
|---|---|---|
| 1 | `00 A4 04 00 07 D2760000850101 00` | SELECT the NDEF Tag Application by AID |
| 2 | `00 A4 00 0C 02 E103` | SELECT the Capability Container file |
| 3 | `00 B0 00 00 0F` | READ BINARY the 15-byte CC |
| 4 | `00 A4 00 0C 02 <ndef file id>` | SELECT the NDEF file the CC points at |
| 5 | `00 B0 00 00 02` | READ BINARY 2 bytes → NLEN |
| 6 | `00 B0 00 02 <len>` | READ BINARY the message, chunked if needed |

The CC gives the NDEF file ID (bytes 9–10), the max NDEF size (11–12), and MLe (3–4), which caps
how many bytes one READ BINARY may ask for. Long messages get read in chunks.

A `61 xx` status word is handled automatically by issuing `00 C0 00 00 xx` (GET RESPONSE).

## Files

- `MainActivity.java` — reader mode setup, tag dispatch, logging
- `Type4TagReader.java` — the Type 4 sequence and NDEF record parsing (Text + URI records)
- `HexUtil.java` — hex conversion and status-word decoding

## Notes

- Reader mode is enabled in `onResume` and disabled in `onPause`, so the app only owns the NFC
  field while it is in the foreground.
- `FLAG_READER_SKIP_NDEF_CHECK` stops the platform from reading the tag behind your back, which
  would otherwise interfere with your own APDU exchange.
- `onTagDiscovered` runs on a **background** thread. All logging is posted back to the UI thread.
- `getMaxTransceiveLength()` on most phones is around 250–65535 bytes; respect it if you send
  extended-length APDUs.
- If a tag reports no ISO-DEP, it is a Type 1/2/5 tag and cannot accept APDUs at all.

## Getting an APK

### Option A — GitHub Actions (no local tooling)

1. Create a new GitHub repo and push this folder to `main`.
2. The `Build APK` workflow runs automatically (GitHub's runners already have the Android SDK).
3. Open the run under the **Actions** tab, download the `apdu-reader-debug` artifact, unzip it.
4. Copy `app-debug.apk` to your phone, then enable *Install unknown apps* for your file manager
   and tap it.

You can also trigger it manually from Actions → Build APK → Run workflow.

### Option B — Android Studio

Open this folder, let it sync, then **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
The result lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Option C — Command line

Needs a JDK 17 and the Android SDK with `ANDROID_HOME` set:

```
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the standard debug key, which is fine for sideloading onto your own
phone but not for the Play Store.
