# My Money – Personal Finance & Expense Tracker (Android)

A native Android app (Kotlin + Jetpack Compose) to track income, expenses, bank accounts, credit cards,
cash, transfers, scheduled payments (EMI / SIP / bills), money lent and borrowed, budgets, savings goals
and reports. It is **offline-first**: every financial record lives in the on-device Room database and the
app is fully usable without an account or a connection. Version 1.1 adds optional Google Sign-In,
encrypted Google Drive backup & restore, seven home screen widgets, smart notifications and a
notification center.

> My Money is a tracker. It never initiates real payments and never asks for bank passwords, full card
> numbers, CVVs or UPI PINs.

## Quick start

### Install the prebuilt APK
`dist/MyMoney-1.1.0-debug.apk` (application id `com.mymoney.app.debug`; also built to `app/build/outputs/apk/debug/app-debug.apk`).

```bash
adb install -r dist/MyMoney-1.1.0-debug.apk
```

Without a Google/Firebase configuration (see below) the app still installs and works fully offline;
"Continue with Google" is shown as unavailable and explains why.
Or copy the APK to the phone and open it (allow "Install unknown apps" for your file manager).

### Build from source
Requirements: JDK 17 and the Android SDK (platform 35, build-tools 35). Android Studio Ladybug or newer
works out of the box.

```bash
# local.properties must point at your SDK, e.g. sdk.dir=/Users/you/Library/Android/sdk
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # JVM + Robolectric unit/UI tests
./gradlew connectedDebugAndroidTest   # instrumented tests (device/emulator required)
./gradlew assembleRelease        # minified release APK
```

