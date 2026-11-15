# FinFox – Personal Finance & Expense Tracker (Android)

<img src="app/src/main/res/drawable-nodpi/finfox_logo.png" width="96" alt="FinFox fox mascot"/>

A native Android app (Kotlin + Jetpack Compose) to track income, expenses, bank accounts, credit cards,
cash, transfers, scheduled payments (EMI / SIP / bills), money lent and borrowed, budgets, savings goals
and reports. It is **offline-first**: every financial record lives in the on-device Room database and the
app is fully usable without an account or a connection. Version 1.1 added
encrypted Google Drive backup & restore, seven home screen widgets, smart notifications and a
notification center.

> FinFox is a tracker. It never initiates real payments and never asks for bank passwords, full card
> numbers, CVVs or UPI PINs.

## Quick start

### Install the prebuilt APK
`dist/FinFox-1.2.0-debug.apk` (application id `com.mymoney.app.debug`; also built to `app/build/outputs/apk/debug/app-debug.apk`).

```bash
adb install -r dist/FinFox-1.2.0-debug.apk
```

The app needs no account;
Drive backup is optional; everything else works offline.
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

> **Renamed in 1.2:** the app is now **FinFox**. The technical application id stays `com.mymoney.app`
> (`.debug` for debug builds) so existing installs upgrade in place with all their data, PIN, Google
> sign-in and Drive backups intact. Old backup files (`MMBK` format, `mymoney-backup-*` in Drive) still restore.

## Design system (FinFox theme)

All colours live in `ui/theme/Color.kt` (`FinFoxPalette`) and are applied through `ui/theme/FinFoxTheme.kt`
(Material 3 light/dark `ColorScheme` + `FinFoxTheme.colors` tokens). UI code doesn't use raw hex values;
the only literals outside the theme are user-selectable data colours (account/category palette).

| Token | Light | Dark |
|---|---|---|
| Background / card | `#F8F6FC` / `#FFFFFF` | `#170B27` / `#2B1645` |
| Text / secondary text | `#25083F` / `#62576F` | `#FFFFFF` / `#C8B7D9` |
| Primary button | `#5B0FA6` | `#7C20D9` |
| Selected navigation | `#7C20D9` | `#FF7417` |
| Accent | `#FF7417` | `#FFB21C` |
| Border | `#E9E1F0` | `#49305F` |
| Brand gradient | `#25083F → #5B0FA6 → #7C20D9 → #FF7417` (balance card, splash, login header) | same |

Status colours: income `#16A34A`, expense `#DC2626`, transfer `#2563EB`, EMI/upcoming `#D97706`,
savings `#0D9488`, overdue `#B91C1C`; always shown together with an icon or label. In dark mode, amounts
drawn as *text* use lightened tints of the same hues (e.g. income `#4ADE80`) so they meet WCAG AA on the
violet cards; icons, bars and chart fills keep the canonical colours. Dark-mode "primary" text/links use a
light violet (`#C9A6F7`) for the same reason, while filled buttons use Royal Violet.

Accessibility: all body/secondary text pairs are ≥ 4.5:1 (most ≥ 6:1) — enforced by `FinFoxThemeTest`.
The specified light-mode green and amber reach 3.2–3.3:1 on white, which meets the 3:1 non-text
minimum, so they're never the only cue. White text never sits on pure Fox Orange (2.7:1): the
gradient keeps orange to the far corner and the balance breakdown has a plum scrim.

Typography: **Inter** (bundled variable font, `res/font/inter.ttf`, SIL OFL — see `licenses/Inter-OFL.txt`),
balance 32–34 sp, headings 22 sp, card titles 17 sp, body 14–16 sp, captions 12 sp; all in `sp`, so
Android font scaling applies. Shapes: buttons 14 dp, inputs 12 dp, cards 20 dp, hero cards 24 dp,
8 dp spacing grid, 16 dp screen padding, 48 dp minimum touch targets. On tablets content is centred at
≤ 720 dp wide.

Animations: splash mascot entrance, navigation transitions, button press scale, dashboard balance
count-up, chart load-in, animated success tick on saves, goal progress bars. All of them turn off
when Android's animator duration scale is 0 ("Remove animations").

