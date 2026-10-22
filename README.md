# Expense Tracker (v1.2.0)

A 100% offline Android expense tracker built for Indian banking and UPI workflows. It automatically captures transactions from bank SMS and UPI app notifications on your phone, categorizes them with an on-device intelligence engine, and stores everything locally.

The app declares **no `INTERNET` permission**—your financial data cannot leave your device.

`Package: com.expensetracker.offline` · `App ID: com.srinand.expensetracker.offline` · `Version: 1.2.0` · `Min SDK 26 (Android 8.0+)` · `Target / Compile SDK 36`

---

## Quick Install (APK)

1. **Download the APK** from the [Releases](https://github.com/gamehackerever/app_expense_tracker/releases) page (`ExpenseTracker-v1.2.0.apk` / `app-release.apk`) and tap to open it.
2. **If Google Play Protect shows *"App scan recommended"*:** tap **Scan app**. Play Protect will scan the APK, verify that it is safe, and prompt you to tap **Install**.
3. **Open Expense Tracker** and complete the quick 2-step setup (choose **Automatic Hands-Free Tracking** or **Manual Mode**, and optionally set a monthly budget).

Need help with Android 13+ *"Restricted settings"* (for live UPI notifications) or phone battery settings? See the **[Installation & Troubleshooting Guide (INSTALL.md)](INSTALL.md)**.

---

## Key Features in v1.2.0

* **Hands-Free Auto-Capture**:
  * **Bank SMS Interceptor (`SmsCaptureReceiver`)**: Reads incoming bank debit/credit alerts and updates account balances offline.
  * **90-Day Inbox Scanner (`SmsHistoryScanner`)**: Imports your past 90 days of bank SMS on first launch with automatic duplicate and self-transfer filtering.
  * **Live UPI Notification Listener (`NotificationCaptureService`)**: Captures real-time payment alerts from GPay, PhonePe, Paytm, and banking apps.
* **On-Device Financial Intelligence (`SmartExpenseEngine` & `SmsPatternLearner`)**:
  * **Contextual Payee & Amount Predictor**: Suggests likely merchants and typical amounts based on time of day, day of week, and recency decay.
  * **Active Review Queue Learner**: Learns structural SMS/notification templates and UPI VPA handles whenever you approve or discard an item in **Needs Review**.
  * **Hybrid Categorizer**: Combines Jaro-Winkler fuzzy matching, subword 3-gram Naive Bayes, and log-amount priors across **18 Indian categories** (`Food & Dining`, `Groceries`, `Fuel & Fastag`, `Travel`, `Recharge & Telecom`, `Bills & Utilities`, `Rent & Housing`, `EMI & Loans`, `Investments`, etc.) and regional merchants.
  * **Smart Spend Forecaster & Habit Insights**: Outlier-dampened cycle projections and behavioral spend insights on the **Insights** screen.
* **Budgeting, Fixed Costs & Safe-to-Spend**:
  * Custom billing cycle reset day (`Day 1–28`), per-category monthly caps with warnings, recurring bill detection, and prorated sinking-fund reserves.
* **Bill Splitting & Thermal Receipt OCR**:
  * Split bills equally, by exact amounts, percentages, or shares; simplify group debts; scan multi-line restaurant/store bills with on-device ML Kit OCR; and settle up via generated UPI QR codes.
* **Privacy, Security & Encrypted Backups**:
  * Biometric / device-credential App Lock.
  * AES-256-GCM encrypted backups (PBKDF2-HMAC-SHA256 key derivation + hardware-backed Android Keystore passphrase storage), scheduled auto-backups via WorkManager, and full CSV import/export (including splits).

---

## Permissions Used

| Permission | Purpose | Required? |
|---|---|---|
| `RECEIVE_SMS`, `READ_SMS` | Read incoming bank transaction SMS and scan past SMS history | Only for automatic SMS capture |
| Notification Listener Access | Capture real-time UPI/bank app payment notifications | Optional (for live UPI app alerts) |
| `POST_NOTIFICATIONS` | Daily digest, low-balance alerts, and bill reminders | Optional |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep background capture active without OS sleep restrictions | Recommended for auto-capture |

---

## Building from Source

**Requirements:** Android Studio (Ladybug or newer) and JDK 17.

```bash
git clone https://github.com/gamehackerever/app_expense_tracker.git
cd app_expense_tracker
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Signed Release Build

1. Copy `keystore.properties.example` to `keystore.properties` in the project root and fill in your keystore path and credentials.
2. Run:

```bash
./gradlew assembleRelease
```

The signed, R8-minified release APK is generated at `app/build/outputs/apk/release/app-release.apk`.

---

## Project Structure

```
app/src/main/java/com/expensetracker/offline/
├── data/        Room database, entities, DAOs, and repositories
├── engine/      FinancialParser, SmartExpenseEngine, SmsPatternLearner, categorizer, interceptors, OCR, backups
├── ui/          Jetpack Compose screens & components (dashboard, insights, review, necessities, settings)
├── util/        AES-GCM backup manager, CSV engine, billing cycle helper, UPI QR utilities
├── widget/      Glance home screen widget
└── service/     Quick Settings tile for instant manual logging
```
