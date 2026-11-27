# Event Manager Apps (COMP1786 coursework)

Two mobile apps that share one Firebase Realtime Database:

| Folder | App | Technology | Spec features |
|---|---|---|---|
| `EventManager/` | **Admin app**: create and manage events and sessions, upload them to the cloud | Native Android, Java | a) – f) |
| `EventManagerUser/` | **User app**: browse events from the cloud, save favourites | .NET MAUI (C#) | g) – h) |

Cloud database (used by both apps):
`https://eventmanager-1d187-default-rtdb.asia-southeast1.firebasedatabase.app/`

---

## 1. Admin app (Android, Java)

### Requirements
- Android Studio (recent version) with its bundled JDK (the project compiles Java 11 source).
- Android SDK installed through Android Studio. The app needs **Android 7.0 (API 24) or newer**; it targets API 37.
- An emulator or a phone with internet access (for the cloud upload and the location search).

The Gradle wrapper (Gradle 9.6, Android Gradle Plugin 9.5) downloads itself the first time; no separate Gradle install is needed.

### Run it
1. In Android Studio choose **File → Open** and select the `EventManager` folder (not the repository root).
2. Wait for the Gradle sync to finish.
3. Choose a device and press **Run ▶**.

From a terminal instead (Windows, inside `EventManager/`):

```
gradlew.bat assembleDebug
```

The APK is written to `EventManager/app/build/outputs/apk/debug/app-debug.apk`.
If Gradle cannot find Java, set `JAVA_HOME` to Android Studio's bundled JDK, for example
`C:\Program Files\Android\Android Studio\jbr`.

### Unit tests
The event / session status rules (`util/EventStatus.java`) are covered by JVM unit tests in
`app/src/test/java/com/greenwich/eventmanager/EventStatusTest.java`:

```
gradlew.bat testDebugUnitTest
```

### Firebase
`EventManager/app/google-services.json` is included, so the app connects to the database above
without any extra set-up. The admin app writes with the Firebase Android SDK; data is stored under
`/events/{eventUuid}` with the sessions nested inside each event.

### Permissions
- **Internet / network state**: upload to the cloud and check the connection first.
- **Location** (asked only when the "use my location" button is tapped): fills in the event location.

---

## 2. User app (.NET MAUI)

### Requirements
- **.NET 10 SDK** with the **MAUI workload**:
  ```
  dotnet workload install maui
  ```
- For Android: the Android SDK and an emulator (Visual Studio or Android Studio can install them).
- Visual Studio 2022/2026 with the ".NET Multi-platform App UI development" workload is the easiest way to run it, but the command line also works.

### Run it
In Visual Studio open `EventManagerUser/EventManagerUser.slnx`, choose an Android emulator as the
target and press **Run**.

From a terminal (inside `EventManagerUser/EventManagerUser/`):

```
dotnet build -f net10.0-android
dotnet build -f net10.0-android -t:Run
```

The second command installs and starts the app on the running emulator.

### Cloud connection
The user app reads `events.json` from the database above through Firebase's REST API
(`Services/EventService.cs`), so no Firebase SDK or key is needed. The last response is saved on
the phone, so events can still be viewed offline. Events with the status **Draft** are never shown.

---

## 3. Trying the apps together
1. In the admin app, tap **Add event**, fill in the form and choose **Review & publish** (or **Save as draft**).
2. Open the event and add sessions with **Add session**.
3. Tap the cloud icon (**Sync now**) on the main screen, or use the menu → **Upload all events**.
   Changes are also uploaded automatically in the background when the device is online.
4. Open the user app and pull down to refresh the **Discover** list.

To see every status in the user app, create events dated today (Live), in a few days (Upcoming),
in the past (Ended), and use **Change status** on an event's detail screen to postpone or cancel it.

---

## 4. Feature overview

**Admin app**
- a) Event form with required and optional fields (description, email, phone), validation
  (required fields, DD/MM/YYYY dates, email and phone format, no duplicate name + date),
  a confirmation screen and the option to go back and edit.
- b) Sessions: add / edit / delete, auto-generated ID, type chips, day chips limited to the event's dates.
- c) Event list with the number of sessions; deleting an event deletes its sessions.
- d) Quick search (name starts with / description contains), session search inside an event,
  advanced search by date and organiser.
- e) Upload all events to Firebase, network check before uploading, automatic sync of local changes.
- f) Extra: event status (draft, published, postponed, cancelled + automatic upcoming / live / ended),
  dashboard, status filters, background sync (WorkManager), location search and "use my location",
  session QR codes, schedule export (.ics), multi-select delete, inline room-clash warning, dark mode.

**User app**
- g) Event list from the cloud, search by name and filter by date, event details with the schedule.
- h) Favourite events (the **Saved** tab). Extra: "Not interested" (hide events, with undo and a
  tab to restore them), live / upcoming / changed tabs, countdowns, offline cache.

---

## 5. Third-party code and references

Libraries (used through Gradle / NuGet, not copied into the project):

| Library | Used for |
|---|---|
| AndroidX AppCompat, Activity, ConstraintLayout | Android UI basics |
| Material Components for Android 1.10 | Material 3 widgets (cards, chips, text fields, pickers) |
| AndroidX WorkManager 2.10 | Background sync when the device is online |
| Firebase Realtime Database (Android SDK) | Upload from the admin app |
| Google Play services Location 21.3 | Current location for the event form |
| ZXing core 3.5 | Session QR codes |
| JUnit 4.13 | Unit tests |
| .NET MAUI, CommunityToolkit.Mvvm 8.4 | User app UI and MVVM helpers |

Other material:
- **Icons**: the vector icons in `EventManager/app/src/main/res/drawable/ic_*.xml` and the tab icons in
  `EventManagerUser/EventManagerUser/Resources/Images/tab_*.svg` use path data from
  Google's Material Icons (Apache License 2.0); each file says so in a comment. The launcher icons
  (`ic_launcher_*`) and the MAUI app icon / splash are the default templates from Android Studio and
  Visual Studio.
- **Design references**: the layout of the event and session forms (inline title, date / time rows,
  "Save as draft" + "Publish") was inspired by Google Calendar, Luma, Meetup and Eventbrite.
  No code was taken from these apps.
- Firebase REST API documentation (reading `/events.json` from the user app).

All other code was written for this coursework.
