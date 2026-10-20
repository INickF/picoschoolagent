# PicoSchoolAgent

A small Android service for PICO 4 headsets that lets **PicoSchoolStream** (`D:\PicoSchoolStream`)
list, launch, close, install and delete apps on each headset. It starts on boot and serves a JSON API on port **3350**,
the same way PICO's cast server uses port 3342.

## Build
```powershell
.\build.ps1          # -> build\PicoSchoolAgent.apk
```
The build doesn't need Gradle or an internet connection. It uses the Android SDK and JDK from `ANDROID_HOME` / `JAVA_HOME`, or
from the newest Unity editor that has Android Build Support installed.

The first build creates `agent.keystore`. **Back it up.** Every later build must be signed with the same key,
otherwise the headsets reject the update (you would have to uninstall the agent first).

## Install (once per headset)
1. On the headset, enable developer mode (Settings → About → tap *Software version* repeatedly) and turn on
   USB debugging.
2. Connect it over USB and accept the debugging prompt inside the headset.
3. Run:
   ```powershell
   .\setup.ps1      # every connected headset; or .\setup.ps1 -Devices <serial>
   ```
   This installs the APK, grants the permissions a normal app can't get on its own
   (`SYSTEM_ALERT_WINDOW` for starting apps from the background, `GET_USAGE_STATS` for detecting the current app,
   `REQUEST_INSTALL_PACKAGES` for installing APKs sent from the PC),
   exempts the agent from battery optimisation, **enables its accessibility service** and starts it.

After that the headset can be unplugged and rebooted: the agent comes back by itself about 25 s after boot.
Updates are installed the same way (`build.ps1` then `setup.ps1`). Headsets set up before version 1.5 need
`setup.ps1` once more for installing apps (the new permission).

The agent is hidden: it doesn't appear in the app library or recent apps. It shows up only in
Settings → Apps and as an enabled service under Settings → Accessibility. If someone turns that service off,
the agent stops starting after a reboot until `setup.ps1` runs again.

## API (`http://<headset-ip>:3350`)
| Request | Body | Result |
|---|---|---|
| `GET /api/status` | | `version, model, name, android, battery, charging, asleep, foreground {package,label} or null, permissions {overlay, usage}` |
| `GET /api/apps` | | `apps: [{package, label, system}]`, user-installed apps only |
| `POST /api/launch` | `{"package": "...", "closePrevious": true}` | starts the app; closes the previously open app |
| `POST /api/close` | `{}` / `{"package": "..."}` / `{"all": true}` | closes the current app / that app / every app |
| `POST /api/install` | `{"url": "http://<pc>/....apk"}` | downloads that APK and installs it; returns the job at once |
| `POST /api/uninstall` | `{"package": "..."}` | deletes a user app; returns the job at once |

`status.job` is the running install / delete, or the last one for 60 s after it ends (otherwise `null`):
`{kind: install|uninstall, state: downloading|installing|confirming|done|failed, progress, package, label, needsWearer, error?, message?}`.
Only one job runs at a time per headset.

Errors return `{"ok": false, "error": "<code>"}`. The codes are `no_overlay_permission`, `not_launchable`, `missing_package`,
`home_refused`, `not_found`, `internal`, and for install / delete `busy`, `missing_url`, `not_user_app`.
A failed job reports `download_failed`, `invalid_apk`, `install_failed`, `uninstall_failed`, `cancelled`, `no_space`,
`incompatible`, `conflict` or `not_confirmed` in `job.error`.

### How closing works
A normal Android app is not allowed to force-stop other apps. If the headset is asleep, the agent first turns
the screen on for a few seconds. It then opens the home screen (`com.pvr.vrshell`) by component name, because
PICO refuses `CATEGORY_HOME` intents from third-party apps. Once the app is in the background, the agent kills it
with `killBackgroundProcesses` (after 1.5 s and again after 4 s).

### Switching apps
If a VR app is started while another one is running, PICO asks the wearer to confirm closing the current app.
To avoid that, `launch` first closes the running app the same way `close` does (home, then kill, about 2 s). Only then
does it start the new app. The response therefore takes a few seconds.

### How installing and deleting works
A normal app can only install or delete apps through the system prompt ("Install?" / "Delete?"). The agent:
1. downloads the APK from the URL the PC sends (the PC serves the file; the agent's own server only takes small JSON),
2. reads the package name and label from it, opens an installer session and commits it (delete: `PackageInstaller.uninstall`),
3. opens the system prompt that Android answers with, waking the headset first,
4. presses **Install** / **ОК** in that prompt through its accessibility service (`install_button_install`, `android:id/button1`,
   or the button text as a fallback). The service only receives events from `com.android.packageinstaller`, and only acts
   while a job is waiting.

PICO drops a prompt opened while its VR shell is still waking up (logcat: `no-history finish`), which happens to almost
every delete prompt on a sleeping headset. So after waking the headset the agent waits 2.5 s before showing the prompt, and
shows it again if nothing was pressed after 5 s (up to 3 times). Only then does `job.needsWearer` turn true, and the prompt
waits for someone in the headset. After 3 minutes without an answer the job fails with `not_confirmed`.

Deleting an app doesn't interrupt the wearer: only the app in use is closed first (home + kill), any other app is just deleted.

Verified on PICO 4 (A81E0, Android 10) **with nobody wearing the headset** (proximity sensor "far" throughout): 14 installs and
deletes, started while asleep (right as it fell asleep) and while the screen was still on, all finished without anyone
pressing anything. Install takes about 3 s after the download; delete 1–9 s, depending on whether the first prompt was dropped.

### Behaviour verified on PICO 4 (A81E0, Android 10)
- Starts after a reboot (via the accessibility service) and after an update, with no USB needed.
- Launch, current-app detection, close, switching apps, and close-all, both while worn and while asleep.
- Asleep = nobody wearing it: PICO sleeps 10 s after the proximity sensor reads "far". The VR app stays loaded
  but paused, and the status reports it with `"asleep": true`.
  An app launched on a sleeping headset opens when someone puts the headset on.

### PICO restrictions found while testing
- `ProcessIntercept` blocks starting third-party processes by broadcast, **including `BOOT_COMPLETED`**,
  which is why the accessibility service is used to start the agent.
- Third-party apps can't start the launcher through `CATEGORY_HOME` ("refuse third part app start launcher").
- A Home press through accessibility (`GLOBAL_ACTION_HOME`) is ignored by PICO's shell.
- `am start` from adb is refused while a VR app is in front ("activity is being kept for the user").
  setup.ps1 hits this but doesn't depend on it: the accessibility service restarts the agent after install.
- `install -g` runtime grants are denied; the app-op grants (`appops set`) work.

## Notes
- No authentication: anyone on the same network can send commands. Keep the headsets on the school network only.
- If the headset sleeps or Wi-Fi turns off, the agent shows as offline until the headset wakes up.
- Logs: `adb logcat -s PicoAgent PicoAgentHttp`
