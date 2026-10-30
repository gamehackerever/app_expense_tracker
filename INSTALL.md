# Installation & Setup Guide (v1.2.1)

Expense Tracker is **100% offline** (it has no `INTERNET` permission, so your data never leaves your phone). Because you download the APK directly from GitHub Releases instead of the Play Store, Android may ask you to confirm the install.

**Requirements:** Android 8.0 (API 26) or higher.

---

## Quick Install (Takes ~60 Seconds)

### 1. Download & Open the APK
1. Download the latest APK (`ExpenseTracker-v1.2.1.apk` or `app-release.apk`) from the [Releases](https://github.com/gamehackerever/app_expense_tracker/releases) page.
2. Tap the downloaded file to open it.
3. If your browser or file manager asks for permission to install apps, tap **Settings** → turn on **Allow from this source**, then press back.

### 2. Tap "Scan app" on the Play Protect Prompt
Because the APK is distributed outside the Play Store, Google Play Protect may display **"App scan recommended"** (*"Play Protect hasn't seen this app before"*):
1. Tap **Scan app**.
2. Play Protect will scan the APK, confirm it is safe, and show the **Install** button.
3. Tap **Install**.

### 3. Open the App & Complete the 2-Step Setup
1. Open **Expense Tracker**.
2. Choose **Automatic Hands-Free Tracking** (recommended) or **Manual & Receipt Entry Only**.
3. Tap **Allow** when prompted for SMS and notification permissions—the app will automatically scan your last 90 days of bank SMS and set up your accounts.
4. Pick an optional monthly budget preset and tap **Start Tracking**.

---

## Optional: Enabling Live UPI App Notifications (Android 13+)

Bank SMS auto-capture works immediately once you grant SMS permission on first launch.

If you also want to capture live push notifications from **GPay, PhonePe, Paytm, or banking apps**, tap the **Auto-Capture Status** card (or go to **Settings → System Health → Notification Interceptor**):

1. Tap **Enable Notification Access** and switch **Expense Tracker** to **On**.
2. **Is the toggle greyed out (*"Restricted setting"*)?**
   On Android 13, 14, and 15, Android locks special permissions for non-Play Store apps until you unlock them once in App Info:
   - Open **App Info** for Expense Tracker (long-press the app icon → **App info**, or tap the *"Greyed out?"* shortcut inside the app).
   - Tap the **⋮** (three dots) in the top-right corner.
   - Tap **Allow restricted settings** and verify with your fingerprint or PIN.
   - Return to **Notification Access** and toggle **Expense Tracker** on.

*(Tip: If you don't see the **⋮** menu in App Info right away, tap the greyed-out toggle once first so Android registers the blocked attempt, then reopen App Info.)*

---

## Preparing for Everything: Fallbacks & Edge Cases

### What if Play Protect shows a different dialog on my phone or a future update?
Google occasionally updates Play Protect rules for apps that use SMS permissions outside the Play Store. Here is how to handle every possible screen:
* **"App scan recommended"**: Tap **Scan app** → wait a few seconds for the scan to pass → tap **Install**.
* **"Unsafe app blocked" / Warning with "More details"**: Tap **More details** → tap **Install anyway**.
* **Hard block with only an "OK" button (no "More details" option)**:
  1. Open the **Google Play Store** app → tap your profile icon (top right) → **Play Protect**.
  2. Tap the **⚙️ (Settings)** gear icon (top right) → pause **Scan apps with Play Protect**.
  3. Install the Expense Tracker APK, then **turn Play Protect back on immediately**.

### Keeping Background Auto-Capture Reliable (Battery Settings)
If Android delays background alerts, open **App Info → Battery** and choose **Unrestricted** or **Allow background activity** if your device offers that option. This is optional and varies by phone brand:
* **Samsung (One UI):** *App Info → Battery → Unrestricted*, and ensure the app is not in *Settings → Battery → Background usage limits → Deep sleeping apps*.
* **Xiaomi / Redmi / POCO (HyperOS / MIUI):** *App Info → Battery saver → No restrictions*, and enable **Autostart**.
* **OnePlus / Oppo / Realme (OxygenOS / ColorOS):** *App Info → Battery usage → Allow background activity*.
* **Vivo / iQOO (Funtouch / OriginOS):** *Settings → Battery → Background power consumption → Allow high background power consumption*, and enable **Autostart**.
* **Pixel / Stock Android:** *App Info → App battery usage → Unrestricted*.

---

## Updating & Backups

### Installing Updates
Download the newer APK from [Releases](https://github.com/gamehackerever/app_expense_tracker/releases) and tap **Install**. Android updates the app in place and preserves all your transactions, categories, and settings.

### Backing Up Before Uninstalling or Switching Phones
For strict privacy, Android cloud auto-backup is disabled (`allowBackup="false"`), which means **uninstalling the app erases its local database**. Before uninstalling or moving to a new phone:
1. Open **Expense Tracker → Settings → Backup & Restore**.
2. Tap **Backup Now** to save an **AES-256 encrypted backup** (or use **CSV Export**).
3. On your new phone, tap **Switching phones? Restore from backup** right on the welcome screen (or use **Restore** in Settings).
