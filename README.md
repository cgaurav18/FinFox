# My Money – Personal Finance & Expense Tracker (Android)

A native Android app (Kotlin + Jetpack Compose) to track income, expenses, bank accounts, credit cards,
cash, transfers, scheduled payments (EMI / SIP / bills), money lent and borrowed, budgets, savings goals
and reports. It works **completely offline**: no backend, no Firebase, no account, and the app does not
request the `INTERNET` permission.

> My Money is a tracker. It never initiates real payments and never asks for bank passwords, full card
> numbers, CVVs or UPI PINs.

## Quick start

### Install the prebuilt APK
`dist/MyMoney-1.0.0-debug.apk` (application id `com.mymoney.app.debug`; also built to `app/build/outputs/apk/debug/app-debug.apk`).

```bash
adb install -r dist/MyMoney-1.0.0-debug.apk
```
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

## Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin 2.0, Jetpack Compose, Material 3 |
| Architecture | MVVM + clean layering (UI → ViewModel → Repository → DAO/Room) |
| Database | Room (SQLite) with exported schemas and explicit migrations |
| DI | Hilt (incl. HiltWorker) |
| Navigation | Navigation Compose |
| Background | WorkManager (reminders) |
| Settings | Room `settings` table (backed up) + DataStore (security state, never backed up) |
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
├── backup/          AES-256-GCM backup format, BackupManager, CSV & PDF exporters
├── notifications/   Notifier, ReminderEngine + ReminderWorker (WorkManager)
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

## Security & privacy

* PIN (4 or 6 digits) → PBKDF2-HMAC-SHA256 (120k iterations, random salt) → sealed with a non-exportable
  AES-256-GCM key in the Android Keystore. Weak PINs (1111, 1234…) are rejected.
* 5 wrong attempts → escalating lockout (30 s, 60 s, 2 min…).
* **Forgot PIN:** a one-time recovery code is shown at setup (only its hash is stored). The PIN can be reset
  with that code or with an enrolled biometric. As a last resort, all data can be erased.
* Auto-lock after the app is backgrounded (immediately / 30 s / 1 / 5 / 15 min) and after foreground
  inactivity. The app stays composed under the lock screen, so navigation state is preserved.
* Android cloud backup and device-transfer are disabled for app data. Backups are explicit and encrypted:
  `MMBK | version | salt | iv | AES-256-GCM(JSON)` with a PBKDF2 key from your password (never stored).
  Restore decrypts first, shows what the file contains, asks for confirmation, then replaces everything in
  one transaction (all-or-nothing).
* CSV export neutralises spreadsheet formula injection.
* Permissions: `POST_NOTIFICATIONS` (asked only when you enable reminders on Android 13+) and
  `USE_BIOMETRIC`. No internet, contacts, storage or location permissions — files are read/written through
  the Storage Access Framework picker.

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
* **Settings** – profile name, currency, light/dark/system theme, change PIN & new recovery code,
  biometric lock, auto-lock, lock now, reminders/budget alerts and reminder time, custom categories
  (add/edit/archive/delete), encrypted backup & restore, CSV/PDF export, app info, erase all data.

## Tests

`./gradlew testDebugUnitTest` runs 64 tests on the JVM (Robolectric for Room/DataStore/Compose):

| Suite | Covers |
|---|---|
| `MoneyTest` | parsing, rounding safety, INR grouping, compact format |
| `TransactionRulesTest` | valid/invalid account combinations and amounts |
| `ScheduleMathTest` | month-end anchoring, all frequencies, overdue counts |
| `ReportsTest` | summaries exclude transfers/loans, breakdowns, series, CSV escaping |
| `FinanceRepositoryTest` | account CRUD & audit, transaction CRUD, balance recalculation, card purchase & bill, transfers, loans with partial repayments/edits/deletes/write-off, schedule mark-paid/skip/duplicate prevention/reopen, card-bill schedules, recurring option, reminders, budgets & alerts, savings earmarks, categories, date/search filters |
| `PersistenceAndBackupTest` | data survives DB close/reopen, encrypted backup round-trip, failed restore is all-or-nothing, reset |
| `PinManagerTest` | PIN set/verify/change, no plaintext at rest, lockout, recovery reset, biometric/auto-lock prefs |
| `AppLockManagerTest` | background timeout, picker suppression, inactivity lock |
| `ComponentsUiTest` | PIN pad, amount field validation, confirm dialog, tabs, interactive charts |

Instrumented tests (`app/src/androidTest`) cover the real Android Keystore, schema/migration validation
and on-device persistence.

## Adding a database migration

1. Change the entities and bump `AppDatabase.VERSION`.
2. Build once – Room exports `app/schemas/com.mymoney.app.data.db.AppDatabase/<n>.json`.
3. Add a `Migration(n-1, n)` to `Migrations.ALL` and a `migrateAndValidate` test in `DeviceTests.kt`.

Destructive fallback is intentionally disabled so user data is never dropped silently.