### Release signing
Create a keystore once and keep it safe (losing it means you can't ship updates):

```bash
keytool -genkeypair -v -keystore my-money-release.jks -alias mymoney -keyalg RSA -keysize 4096 -validity 10000
```

Then add `keystore.properties` in the project root (it's git-ignored):

```properties
storeFile=my-money-release.jks
storePassword=...
keyAlias=mymoney
keyPassword=...
```

`./gradlew assembleRelease` then produces a signed, R8-minified, resource-shrunk APK. Without the file,
the release APK is built unsigned.

## Google Cloud / Firebase configuration

Google Sign-In and Drive backup need **your own** Google Cloud/Firebase project. Nothing secret is
compiled into the APK: Android apps use public OAuth client IDs that Google binds to your package name
and signing-certificate fingerprints. **Never** put a client secret, service-account key or private key
in this project.

### 1. Signing certificate fingerprints
Google only accepts sign-in/authorization requests from APKs whose SHA fingerprints you registered.

```bash
# Debug builds (application id com.mymoney.app.debug)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
# Release builds (application id com.mymoney.app) — your upload/release keystore
keytool -list -v -keystore my-money-release.jks -alias mymoney
```
Copy both the **SHA-1** and **SHA-256** values. If you publish with Google Play App Signing, also add the
*App signing key* fingerprints from Play Console → Setup → App integrity.

### 2. Firebase project + Authentication
1. Create a project at <https://console.firebase.google.com>.
2. Add an **Android app** for `com.mymoney.app` (release) and another for `com.mymoney.app.debug` (debug),
   entering the SHA-1/SHA-256 fingerprints from step 1 for each.
3. *Build → Authentication → Sign-in method*: enable **Google**. Firebase creates the OAuth clients,
   including a **Web client** ("Web client (auto created by Google Service)") whose ID is the
   `serverClientId` the app requests ID tokens for.
4. Download `google-services.json` and place it at `app/google-services.json` (it's git-ignored).
   The `com.google.gms.google-services` plugin is applied automatically only when that file exists.

Alternatively, skip the JSON file and put the identifiers in `local.properties` (also git-ignored):
```properties
mymoney.googleWebClientId=1234567890-abc.apps.googleusercontent.com   # OAuth *Web* client ID
mymoney.firebaseApiKey=AIza...                                          # Firebase Android API key
mymoney.firebaseAppId=1:1234567890:android:abcdef                       # Firebase app ID
mymoney.firebaseProjectId=my-money-12345
```
(also accepted as `-P` Gradle properties or `MYMONEY_GOOGLEWEBCLIENTID`-style environment variables).

### 3. Google Drive API and OAuth consent
In <https://console.cloud.google.com> for the same project:
1. *APIs & Services → Library*: enable **Google Drive API**.
2. *APIs & Services → OAuth consent screen*: set app name, support email, privacy policy URL, and add
   the scopes below. While the app is in *Testing* mode, add your Google accounts as test users.
3. *Credentials*: you should see Android OAuth clients for both package names (with your SHA-1s) and the
   Web client. Create any that are missing.

| Scope | Requested when | Purpose |
|---|---|---|
| `openid`, `email`, `profile` | "Continue with Google" (Credential Manager → Firebase Auth) | Verified name, email, photo |
| `https://www.googleapis.com/auth/drive.appdata` | Only when the user taps **Connect Google Drive** | Read/write *only* the app's hidden `appDataFolder` |

Sign-in never grants Drive access; Drive is a separate, explicit authorization through
`AuthorizationClient` (Google Identity Services). `drive.appdata` cannot see any of the user's other
Drive files. Check Google's current scope classification in the console before publishing.

### How the flows work
* **Sign-in:** Credential Manager `GetSignInWithGoogleOption` shows Google's account chooser → Google ID
  token → `FirebaseAuth.signInWithCredential` verifies it → the app stores only uid, name, email and photo
  URL in Room (`user_profile`). The ID token is not stored. Firebase keeps its own session, which works
  offline. Sign-out clears Firebase + Credential Manager state; **local financial data is never deleted**.
  Switching to another account resets the Drive connection (grants are per account).
* **Drive authorization:** `AuthorizationClient.authorize(drive.appdata)`; if Google needs consent the
  app launches the returned `PendingIntent`. Access tokens are kept in memory for one operation only and
  never written to disk; Play services caches the grant so background backups can get a fresh token
  silently. A 401 clears the token (`clearToken`) and retries once; if user interaction is needed the
  backup stops, posts "Google Drive authorization expired" and the app shows a reconnect banner.
  "Revoke access" calls `revokeAccess` for the Drive scope and signs out.

## Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin 2.0, Jetpack Compose, Material 3 |
| Architecture | MVVM + clean layering (UI → ViewModel → Repository → DAO/Room) |
| Database | Room (SQLite) with exported schemas and explicit migrations |
| DI | Hilt (incl. HiltWorker) |
| Navigation | Navigation Compose |
| Background | WorkManager (reminders, snooze checks, scheduled & manual backups) — no AlarmManager, no services |
| Settings | Room `settings` / preference tables (backed up) + DataStore (security state & sealed backup key, never backed up) |
| Widgets | Jetpack Glance 1.1 (7 widgets, responsive sizes, Material 3 purple theme, light/dark) |
| Google | Credential Manager + Google ID, Firebase Authentication, Play Services `AuthorizationClient` |
| Cloud | Google Drive REST v3 (`appDataFolder`) via Retrofit + OkHttp |
| Crypto | AES-256-GCM, Argon2id (BouncyCastle) for recovery passphrases, Android Keystore |
| Charts | Custom Compose Canvas charts (donut, grouped bar, line) — interactive, no extra dependency |
| Security | PBKDF2 PIN hash sealed with an Android Keystore AES-GCM key, BiometricPrompt |
| Min / target SDK | 26 (Android 8.0) / 35 |

## Project layout

```
app/src/main/java/com/mymoney/app/
├── core/            Money (paise math & INR formatting), dates, periods, FinanceException
├── data/db/         Room entities, DAOs, query POJOs, AppDatabase, migrations, seed data
├── data/repo/       Repositories – all business rules and multi-table Room transactions
├── domain/          Pure rules: TransactionRules, ScheduleMath, Reports aggregation
├── security/        PinManager, KeystoreSecretBox, AppLockManager
├── auth/            Google Sign-In gateway (Credential Manager → Firebase Auth)
├── backup/          Backup format v2 (Argon2id + AES-256-GCM), key store, BackupManager (replace / merge /
│                    validate / undo), BackupMerger, CSV & PDF exporters
├── cloud/           Drive authorization, Drive REST client, CloudBackupService, BackupWorker
├── notifications/   Notifier, NotificationDispatcher (prefs, quiet hours, privacy, history), ReminderEngine
│                    + ReminderWorker, NotificationActionReceiver ("Remind later" / "Dismiss")
├── widgets/         Glance widgets, WidgetRepository (Room snapshots), WidgetUpdater (change-driven refresh)
├── di/              Hilt modules
└── ui/              Compose screens + ViewModels per feature, theme, components, navigation
```

## Financial model (how balances stay correct)

* **Money is stored as `Long` minor units (paise).** No `Float`/`Double` is used for money anywhere.
* **Balances are derived, not stored.** `balance = opening + Σ effects of the account's ledger entries`.
  Editing or deleting any transaction therefore reverses its old effect and applies the new one
  automatically and atomically — balances can't drift, even if the app is killed mid-operation
  (every multi-table change runs in a single Room transaction).
* Credit cards store the opening outstanding as a negative opening balance, so `outstanding = −balance`.

| Action | Effect |
|---|---|
| Expense from bank/cash | account − amount, counted as expense |
| Income to bank/cash | account + amount, counted as income |
| Purchase on credit card | card outstanding + amount, counted as expense (once) |
| Card bill payment (transfer bank → card) | bank − amount, outstanding − amount, **not** an expense |
| Transfer A → B | A − amount, B + amount, never income/expense |
| Lend ₹X from an account | account − X, receivable + X, not an expense |
| Repayment received | account + amount, receivable − amount |
| Borrow / repay borrowed | mirror of the above |
| Savings goal contribution | earmark only – no balance change (no double counting); earmarks can't exceed a linked account's balance |

**Available balance = bank + cash.** Unused credit limits are never counted as money; card outstanding is
shown separately.

Rules enforced: amounts > 0 with ≤ 2 decimals; transfers need two different accounts in the same
currency; a credit card can't be a transfer source; categories must match the transaction type; archived
accounts/categories can't receive new entries; loan ledger entries can only be changed from *Money with
People*; repayments can't exceed the outstanding amount.

**Scheduled payments** never deduct money automatically. *Mark as paid* creates the linked transaction and
an installment record in one transaction; a unique `(scheduleId, dueDate)` index makes paying the same
installment twice impossible. Deleting a paid installment's transaction re-opens that installment.

**Accounts with history can't be deleted** (foreign keys use `RESTRICT`); archive them instead.
Opening-balance edits and "Fix balance" reconciliations are written to an audit trail.

## Google Drive backup & restore

**Settings → Backup & Restore** shows the connected account, Drive status, last successful backup and its
size, automatic backup (Manual / Daily / Weekly / Monthly, Wi-Fi only, how many to keep), **Backup Now**,
**Restore Backup**, **Backup History** and **Disconnect Google Drive**.

Backup: check authorization → consistent snapshot (one Room transaction) → versioned JSON → encrypt on the
device → upload to `appDataFolder` (multipart) → verify Drive's size and MD5 against the local bytes
(mismatch deletes the upload) → record metadata → prune old backups. Background runs use WorkManager
with network + battery constraints and exponential backoff; Android decides the exact time.

Encryption (format v2): `MMBK | 2 | Argon2id | m,t,p | salt | iv | AES-256-GCM(json)`. The whole header is
authenticated data, so parameters can't be tampered with, and absurd parameters are refused. The key is
derived from the user's **recovery passphrase** (Argon2id, 19 MiB, t=2) and stored on the phone only
sealed by a non-exportable Android Keystore key so scheduled backups can run; neither passphrase nor key
is ever uploaded. Another phone needs only the passphrase. **If the passphrase is lost, the backups can't
be decrypted** — the app says so and requires an explicit acknowledgement. Files from v1.0 (PBKDF2) still
restore.

Included: accounts (incl. card metadata), cash wallets, transactions, categories, schedules and
installments, lending/borrowing and repayments, budgets, goals and contributions, app/notification/widget
preferences. Excluded: PIN, biometric settings, recovery code, Google identity and tokens, the backup key,
backup metadata, notification history, caches.

Restore: list → download (MD5 check) → passphrase → GCM authenticity check & decrypt → schema/version and
referential-integrity validation → preview (backup vs. what's on the phone) → choose **Merge**
(adds only records not already present, matched on natural keys with id remapping; idempotent) or
**Replace** (type REPLACE to confirm) → one Room transaction with a `PRAGMA foreign_key_check`
(any problem rolls everything back) → balances are recomputed from the ledger → reminders and widgets
refresh → results. A Keystore-sealed safety copy of the previous data is kept so **Undo last restore**
is possible.

## Widgets

Total Balance (S/M, eye toggle), Income & Expenses (M/L), Quick Actions (S/M: expense, income, transfer,
lending), Upcoming Payments (M/L, overdue flags, tap an item → its details), Money with People (M/L),
Budget Tracker (M/L, overspending warnings), Credit Card Summary (M/L; credit limits are never counted
as cash). Widgets read Room directly, so they work without opening the app; taps open the right screen
behind the app lock. They refresh when Room reports changes to relevant tables (debounced), after
restores and privacy changes, and on the periodic reminder work (date roll-over) — `updatePeriodMillis`
is 0 and nothing polls. **Settings → Widgets** has live previews rendered from the real widget layouts,
one-tap "Add to home screen" (on launchers that support pinning) and **widget privacy mode**.

## Notifications

Payments (EMI, SIP, card bill — from schedules or a card's due day — electricity, subscriptions, rent),
lending/borrowing (due, overdue, money expected, repayment recorded), budgets (80/90/100 % and exceeded),
savings goals (25/50/75 %, completed, target date within 7 days) and backup (success, failure, restore
completed, Drive authorization expired). Reminder lead time is per schedule (on the due date … 30 days)
and a default for loans/cards (0/1/3/7/custom). Each reminder is posted once (dedupe table); "Remind
later" snoozes it (re-posted once afterwards via a deferrable WorkManager check); **Dismiss never marks
anything paid**; **Mark as paid** locks the app so the PIN/biometric is required, then opens the
confirmation dialog. Quiet hours defer reminders and silence alerts. Amounts are hidden on the lock
screen by default. POST_NOTIFICATIONS is requested on Android 13+ when enabling notifications; if it's
denied, alerts still appear in the in-app **Notification Center** (filters, mark read / all read,
delete, open related record; history kept ~90 days).

## Security & privacy

* PIN (4 or 6 digits) → PBKDF2-HMAC-SHA256 (120k iterations, random salt) → sealed with a non-exportable
  AES-256-GCM key in the Android Keystore. Weak PINs (1111, 1234…) are rejected.
* 5 wrong attempts → escalating lockout (30 s, 60 s, 2 min…).
* **Forgot PIN:** a one-time recovery code is shown at setup (only its hash is stored). The PIN can be reset
  with that code or with an enrolled biometric. As a last resort, all data can be erased.
* Auto-lock after the app is backgrounded (immediately / 30 s / 1 / 5 / 15 min) and after foreground
  inactivity. The app stays composed under the lock screen, so navigation state is preserved.
* Android cloud backup and device-transfer are disabled for app data. Backups are explicit and encrypted
  (see *Google Drive backup & restore*); file backups use the same v2 format with your password.
* CSV export neutralises spreadsheet formula injection.
* Permissions: `INTERNET` + `ACCESS_NETWORK_STATE` (used only by optional Google Sign-In and Drive backup),
  `POST_NOTIFICATIONS` (asked when you enable notifications on Android 13+) and `USE_BIOMETRIC`. No contacts,
  storage or location permissions — files go through the Storage Access Framework picker.
* No Google password, OAuth access token or ID token is stored by the app; Drive access tokens live in memory
  for a single operation. Routes that open "Mark as paid" always require the PIN/biometric, even when sent
  by another app.

## Features by screen

* **Splash & onboarding** – intro pager, name & currency, create/confirm PIN, biometric opt-in, recovery code.
* **Lock screen** – PIN pad, fingerprint/face, lockout timer, forgot-PIN reset.
* **Home** – available balance (hideable), bank / cash / card due, monthly income, expenses and net savings
  with month switcher, money to receive / to pay (with overdue), quick actions, upcoming payments, 6-month
  income-vs-expense bar chart, category donut, recent transactions.
* **Transactions** – All / Income / Expense / Transfer tabs, search (merchant, note, tag, category, account,
  person), filters (period incl. custom range, account, category, min/max amount), sort (newest, oldest,
  highest, lowest), grouped by day; add / edit / view / delete with confirmation; optional "repeat" creates
  a schedule.
* **Accounts** – All / Bank / Card / Cash; add bank account, credit card (limit, statement & due day),
  cash wallet; edit, archive/restore, delete (only without history), per-account history, card
  utilisation, balance reconciliation with audit trail.
* **Scheduled payments** – EMI, SIP, electricity, internet, mobile, rent, insurance, credit card bill,
  subscriptions, custom; daily → yearly; upcoming / overdue / all / calendar views; mark paid (amount,
  account and date editable), skip, pause/resume, edit, delete, payment history, reminders.
* **Money with People** – I Gave / I Received / People; person-wise ledger; partial and full repayments
  (edit/delete), settle (record remaining or write off), reopen, due-date reminders, search.
* **Budgets** – monthly per-category limits, spent/remaining, progress colours, alerts at 80/90/100 %,
  history by month, copy last month.
* **Savings goals** – target, date, linked account, contributions/withdrawals, progress, monthly
  amount needed, completion, archive, history.
* **Reports** – Today / Yesterday / Last 7 days / This month / Last month / Custom + account & category
  filters; income vs expenses (bar), cash flow (line), category and income-source donuts, account-wise
  spending, card outstanding, budget utilisation, scheduled payment summary, lent/borrowed, savings
  progress; CSV and PDF export.
* **Login** – welcome page with Continue with Google / Continue Offline, then PIN + fingerprint setup;
  returning users unlock with PIN or fingerprint (shows the linked account or "Offline mode").
* **Settings** – profile, currency, theme; **Account** (Google account profile: photo, name, email, status,
  last backup, switch account, sign out, revoke access); **Cloud Backup** (Drive connection, Backup &
  Restore, history); **Widgets** (gallery, privacy mode); **Notifications** (categories, timing, quiet
  hours, lock-screen privacy, notification center); **Security** (PIN, fingerprint, auto-lock, hide
  balances, lock now); custom categories, file backup & CSV/PDF export, app info, erase all data.

## Tests

`./gradlew testDebugUnitTest` runs 122 tests on the JVM (Robolectric for Room/DataStore/Compose/Glance):

| Suite | Covers |
|---|---|
| `MoneyTest` | parsing, rounding safety, INR grouping, compact format |
| `TransactionRulesTest` | valid/invalid account combinations and amounts |
| `ScheduleMathTest` | month-end anchoring, all frequencies, overdue counts |
| `ReportsTest` | summaries exclude transfers/loans, breakdowns, series, CSV escaping |
| `FinanceRepositoryTest` | accounts, transactions, balances, cards, transfers, loans, schedules, reminders, budgets (incl. "exceeded"), goals, categories, filters |
| `PersistenceAndBackupTest` | data survives DB close/reopen (process restart), encrypted round-trip, failed restore is all-or-nothing, reset |
| `MigrationTest` | real v1 database (from the exported schema) migrates to v2 with Room schema validation; no destructive fallback |
| `BackupCryptoTest`, `BackupCryptoV2Test` | Argon2id header, cross-device restore with passphrase only, invalid passphrase, corrupted/tampered/truncated/future files, malicious KDF params, v1 compatibility |
| `RestoreAndKeysTest` | merge duplicate detection & id remapping, idempotent re-merge, replace + undo, corrupted/newer backups rejected without changes, preferences included / identity excluded, sealed passphrase key |
| `CloudBackupServiceTest` | upload/verify/metadata, restore on another device, 401 → clear token → retry, revoked grant → reauth flag, verification failure discards upload, preconditions, pruning, corrupted download, **WorkManager retry/failure/auth-expired** |
| `DriveApiTest` | multipart/related upload into appDataFolder, paging, download limits, 401/403/429/5xx/quota/network mapping (MockWebServer) |
| `NotificationSystemTest` | actions, lock-screen privacy, category/master switches, history when permission denied, quiet hours, snooze → single re-post, duplicate prevention, card-bill & goal reminders, milestones, repayments |
| `WidgetTest` | balance excludes credit limits, card/upcoming models, **rendering at small/medium/large**, privacy masking, refresh after transactions without polling |
| `PinManagerTest`, `AppLockManagerTest`, `ComponentsUiTest` | PIN/lockout/recovery, auto-lock, Compose components |

Instrumented tests (`./gradlew connectedDebugAndroidTest`, `app/src/androidTest`) cover the real Android
Keystore, `MigrationTestHelper` validation of v1 → v2 and on-device persistence.

Google Sign-In and live Drive calls need a configured Google project and a signed-in Google account on a
device, so they are covered by fakes and a mock Drive server in unit tests; verify them manually after
completing *Google Cloud / Firebase configuration*.

## Adding a database migration

1. Change the entities and bump `AppDatabase.VERSION`.
2. Build once – Room exports `app/schemas/com.mymoney.app.data.db.AppDatabase/<n>.json`.
3. Add a `Migration(n-1, n)` to `Migrations.ALL` and a `migrateAndValidate` test in `DeviceTests.kt`.

Destructive fallback is intentionally disabled so user data is never dropped silently.
