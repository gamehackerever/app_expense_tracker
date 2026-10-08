# Installing Expense Tracker (APK)

Expense Tracker is not on the Play Store. You install it by downloading the APK from the [Releases](../../releases) page. Because of that, Android and Google Play Protect will show warnings, and Android will restrict the SMS and notification permissions the app depends on. This guide covers every step. Menu names differ between phone brands and Android versions, so use the closest match on your phone.

**Requirements:** Android 8.0 (API 26) or higher.

**What the app needs:**

| Access | Why | Required? |
|---|---|---|
| Install from unknown sources | You are installing outside the Play Store | Yes, to install |
| SMS (read and receive) | Detects bank transaction messages and scans old ones | Yes, for SMS capture |
| Notification access | Detects transactions from bank and UPI app notifications | Yes, for notification capture |
| Post notifications (Android 13+) | Daily digest, low-balance and reminder alerts | Optional |
| Unrestricted battery use | Stops your phone from killing the background capture | Strongly recommended |
| Biometric / screen lock | Only if you turn on the app lock | Optional |

The app has no internet permission. Your data stays on the device.

---

## Step 1: Download the APK

1. On your phone, open the [Releases](../../releases) page and download `ExpenseTracker-v1.0.0.apk` under **Assets**.
2. Or download it on a PC and copy it to your phone (USB, Google Drive, etc.).
3. Do not download an APK from anywhere other than this repository's Releases page.

Optional: to check the file wasn't corrupted or swapped, compare its SHA-256 with the one in the release notes (if listed). On a PC: `certutil -hashfile ExpenseTracker-v1.0.0.apk SHA256` (Windows) or `sha256sum ExpenseTracker-v1.0.0.apk` (Linux/macOS).

## Step 2: Allow installs from your browser or file manager

