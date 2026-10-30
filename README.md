# Mock Loc

On-device mock location app with an OpenStreetMap picker, built by GitHub Actions (no Android Studio needed).

## Build with GitHub
1. Upload ALL files of this folder to a GitHub repo (settings.gradle.kts at the repo root). Make sure these are included:
   `.github/workflows/build.yml` and `app/mockloc.keystore`.
2. The "Build APK" workflow runs automatically on every upload (Actions tab, or press Run workflow).
3. When it is green, open the **Releases** page of your repo (right side on desktop, or the "Releases" link on the mobile site),
   open the newest "Mock Loc build N" and tap **MockLoc.apk** to download. Install it.
   (The Actions run also keeps a `MockLoc-apk` zip artifact.)

## Updating
Every build is signed with the same key (app/mockloc.keystore) and has a higher version number, so a new APK installs
**over** the old one and keeps your favorites and settings.
Only the very first time after switching to this signed build you must uninstall the older version once
(it was signed with a different, temporary key).

## Phone setup (one time)
1. Settings > About phone > tap Build number 7 times.
2. Developer options > Select mock location app > Mock Loc.
3. App info > Battery > Unrestricted.
4. Optional: turn off Google Location Accuracy so real Wi-Fi/cell positions aren't blended in.

## Use
- Search (pick from up to 5 results) or tap the map to drop a pin, then **Start here**.
- Choose Walk / Jog / Bike / Car or drag the speed slider (1-120 km/h). You can change speed while moving.
- Drop a new pin and tap **Move to pin**: you follow real roads/paths (OpenStreetMap routing), with gentle acceleration and braking.
  If routing is unavailable it falls back to a straight line.
- **Pause / Resume** freezes movement. **Center** re-centers and follows you. Touching the map stops following.
- **Loop** chip: at the end of a route, turn around and walk it back, repeatedly.
- **Joystick** chip: steer by hand (up = north); push further to go faster, up to the speed slider value.
- **Save pin / Favorites** store places. **Stop** (or the notification's Stop) returns to real GPS.
- Paste coordinates like `28.6139, 77.2090` into the search box to jump straight to that spot.
- The marker glides smoothly between location updates, the map goes dark in dark mode, and you get an "Arrived" message at the end of a route.
- **Quick Settings tile:** pull down the notification shade, tap the pencil/edit icon, and drag the **Mock Loc** tile into your active tiles.
  Tap it to stop mocking from anywhere (or to open the app when idle).
- **Add stop** chip (top-left of the map): drop a pin, tap **Add stop** (orange dot), repeat up to 8 times, then pick the final pin and tap **Move to pin**. The route goes through every stop in order. Long-press the chip to clear stops.
- The notification has **Pause / Resume** and **Stop** buttons while you are moving.
- If the app ever crashes, it shows the error on the next launch with a **Copy** button so you can send it to me.
- **Timer** chip: choose Off / 15 min / 30 min / 1 h / 2 h. Mocking switches itself off when the time is up, so you can't forget it is on.
- **Less / More** button: hides the speed and extra buttons to give the map more room.
- The chip bar at the top of the map scrolls sideways if your screen is narrow.
- When you start a move, a message shows the route length and rough time.
- Map tiles come from OpenStreetMap's public servers. The app identifies itself with a proper User-Agent as their policy requires, and shows the
  "© OpenStreetMap contributors" credit on the map (tap it for the licence). Keep usage personal and light.
