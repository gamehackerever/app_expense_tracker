# Expense Tracker

An offline Android expense tracker. It reads bank SMS and notifications on the device, turns them into categorized transactions, and keeps everything in a local database. The app declares no `INTERNET` permission, so nothing leaves the phone.

Package: `com.expensetracker.offline` · Min SDK 26 · Target SDK 35 · Kotlin + Jetpack Compose

## Features

- **Automatic capture** of transactions from bank SMS and notifications (`SmsCaptureReceiver`, `NotificationCaptureService`), plus a scan of existing SMS history.
- **Parsing and categorization** on-device: a rule-based parser, a Naive Bayes categorizer, and user-defined category rules.
- **Review queue** for transactions the parser isn't sure about.
- **Fixed Costs and Safe to Spend**: define recurring bills once, and see what is left after them.
- **Recurring payment detection** and an Insights screen.
- **Bill splitting**: split presets, debt simplification across a group, receipt scanning (ML Kit text recognition, on-device), and UPI QR codes for settling up.
- **Notifications**: daily digest, low-balance alerts, missing-transaction reminders.
- **Home screen widget** (Glance) and a Quick Settings tile for adding an expense.
- **Biometric lock.**
- **Backup and restore**: encrypted backups (AES-GCM, key derived from a passphrase with PBKDF2), automatic backups via WorkManager, and CSV import/export.

## Tech stack

Kotlin, Jetpack Compose (Material 3), Room (KSP), WorkManager, Glance, Navigation Compose, AndroidX Biometric, AndroidX Security Crypto, ML Kit Text Recognition, ZXing core.

## Permissions

| Permission | Why |
|---|---|
| `RECEIVE_SMS`, `READ_SMS` | Detect bank transaction messages and scan history |
| Notification listener access | Detect transactions from bank/UPI app notifications |
| `POST_NOTIFICATIONS` | Daily digest and alerts |

SMS and notification content is processed locally and is not sent anywhere.

## Build

Requirements: Android Studio (recent stable) and JDK 17.

```bash
git clone https://github.com/gamehackerever/app_expense_tracker.git
cd app_expense_tracker
./gradlew assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`.

### Release build

Release builds are signed with your own keystore. Copy `keystore.properties.example` to `keystore.properties`, fill in your keystore details, then run:

```bash
./gradlew assembleRelease
```

`keystore.properties` and keystores are git-ignored. Without that file, only debug builds are signed.

## Project layout

```
app/src/main/java/com/expensetracker/offline/
├── data/        Room entities, DAOs, repositories
├── engine/      parser, categorizer, interceptors, split, recurring, backup, notifications
├── ui/          Compose screens and components (dashboard, insights, settings, review, necessities)
├── util/        CSV, backup, billing cycle, UPI, helpers
├── widget/      Glance home screen widget
└── service/     Quick Settings tile
```

## Status

Personal project, version 1.0.0. Built and tested mainly against Indian bank SMS formats.