1. Tap the downloaded APK (from the browser's download notification, or from the **Files** / **Downloads** app).
2. Android will say something like *"For your security, your phone is not allowed to install unknown apps from this source."* Tap **Settings**.
3. Turn on **Allow from this source**. This is the app you opened the APK from (Chrome, Files, Drive, etc.).
4. Press back and tap **Install**.

If you don't get that prompt, enable it manually:
- **Android 8 and above:** Settings → Apps → Special app access → **Install unknown apps** → pick Chrome (or your file manager) → **Allow from this source**.
- On some phones this is under Settings → Security / Privacy → **Install unknown apps**.

After installing, you can turn this setting back **off** for that app.

## Step 3: Handle the Play Protect warning

You will probably see **"Blocked by Play Protect"** or **"App scan recommended"**. This appears because the app isn't distributed through the Play Store, and because it asks for SMS access. It does not mean it found a virus.

1. Tap **More details** (or **Details**), then **Install anyway**.
2. If a dialog offers **Scan app**, you can choose it. Play Protect will check the APK and usually lets you continue.
3. If it only shows **OK** with no way to proceed (some phones do this): open the **Play Store** → tap your profile picture → **Play Protect** → gear icon (Settings) → turn off **Scan apps with Play Protect**. Install the APK, then **turn the setting back on**.
   Only disable it if you downloaded the APK from this repository's Releases page. Leaving it off permanently is not recommended.

## Step 4: Open the app and grant permissions

1. Open **Expense Tracker**.
2. It will ask for **SMS** permission. Tap **Allow**. Without it, bank messages can't be read.
3. On Android 13 and above it will also ask to **send notifications**. Tap **Allow**.

**If Allow is greyed out or the permission is blocked, go to Step 5. This is the most common problem.**

## Step 5: Allow restricted settings (Android 13 and above)

Android blocks SMS and notification access for apps installed outside the Play Store until you approve them manually. This is a system protection, not a bug in the app. You may see *"Restricted setting. For your security, this setting is currently unavailable."*

1. Long-press the **Expense Tracker** icon → **App info** (or Settings → Apps → Expense Tracker).
2. Tap the **⋮** (three dots) at the top right.
3. Tap **Allow restricted settings**. Confirm with your PIN, pattern or fingerprint.
    - The option only appears after you have tried to enable a blocked permission at least once. If you don't see the ⋮ menu or the option, go back, try enabling the SMS or notification permission once so it gets blocked, then return here.
4. Now repeat the permission steps below.

## Step 6: Grant SMS permission manually (if needed)

1. Settings → Apps → Expense Tracker → **Permissions** → **SMS**.
2. Choose **Allow**.
3. On some phones it appears as **Messages** or under *Other permissions*.

## Step 7: Turn on Notification access

This lets the app read payment notifications from bank and UPI apps.

1. Open Expense Tracker → **Settings** and find the notification access option, which opens the system screen. Or go to Settings → **Notifications** → **Device & app notifications** (on some phones: Settings → Apps → Special app access → **Notification access**).
2. Find **Expense Tracker** in the list and switch it **On**.
3. Android shows a warning that the app can read all notifications. Tap **Allow**.
4. If the toggle is greyed out, do Step 5 first.

Also check that regular notifications are on: Settings → Apps → Expense Tracker → **Notifications** → **Allow**.

## Step 8: Set battery use to Unrestricted

Android and phone makers aggressively stop background apps, which can cause missed transactions. Do this so captures keep working when the app is closed.

**Stock Android / Pixel:**
Settings → Apps → Expense Tracker → **App battery usage** (or Battery) → **Unrestricted**.

**Samsung (One UI):**
- Settings → Apps → Expense Tracker → **Battery** → **Unrestricted**.
- Settings → Battery → **Background usage limits** → make sure Expense Tracker is not in *Sleeping apps* or *Deep sleeping apps*. Optionally add it to **Never sleeping apps**.

**Xiaomi / Redmi / POCO (MIUI / HyperOS):**
- Settings → Apps → Manage apps → Expense Tracker → **Battery saver** → **No restrictions**.
- Same screen: turn on **Autostart**.
- In Recent apps, long-press the app card and tap the **lock** icon so it isn't cleared.

**OnePlus / Oppo / Realme (OxygenOS / ColorOS):**
- Settings → Apps → Expense Tracker → **Battery usage** → **Allow background activity** / **Unrestricted**.
- Turn on **Auto-launch** if shown.

**Vivo (Funtouch / OriginOS):**
- Settings → Battery → **Background power consumption management** → Expense Tracker → **Allow high background power consumption**.
- Settings → Apps → Autostart → turn it on for Expense Tracker.

**Other brands:** search Settings for "battery optimization", "background activity" or "autostart" and allow Expense Tracker. The website [dontkillmyapp.com](https://dontkillmyapp.com) has per-brand instructions.

Also turn off any **Battery saver / Power saving mode** if you notice that transactions arrive late or not at all.

## Step 9: Import your past transactions

Open the app and use the history scan option (in Settings or the first-run prompt) to read existing bank SMS. It runs locally.

## Step 10: Optional app lock

If you enable the app lock in Settings, your phone must have a screen lock or fingerprint set up. If you remove your phone's screen lock later, the app lock can't authenticate.

---

## Troubleshooting

**"App not installed"**
- You may already have a copy signed with a different key. Uninstall that copy first (back up first, see below).
- Not enough storage, or the APK download was incomplete. Download it again.
- Android 8 or newer is required.

**Transactions aren't being captured**
1. Check that SMS permission is **Allowed** (Step 6).
2. Check that Notification access is **On** (Step 7).
3. Check that battery is **Unrestricted** (Step 8).
4. Check the sender: only recognised bank/UPI senders are processed, so unusual sender IDs may be ignored. Add a transaction manually if needed.
5. Send yourself a test: make a small payment and see whether it shows up.

**Notification access turns itself off**
Some phones disable notification listeners when an app is force-stopped or the battery saver kills it. Re-enable it (Step 7) and set battery to Unrestricted (Step 8).

**"Allow restricted settings" is missing**
Try to enable the blocked permission once first, then check the ⋮ menu in App info again. This setting only exists on Android 13 and above.

**Play Protect keeps warning me**
This is normal for sideloaded apps with SMS access. It's a warning, not a detection of malware.

---

## Updating

1. Download the new APK from Releases.
2. Open it and tap **Install**. Android updates in place and keeps your data, as long as it's signed with the same key as the version you have installed.
3. Re-check your permissions and battery setting afterward, since some phones reset them after an update.

## Uninstalling and backups

The app does not use Android's cloud backup (`allowBackup` is off), so **uninstalling deletes all your data**. Before uninstalling or switching phones:
1. Open the app → Settings → create an **encrypted backup** (or export a **CSV**).
2. Save the backup file somewhere outside the app (Downloads, Drive, PC).
3. Remember your backup passphrase. It can't be recovered.

To uninstall: Settings → Apps → Expense Tracker → **Uninstall**. To remove access without uninstalling, revoke SMS permission and turn off Notification access.

## Privacy

- No internet permission, so the app cannot send data anywhere.
- SMS and notification content is parsed on the device.
- Backups are encrypted with a passphrase you choose.
- The source code is in this repository, so you can check it.