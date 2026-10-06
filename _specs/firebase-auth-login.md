# Feature Spec: Firebase Authentication Login

## Description

Add a **login screen** to the app backed by **Firebase Authentication (Email/Password provider)**. Every installed copy of the app must be signed in with a Firebase user before it can reach the Main Menu or any feature screen.

The primary purpose is **remote access control across store installations**. The owner will install the app on devices at other stores. Each store (or staff member) is given its own Firebase user, created by the owner in the Firebase Console. If the owner later **deletes or disables** that user in the Firebase Console, the corresponding device must **lose access to the app** on its next launch or next access check, and must be returned to the login screen.

Key points:

- Accounts are created, disabled, and deleted **only in the Firebase Console**. The app has **no self-registration**.
- Login is required **once per device**; the session persists across app restarts until the user is signed out, deleted, or disabled.
- The app **re-verifies the account against Firebase** on every launch and on every return to the foreground (when online), so a deleted or disabled account is locked out promptly rather than after the cached session happens to expire.
- When the device is **offline**, the cached session is honoured for a **24-hour grace period** counted from the last successful online verification. After that the app blocks until it can verify again.
- **Accounts are provisioned one per branch** (store). Staff at a branch share that branch's account.
- **Firestore Security Rules must be tightened to require an authenticated user.** Without this, deleting a user only removes the login screen gate; the data itself would remain reachable. The rules change is part of this feature, but it is **deployed last**, after the owner has updated every device and the web app.
- The existing **Program Settings password gate** (the kebab menu password stored in `app_config/settings_password`) is **unchanged**. It continues to protect settings; login protects the whole app.
- Store location, user name, and all other Program Settings remain per-device SharedPreferences values. Login does not replace or move them.

---

## Feature 1: Login Screen

### User Story

**As the store owner**, I want each installed copy of the app to require a Firebase email and password before it can be used, so that only devices I have provisioned with an account can access the business data.

**As branch staff**, I want to sign in once on my device and not be asked again on every launch, so day-to-day use is not slowed down.

### UI/UX Flow

1. The app launches to the **Splash** screen as today.
2. After the splash delay, the app checks whether a Firebase user is already signed in on this device:
   - **No signed-in user:** the app opens the **Login** screen.
   - **Signed-in user:** the app proceeds to the account re-verification step (Feature 2) and then to the **Main Menu**.
3. The **Login** screen uses the TechCity branding (logo, "TechCity Suite" title, blue palette) and contains:
   - **Email** field (email keyboard, single line).
   - **Password** field (masked, with a show/hide toggle).
   - **Sign In** button (primary, full width).
   - An inline **error message** area below the fields (hidden until needed).
   - A **progress indicator** shown while signing in; fields and button are disabled during the request.
   - App version text at the bottom, matching the Main Menu.
4. Tapping **Sign In**:
   - If email or password is empty, show "Please enter your email and password" inline and do not call Firebase.
   - Otherwise sign in with Firebase Email/Password. On success, open the **Main Menu** and finish the Login screen so Back does not return to it.
   - On failure, re-enable the form and show a user-friendly inline message (see Edge Cases). The password field is cleared and focused.
5. Pressing **Enter/Done** on the password field triggers Sign In.
6. The device **Back** button on the Login screen exits the app (there is no screen to go back to).
7. The Login screen is **not registered as the launcher**; Splash remains the launcher and decides where to go.

### Business Rules

