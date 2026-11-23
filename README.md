# Journal Me — Android

The Android app writes most of your journal for you. It records your day in the background: places, Strava-grade routes for walks, runs, rides and drives, steps, and the day's photos and videos, each tagged with where it came from (your camera, WhatsApp, a friend's phone…). **Every day is saved to Telegram in full**, journaled or not; tap *Save to Journal* on the days you want as **day story** entries, and open any other day from the calendar on the day view. The UI follows Apple Journal and is the same React app as `../web`, running inside a Capacitor WebView, so phone and web share one journal through your Telegram vault.

## Two repositories, one folder

This repo is the native half. The web app lives in [JournalMe-web](https://github.com/Debashish19-prog/JournalMe-web), and the Android build needs it checked out next to this one, as `web/`: Gradle loads Capacitor from `../web/node_modules`, and the web build is copied into `android/` from there.

```bash
mkdir "Journal Me" && cd "Journal Me"
git clone https://github.com/Debashish19-prog/JournalMe-web.git web
git clone https://github.com/Debashish19-prog/JournalMe-android.git android
```

## Build and install

You need Android Studio (its bundled JDK 25 is what Gradle runs on) and the `web/.env` file with your Telegram `api_id` / `api_hash` (copy `web/.env.example` and fill it in from <https://my.telegram.org> → *API development tools*).

```bash
cd web
npm install
npm run android:sync     # production web build (Android flavour) → copied into android/
npm run android:open     # opens this folder in Android Studio → Run ▶ on your phone
```

Or build an APK from the command line:

```bash
export JAVA_HOME="$HOME/Android Studio/android-studio-rabbit2-linux/android-studio/jbr"   # your Studio's JBR
cd web && npm run android:apk
# → android/app/build/outputs/apk/debug/app-debug.apk   (adb install -r <that file>)
```

Run `npm run android:sync` again after every change in `web/`: the app ships a copy of the web build.

**Toolchain:** Gradle 9.7.1 · Android Gradle Plugin 9.5.0 (it compiles Kotlin 2.2 itself) · JDK 25 (Android Studio's JBR) · compile/target SDK 36 · **minSdk 29 (Android 10)**. AGP 8.x, which the Capacitor template ships with, doesn't run on Gradle 9.6+; the reason is noted in `gradle/wrapper/gradle-wrapper.properties`.

## First run

1. Sign in with your phone number (Telegram), as on the web.
2. Open **⋯ → Day Tracking** and work down the checklist. Every item says why it's needed:
   - **Location**, then **Location all the time**: Android sends you to Settings; pick *Allow all the time*.
   - **Physical activity**: walk/run/ride/drive detection and the step counter.
   - **Photos & videos**: choose *Allow all*, not *Select photos*. It also asks for photo location access, so EXIF GPS isn't blanked.
   - **Notifications**: Android requires one while tracking runs; the evening recap uses it too.
   - **Unrestricted battery**: important on Xiaomi, Samsung, OnePlus, Oppo and Vivo, which otherwise kill background apps.
3. Turn on **Track my day**. Optionally turn on the **evening recap**, a notification like "8.2 km · 6,430 steps · 14 photos · Tap to see your day".

From then on you don't need to open the app. When you do, it catches up: today's view refreshes, and every finished day gets its story (up to two weeks back, never before the day tracking was turned on).

## What you see

| Screen | What's on it |
| --- | --- |
| **Journal** (home) | Apple Journal-style feed. A row of highlight cards (Today so far, streak, map). The centred **+** opens a sheet: *New Entry*, or start from a moment (today so far, yesterday, a workout, a burst of photos, a place, a prompt). |
| **Day** (`Today` card, or ⋯ → Today) | Map of the day (routes coloured by activity, stops, photos pinned where they were taken), distance · moving time · steps · places, Strava-style activity cards, a timeline, weather, calendar (opt-in). **Photos & Videos** has filter chips: *Clicked by you · Chats · Shared with you · Screenshots · Other*. Tap ✓/+ on a photo to put it in or take it out of the day story. *Save to Journal* / *Write about it*. |
| **Activity** (tap a route) | Strava-style: route map with start/finish, distance, moving time, pace or speed, elevation gain, best pace or max speed, steps, an elevation profile and per-km splits. Rename it, or fix a wrong walk/run/ride/drive guess. |
| **Day story** entries | Photo mosaic (with source badges such as "WhatsApp" or "iPhone 15"), the day map, the stats, the weather. Edit it like any entry. Once you've edited it, the app never rewrites it; delete it and it stays deleted. |

## How it works

```
 Android (Kotlin, runs with the app closed)          WebView (React, runs when the app is open)
 ┌──────────────────────────────────────────┐        ┌──────────────────────────────────────────────┐
 │ DayTrackerService (foreground, location) │        │ services/day/autoJournal  on open/resume/10m │
 │  moving: GPS fix every 2 s (5 s balanced)│        │  processDay(today) + finished days (≤120 d)  │
 │  still:  low-power fix every 2–5 min     │ Journal│ services/day/ingest                          │
 │  recording: 1 s (manual route)           │ Native │  fixes → core/segments → visits + routes     │
 │ Activity Recognition · step counter      │ plugin │  gallery → core/mediaOrigin (camera/WhatsApp…)│
 │ MediaScanner (MediaStore + EXIF GPS)     │ ─────► │  steps, calendar, weather (Open-Meteo)       │
 │ Calendar · recap & watchdog alarms       │        │ services/day/story → day story (on request)  │
 │ SQLite: fixes, activities, steps (120 d) │        │ Dexie → Telegram: day, gallery, gps, places  │
 └──────────────────────────────────────────┘        └──────────────────────────────────────────────┘
```

- **The contract** between the two layers is one file: [`web/src/platform/journalNative.ts`](https://github.com/Debashish19-prog/JournalMe-web/blob/main/src/platform/journalNative.ts). The Kotlin plugin is [`JournalNativePlugin.kt`](app/src/main/java/in/journalme/app/JournalNativePlugin.kt).
- **Battery strategy, the same idea as Strava plus Google Timeline.** GPS runs at full rate only while you're moving. `MotionDetector` (pure Kotlin, unit-tested) drops to a low-power mode after 4 minutes within ~60 m (2 minutes if activity recognition says you’re still). It switches back as soon as a fix lands more than ~100 m away or activity recognition says you’re walking, riding or driving.
- **Every day goes to Telegram, journaled or not.** Visits and simplified routes go in the month's `places` bundle. Steps, weather and calendar go in a `day` record. The day's photo/video index (source, place, ~10 KB thumbnail) goes in a `gallery` record, with **every original** uploaded once. Each finished day's raw fixes and activity samples go in a `gps` record, read straight from the tracker's SQLite when uploading. The phone still prunes raw fixes after 120 days; the Telegram copy stays. A one-time backup queues the days that were processed before this existed.
- **It syncs by itself, with the app closed.** The day tracker keeps the app's process alive all day, so it also runs the Telegram backup ([`BackgroundSync.kt`](app/src/main/java/in/journalme/app/sync/BackgroundSync.kt)). It runs every 30 minutes (10 after a failed run), a few seconds after you leave the app, and when Wi-Fi comes back while photos wait for it. A run opens `worker.html` (the web app's background page, [`web/src/worker/main.ts`](https://github.com/Debashish19-prog/JournalMe-web/blob/main/src/worker/main.ts)) in a WebView with no screen, at the app's own origin, so it shares the app's storage and Telegram login. It processes the days and pushes and pulls with the same code the open app runs, then closes. Native data reaches it through a small JS bridge (the read-only half of the plugin, [`NativeQueries.kt`](app/src/main/java/in/journalme/app/NativeQueries.kt)). It never runs while the app is on screen; a run in progress stops when you open the app, and a lock keeps the two from syncing at once. Without day tracking, it only runs right after you leave the app.
- **Uploads stream from the gallery.** [`GalleryFiles.kt`](app/src/main/java/in/journalme/app/media/GalleryFiles.kt) serves byte ranges of gallery originals (`/_jm_file_`), and the uploader ([`web/src/services/telegram/upload.ts`](https://github.com/Debashish19-prog/JournalMe-web/blob/main/src/services/telegram/upload.ts)) sends them to Telegram in 512 KB parts as it reads them, so nothing is copied to disk and a 4 GB video never sits in memory. Files over 2000 MB go up as several documents. Photo/video originals wait for an unmetered network when *Photos and videos on Wi-Fi only* is on (the default); everything else syncs on any connection.
- **Photos aren't copied on the phone.** The index and day stories reference gallery originals by `content://` URI and read them only to upload to Telegram. A photo deleted from the gallery after its upload stays in the day (marked `missing`) and shows from Telegram; one deleted before it was uploaded is dropped. Off the phone (the web app), the grid shows thumbnails and the viewer downloads originals from Telegram. On the phone, originals that aren't in the gallery any more are downloaded into memory only when opened, and the editor's own copies (camera shots, pasted images, voice notes) are deleted once they're in Telegram. HEIC photos show as their thumbnail there, because desktop Chrome can't decode HEIC.
- **Where a photo came from** ([`core/mediaOrigin.ts`](https://github.com/Debashish19-prog/JournalMe-web/blob/main/src/core/mediaOrigin.ts)) is worked out from its folder, the app that saved it, its file name (`IMG-…-WA0012`), and the EXIF camera model compared with this phone. A WhatsApp photo is marked *sent* or *received*. An original from another phone shows that phone's model.
- **The Android back button** closes the top sheet or dialog, then goes back, then leaves the app. The recap notification opens `journalme://day/today`.

## Verified so far, and what isn't

Verified on the development machine (no phone or emulator was available):

- `./gradlew testDebugUnitTest assembleDebug` succeeds; the 14 JVM tests pass (motion state machine, media metadata parsing).
- The TypeScript type-checks. The day segmentation, route stats and photo-source rules were run against a synthetic day: run, ride, walks and drives are detected correctly, and 14 photo-source cases (camera, WhatsApp sent/received/Business, Telegram, screenshots, Quick Share from an iPhone, downloads, edits) are classified correctly.
- Every new screen was rendered at phone size in light and dark mode with a fake phone (`localStorage['jm.dev.demo'] = '1'` in a dev build; never in production builds).

**Not yet tried on a real device:** the background sync (a windowless WebView opened by the tracker, its throttling when nothing is on screen, sharing IndexedDB and the Telegram login with the app, the Web Locks between them), streamed uploads of real multi-GB videos, permission dialogs (including background location and Android 14's "Select photos"), the foreground service's start rules on Android 12–16, mode switching and battery drain, activity recognition and step counts, EXIF location, thumbnails, calendar, the notifications and alarms, restart after reboot, OEM battery killers, deep links from the notification, and Telegram sign-in inside the WebView. Expect to tune thresholds (`MotionDetector`, `core/segments.ts` `DEFAULT_SEGMENT`) after a few days of real data.

## Troubleshooting

- **Tracking stops overnight or after a while:** set *Unrestricted battery* in Day Tracking. On Xiaomi also enable *Autostart*; on Samsung remove Journal Me from *Sleeping apps*.
- **Photos show no location:** allow *Photos & videos → Allow all*, and grant photo location access (Day Tracking re-asks for it).
- **Routes look jagged or walks show up as runs:** use *Best* accuracy. Fix a wrong activity type on the route's page (⋯ → Rename or change type).
- **Debugging the WebView:** `chrome://inspect` on your computer while the phone is connected over USB.