**Settings → Appearance**: Light Mode / Dark Mode / Follow System, stored in DataStore (`appearance`),
applied immediately and kept after restart (the previous value stored in Room is migrated automatically).

Brand assets: the fox mascot is `drawable-nodpi/finfox_logo.png` (+ `finfox_logo_small.png` for widgets);
the adaptive launcher icon uses it as the foreground over lavender, with a monochrome layer for Android 13
themed icons; the system splash shows it on Deep Plum.

## Google Drive backup setup (Google Cloud)

FinFox has **no sign-in**. Google is used only for the optional Drive backup, and that only needs
Android OAuth clients — no Firebase, no web client, and no secrets in the APK.

1. In Google Cloud (project `ardent-gearbox-450403-n9`), go to **APIs & Services → Library** and
   enable **Google Drive API**.
2. Under **OAuth consent screen**, set the app name to FinFox, add the scope `.../auth/drive.appdata`,
   and, while the app is in Testing mode, add your Gmail address as a test user.
3. Under **Credentials → Create OAuth client ID → Android**, create one client per signing key:

| Build | Package name | SHA-1 |
|---|---|---|
| Release (`dist/FinFox-*-release.apk`) | `com.mymoney.app` | `95:78:41:96:EE:34:3D:A5:CD:2F:62:CF:DE:A9:E9:66:FF:3F:DC:3F` |
| Debug (built on this Mac) | `com.mymoney.app.debug` | `94:7D:1C:E3:5F:AA:79:E8:87:C8:CD:C1:DF:C1:54:5C:E0:9B:54:EB` |

If you publish through Google Play, also add Play's *App signing key* SHA-1. Google matches clients by
package name and SHA-1 at runtime, so nothing has to be pasted into the project.

In the app: **Settings → Connect Google Drive** opens Google's own account picker and consent screen,
requesting only `drive.appdata` (the hidden app folder) and `email` (to remember which account holds
the backups so scheduled backups can re-authorize silently). Access tokens are never stored.

**Release signing key:** `finfox-release.jks` + `keystore.properties` (both git-ignored). Back them
up — without them you can never publish an update.

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
| Google | Play Services `AuthorizationClient` (Drive only, no sign-in) |
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
* Permissions: `INTERNET` + `ACCESS_NETWORK_STATE` (used only by the optional Google Drive backup),
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
* **Unlock** – onboarding sets a PIN + fingerprint (no account); returning users unlock with PIN or fingerprint.
* **Settings** – profile, currency, theme; **Account** (Google account profile: photo, name, email, status,
  last backup, switch account, sign out, revoke access); **Cloud Backup** (Drive connection, Backup &
  Restore, history); **Widgets** (gallery, privacy mode); **Notifications** (categories, timing, quiet
  hours, lock-screen privacy, notification center); **Security** (PIN, fingerprint, auto-lock, hide
  balances, lock now); custom categories, file backup & CSV/PDF export, app info, erase all data.

## Tests

`./gradlew testDebugUnitTest` runs 126 tests on the JVM (Robolectric for Room/DataStore/Compose/Glance):

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
| `FinFoxThemeTest` | spec colours, WCAG contrast for text/buttons/nav/status colours in both themes, theme persistence across restart and migration of the old setting |
| `PinManagerTest`, `AppLockManagerTest`, `ComponentsUiTest` | PIN/lockout/recovery, auto-lock, Compose components |

Instrumented tests (`./gradlew connectedDebugAndroidTest`, `app/src/androidTest`) cover the real Android
Keystore, `MigrationTestHelper` validation of v1 → v2 and on-device persistence.

Live Drive calls need the Android OAuth clients above and a Google account on a
device, so they are covered by fakes and a mock Drive server in unit tests; verify them manually after
completing *Google Drive backup setup*.

## Adding a database migration

1. Change the entities and bump `AppDatabase.VERSION`.
2. Build once – Room exports `app/schemas/com.mymoney.app.data.db.AppDatabase/<n>.json`.
3. Add a `Migration(n-1, n)` to `Migrations.ALL` and a `migrateAndValidate` test in `DeviceTests.kt`.

Destructive fallback is intentionally disabled so user data is never dropped silently.