- Only the **Email/Password** provider is supported. No Google, phone, or anonymous sign-in.
- There is **no "Create account"** option anywhere in the app. Accounts are provisioned in the Firebase Console only.
- A successful sign-in persists on the device (Firebase's default persistent session). The app does not store the password itself anywhere.
- The Login screen cannot be bypassed by any Intent path: every feature Activity is reached only through the Main Menu, and the Main Menu refuses to show without a verified signed-in user (Feature 2).

---

## Feature 2: Access Re-verification (Lockout on Deleted or Disabled User)

This is the core of the feature. Firebase keeps a signed-in session cached on the device and its access token is valid for roughly an hour after issue. Deleting the user in the console does **not** by itself push the device out immediately, so the app must **actively re-check** the account.

### User Story

**As the store owner**, when I delete or disable a store's Firebase user in the console, I want that store's device to be locked out the next time the app is opened or brought to the foreground, and to be sent back to the login screen with a clear message.

### UI/UX Flow

1. On **every cold launch** (from Splash), and on a **return to the Main Menu when the last successful verification is one hour or more old**, the app performs an **online re-verification** of the account with Firebase (reloading the user record and forcing a fresh token). Returns to the Main Menu within one hour of the last successful verification skip the check, so backing out of a feature screen does not cause a network round-trip every time.
2. While verification runs on launch, the Splash screen stays visible (no extra loading screen) until verification finishes or **10 seconds** pass, whichever comes first; on timeout the offline rules in step 5 apply. On a Main Menu resume, verification runs silently in the background; the menu remains usable during the check.
3. **Account still valid:** continue as normal.
4. **Account deleted, disabled, or token refresh rejected:**
   - The app signs the user out locally.
   - All in-memory global state is cleared: `LedgerManager` ledgers and the `AppSettingsManager` cache. Per-device Program Settings in SharedPreferences (store location, user name, feature toggles, account names) are **kept**, so re-provisioning the same device later does not require reconfiguration.
   - The app returns to the **Login** screen, clearing the back stack so the Main Menu and any open feature screens are finished.
   - The Login screen shows an inline notice: **"Your access has been revoked. Please contact the administrator."**
5. **Verification could not complete because the device is offline** (no network, or Firebase unreachable):
   - If the last successful online verification (or the original sign-in) was **less than 24 hours ago**, the app **allows the cached session** and continues to the Main Menu. The next online check will enforce revocation.
   - If it was **24 hours ago or more**, the app does **not** sign the user out but shows a blocking **"Verification required"** state on the Login screen: the email of the signed-in account is shown read-only, the password field is hidden, and the message reads **"This device has been offline for more than 24 hours. Connect to the internet to continue."** with a **Retry** button. Retry re-runs verification; on success the app continues to the Main Menu without asking for the password. If verification instead reports the account as deleted or disabled, the revoked-access flow in step 4 applies.
6. **Firestore permission denied mid-session** (central handling only): when the user was deleted while the app was already open and the token has since expired, Firestore starts refusing reads and writes with a permission-denied error (this only occurs once the Feature 4 rules are deployed). Only **Splash** and the **Main Menu** react to it: a permission-denied error from the Main Menu's own checks or from its device transaction real-time listener is treated the same as a failed verification: sign out, clear state, return to Login with the revoked-access notice. **Feature screens** (Device Transactions, Ledger, End of Day, and so on) are **not changed**; they keep showing their existing generic error toast, no data is read or written because Firestore refuses it, and the lockout happens when the user returns to the Main Menu or relaunches the app.

### Business Rules

- Re-verification runs at most once per cold launch and at most once per hour of active use (Main Menu resumes inside that hour skip it); it does not poll on a timer.
- Every successful online verification, and every successful sign-in, records the current time on the device as the **last verified time**. The 24-hour grace period is measured from this value.
- The grace period is checked on cold launch and on every Main Menu resume (the 24-hour check is a local clock comparison and is not subject to the one-hour throttle), so a device that stays in the foreground offline is still blocked once 24 hours have passed.
- Worst-case lockout delay for a device that stays online and in active use is about **one hour**: either the hourly re-verification detects the deleted account, or the cached token expires and Firestore itself starts refusing requests, whichever comes first.
- A disabled user and a deleted user are treated identically by the app: both are locked out.
- A user whose **password was changed** in the console also fails token refresh and is sent to the Login screen; signing in with the new password restores access.
- The device transaction real-time listener on the Main Menu is only started after a verified signed-in user exists, and is removed on sign-out.
- Feature Activities do not perform their own verification and their error handling is not modified; they rely on the Main Menu gate and the central permission-denied handling described above.

---

## Feature 3: Sign Out

### User Story

**As the store owner**, when I move a device to a different store or hand it to a different account, I want to sign the current account out from within the app so I can sign in with another one.

### UI/UX Flow

1. **Program Settings** gains a **"Account"** section at the bottom of the screen (below the existing feature toggles and password section) showing:
   - **Signed in as:** the current user's email (read-only).
   - A **Sign Out** button (outlined, red/warning style).
2. Tapping **Sign Out** shows a confirmation dialog: "Sign out of TechCity Suite on this device? You will need the account email and password to sign in again." with **Cancel** / **Sign Out**.
3. Confirming signs out, clears in-memory global state (same as Feature 2), finishes Program Settings and the Main Menu, and opens the **Login** screen.
4. Because Program Settings is already behind the settings password, sign-out is effectively password-protected and cannot be triggered by ordinary staff.

### Business Rules

- Sign Out is only available from Program Settings. It is not on the Main Menu.
- Sign Out does not delete or modify anything in Firestore or Firebase Authentication; it only ends the device session.
- Per-device Program Settings are kept after sign-out (same rule as revocation).

---

## Feature 4: Firestore Security Rules Require Authentication

### User Story

**As the store owner**, I want the database itself to reject requests from devices that are not signed in, so that deleting a user truly cuts off access to the data and not just to the app's screens.

### Flow

1. The Firestore Security Rules for the project are updated so that **every read and write to every collection requires an authenticated user** (`request.auth` present). No collection remains publicly readable or writable.
2. **Rollout order** (owner-driven): (a) build and install the login-enabled app on every device; (b) update the web app to sign in with Firebase Authentication; (c) only then deploy the authenticated-only rules. Until step (c), old installs keep working unchanged. Nothing in the app depends on the rules being deployed, so steps (a) and (c) can be days apart.
3. Existing per-device documents in `app_settings/{deviceId}` and the `app_config/settings_password` document continue to be accessed as today, but only by authenticated users.

### Business Rules

- Rules are **authenticated-or-nothing** in this feature. No per-user or per-store data restrictions are introduced; every authenticated user can access every collection exactly as today. Per-store separation continues to be enforced by the app's store location filtering, not by rules.
- The rules text is delivered as a file in this repository so the change is versioned; the owner deploys it through the Firebase Console (or Firebase CLI) as a manual step, not as part of the app build.
- The web app that also uses this Firestore project **does not currently authenticate**. The owner will update it separately before deploying the rules. Changes to the web app are **out of scope** for this task.

---

## Feature 5: Behaviour of Older Installs (Backward Compatibility)

This section answers the owner's question: *"What happens to older apps? They do not require authentication, right, since it is the app that checks the authentication?"*

**Short answer: yes, older installs keep working without login until the Firestore Security Rules from Feature 4 are deployed. Deploying those rules is the only thing that can affect an old install.**

There are two independent layers, and only one of them is visible to an old install:

| Layer | Where it runs | Affects new (login) app | Affects old app | Affects web app |
|---|---|---|---|---|
| App-side check (login screen, hourly re-verification, 24-hour grace) | Inside the new app's code | Yes | No, the old code has no such check | No |
| Firestore Security Rules requiring authentication (Feature 4) | Inside Firebase, on the server | Yes | Yes, every read and write is refused | Yes, unless updated to sign in |

**Before the rules are deployed** (the state right after this feature ships):

- New installs show the Login screen and are locked out when their Firebase user is deleted or disabled.
- Old installs behave exactly as today: no login, full access to Firestore. Deleting a Firebase user has no effect on them, because they never present a user to Firestore.
- Both versions read and write the same collections and can run side by side indefinitely.

**After the rules are deployed:**

- New installs are unaffected (they are already signed in).
- Old installs fail on every Firestore call with a permission-denied error. They show their generic error toasts and cannot load or save anything. There is no message telling the user to update; the app simply stops working until it is replaced with the login version.
- The web app fails the same way unless it has been updated to sign in.

**What this means for the lockout guarantee:** as long as the rules are **not** deployed, the lockout is enforced only by the app's own code. Anyone who still has the old APK (or who sideloads it) can access all data without an account. If the owner controls every device, this may be acceptable; if not, the rules are what make the lockout real. See Open Question (Round 4).

---

## Data Model

### Firebase Authentication

- Provider: **Email/Password**, enabled in the Firebase Console.
- Users: created manually in the console by the owner, **one per branch** (e.g. `kapatagan@techcity.local`). Password resets are console-only; the app has no "Forgot password" link.
- Nothing about the user is stored in Firestore by this feature.

### Firestore

- **No new collections and no changes to existing document shapes.**
- **Security Rules changed** to require an authenticated request for all reads and writes across all collections (Feature 4).

### Local device state

- **Firebase Auth session** is persisted by the Firebase SDK itself (no app-managed storage).
- **One new SharedPreferences value:** the **last verified time** (epoch milliseconds of the last successful sign-in or online verification), used for the 24-hour grace period. It is cleared on sign-out and on revocation.
- All existing `TechCitySettings` and `TechCityDevicePrefs` values are untouched by login, sign-out, and revocation.

### Screens

- **New:** Login screen (Activity + layout), registered in the manifest as non-exported. It has two states: **Sign In** (email + password) and **Verification required** (read-only email, message, Retry).
- **Modified:** Splash (routing decision), Main Menu (verification on resume, listener gating), Program Settings (Account section with Sign Out).
- **Repository file:** Firestore Security Rules text for the owner to deploy manually.

## Business Rules (Summary)

1. The app cannot be used without a signed-in Firebase Email/Password user.
2. Accounts are managed only in the Firebase Console; the app has no registration or password-change UI.
3. A signed-in session persists across launches until sign-out, deletion, disablement, or password change.
4. On every cold launch, and on Main Menu resume when the last verification is an hour or more old, the account is re-verified online; a deleted, disabled, or password-changed user is signed out and returned to Login with a revoked-access notice. The splash waits at most 10 seconds for the launch check.
5. When offline, the cached session is allowed for 24 hours from the last successful sign-in or verification; after that the app blocks with a "Verification required" state until it can verify online. The session is not signed out by the block.
6. A Firestore permission-denied error seen by Splash or the Main Menu is treated as revoked access; feature screens are not modified and keep their existing error toasts.
7. Sign Out lives in Program Settings (behind the existing settings password) and clears only the session, the last verified time, and in-memory state, not per-device settings.
8. Firestore Security Rules require authentication for all collections; no per-user data rules are added. Rules are deployed by the owner last, after all devices and the web app are updated.
9. The existing settings password gate is unchanged and independent of login.
10. Accounts are one per branch, created and reset in the Firebase Console only. The signed-in email is shown only in Program Settings. Login and store location stay independent.

## Edge Cases and Error Handling

- **Wrong email or password:** show "Incorrect email or password." Do not distinguish between an unknown email and a wrong password.
- **Malformed email:** show "Please enter a valid email address."
- **Account disabled at sign-in time:** show "This account has been disabled. Please contact the administrator."
- **Too many failed attempts (Firebase throttling):** show "Too many attempts. Please try again later."
- **No network at sign-in:** show "No internet connection. Please check your connection and try again." Sign-in always requires network; there is no offline first sign-in.
- **Network lost mid-sign-in / timeout:** treat as the no-network case and re-enable the form.
- **Offline cold launch with a cached session, last verified under 24 hours ago:** proceed to the Main Menu; verification is retried on the next resume.
- **Offline cold launch with a cached session, last verified 24 hours or more ago:** show the "Verification required" state; Retry until online. No password is needed once verification succeeds.
- **Device clock moved backwards or forwards:** the grace period uses the device clock, so a clock change can lengthen or shorten it. Accepted; the online check still enforces revocation whenever a connection exists.
- **Verification succeeds but a later Firestore call is denied because the rules were deployed before this device was updated:** cannot happen if the rollout order in Feature 4 is followed; if it does, the device shows the revoked-access flow and signing in again with the same account resolves it once the app is updated.
- **User deleted while a feature screen is open:** the current screen keeps working until the cached token expires (up to about an hour), after which every Firestore call on that screen is refused (once rules are deployed). The screen shows its existing error toast; the lockout happens on the next return to the Main Menu or relaunch. Unsaved input on that screen is lost, which is acceptable.
- **Revocation during an in-flight transaction save:** the save fails with permission denied; the transaction is not written; the app locks out. No partial-write cleanup is attempted by this feature (Firestore batched/transactional writes already used by the app are atomic).
- **Store location not configured after fresh login:** unchanged behaviour; the Main Menu shows "No store location set" and location-scoped screens block as today. Login and store location are independent.
- **Device transaction listener error with permission denied:** treated as revoked access (Feature 2, step 6).
- **Process death / app killed while on Login screen:** relaunch goes through Splash and back to Login; no state to restore.
- **Screen rotation on the Login screen:** typed email is retained; an in-progress sign-in request is not duplicated.
- **Very first run after rollout on an existing installed device:** the user sees the Login screen once; after signing in, all previous per-device settings are still in place.

## Resolved Decisions

1. **Offline policy:** 24-hour grace period from the last successful verification; after that the app blocks until it can verify online.
2. **Account granularity:** one account per branch initially. How accounts are provisioned is the owner's call and does not affect the app.
3. **Forgot password:** none. Account creation and password resets are Firebase Console only.
4. **Web app:** does not currently authenticate. The owner will update it separately before deploying the rules; out of scope here.
5. **Signed-in email:** shown only in Program Settings, not on the Main Menu.
6. **Store location:** stays a per-device setting, independent of login.
7. **Rollout:** old installs are left alone. The owner updates every device and the web app first, then deploys the rules last.
8. **Grace period expired while offline:** the app keeps the session and shows the "Verification required" state with a Retry button; no password is asked once verification succeeds.
9. **Re-verification interval:** once per hour of active use. Main Menu resumes within an hour of the last successful verification skip the check; cold launch always checks.
10. **Splash duration:** the splash waits for verification or 10 seconds, whichever comes first; on timeout the offline/grace-period rules apply.
11. **Assumptions accepted:** rules text saved as `firestore.rules` at the project root; the Login screen does not pre-fill the previous email after a manual sign-out; the "Verification required" state and the revoked-access notice both live on the Login screen.

## Resolved Decisions (continued)

12. **Mid-session permission-denied handling:** Option B, central only. Splash and the Main Menu handle it; feature screens are not modified.
13. **Older installs:** keep working without login until the Feature 4 rules are deployed (see Feature 5). This matches the owner's intent.

## Open Questions (Round 4)

1. **Do you still want the Firestore rules (Feature 4) at all, and when?** Your answer on older installs says you want them to keep working without login, and they will, right up until the rules are deployed. The rules are also the only thing that stops someone with an old APK from bypassing the login entirely. Choose one:
   - **(a) Keep Feature 4 as written (recommended):** the rules file is created now but you deploy it only after every device and the web app are updated. Old installs work until that day, then stop. This gives you the real lockout in the end.
   - **(b) Defer Feature 4 indefinitely:** the rules file is still created and kept in the repo, but the spec no longer treats deploying it as part of this feature. The lockout relies on the app-side check alone, and old APKs can bypass it. The permission-denied handling in Splash and the Main Menu stays in the code but will never trigger until you deploy rules later.
   - **(c) Drop Feature 4 entirely:** no rules file, no permission-denied handling. Login is a screen gate only.

Assumption unless you say otherwise: **(a)**. - I prefer b

**Decision 14 (recorded):** Option **(b)**. The `firestore.rules` file is created and kept in the repository, but deploying it is **not** part of this feature. Old installs keep working without login indefinitely until the owner chooses to deploy the rules. The permission-denied handling in Splash and the Main Menu is still implemented and remains dormant until then.
