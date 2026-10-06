# Technical Plan: Firebase Authentication Login

Spec: `_specs/firebase-auth-login.md` (all 14 decisions resolved; Feature 4 rules file is created but **not deployed**, per decision 14).

## Summary of the approach

- Add the Firebase Authentication SDK (Email/Password) through the existing Firebase BOM.
- Introduce one new singleton, `AuthManager`, that owns every auth concern: sign-in with error mapping, online re-verification, the one-hour throttle, the 24-hour grace period, sign-out with state clearing, permission-denied detection, and the "send the user to Login" routine. Splash, Main Menu, and Program Settings call into it; no auth logic lives in Activities.
- Add one new `LoginActivity` with two visual states (Sign In, Verification Required) driven by an Intent extra.
- Splash becomes the router: no user goes to Login, a user goes through verification (10-second cap) and then Main Menu, offline falls back to the grace-period rule.
- Main Menu gains a signed-in guard in `onCreate`, the throttled re-verification in `onResume`, and permission-denied handling in its two existing Firestore touch points (settings load and the device transaction listener).
- Program Settings gains an "Account" card (email + Sign Out) placed after the Feature Settings card, before the Save/Cancel buttons.
- A `firestore.rules` file is added at the project root for the owner to deploy later. Nothing in the build references it.

Feature screens are **not** touched (spec decision 12).

---

## Files to be created

### 1. `app/src/main/java/com/techcity/techcitysuite/AuthManager.kt`

Kotlin `object`, same style as `AppSettingsManager` (PART banners, `suspend` functions using `.await()`).

Responsibilities:

- **Session queries:** `currentUser()`, `isSignedIn()`, `currentEmail()` (empty string when none), all wrapping `FirebaseAuth.getInstance()`. Uses the plain `FirebaseAuth` API, not the deprecated KTX accessor.
- **Sign-in:** `suspend fun signIn(email, password): SignInResult` where `SignInResult` is a sealed class `Success` / `Failure(message)`. Maps Firebase exceptions to the exact spec messages:
  - `FirebaseAuthInvalidCredentialsException` with error code `ERROR_INVALID_EMAIL` → "Please enter a valid email address."
  - Any other `FirebaseAuthInvalidCredentialsException`, and `FirebaseAuthInvalidUserException` with `ERROR_USER_NOT_FOUND` → "Incorrect email or password." (Also covers the `INVALID_LOGIN_CREDENTIALS` code that projects with email-enumeration protection return, so unknown email and wrong password are indistinguishable as the spec requires.)
  - `FirebaseAuthInvalidUserException` with `ERROR_USER_DISABLED` → "This account has been disabled. Please contact the administrator."
  - `FirebaseTooManyRequestsException` → "Too many attempts. Please try again later."
  - `FirebaseNetworkException` → "No internet connection. Please check your connection and try again."
  - Anything else → "Sign in failed. Please try again."
  On success, records the last verified time.
- **Verification:** `suspend fun verifyAccess(context): VerifyResult` where `VerifyResult` is `Valid` / `Revoked` / `Offline`. Implementation: reload the current user, then force a token refresh (`getIdToken(true)`). `FirebaseAuthInvalidUserException` (codes `ERROR_USER_NOT_FOUND`, `ERROR_USER_DISABLED`, `ERROR_USER_TOKEN_EXPIRED`) or a null `currentUser` after the call (the SDK can auto-sign-out an invalid user) → `Revoked`. `FirebaseNetworkException` or any other `IOException`-like failure → `Offline`. Success → record last verified time, return `Valid`. Password changes in the console surface as `ERROR_USER_TOKEN_EXPIRED` and therefore as `Revoked`, matching the spec.
- **Timing helpers (read the new SharedPreferences key):**
  - `needsVerification(context)`: true when no last verified time exists or it is **≥ 1 hour** old (`VERIFY_INTERVAL_MS = 60 * 60 * 1000`).
  - `isWithinGracePeriod(context)`: true when a last verified time exists and it is **< 24 hours** old (`GRACE_PERIOD_MS = 24 * 60 * 60 * 1000`).
  - `recordVerified(context)` / `clearVerified(context)`.
- **Sign-out:** `signOut(context)`: Firebase sign-out, `clearVerified`, `LedgerManager.clearAll()`, `AppSettingsManager.clearCache()`. Does not touch any other SharedPreferences value.
- **Permission-denied detection:** `isPermissionDenied(e: Throwable?)`: true when `e` (or its cause) is a `FirebaseFirestoreException` with code `PERMISSION_DENIED`.
- **Routing:** `goToLogin(activity, mode, notice)`: builds the `LoginActivity` Intent with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`, puts the extras, starts it and finishes the caller. `handleRevoked(activity)` = `signOut` + `goToLogin` with the revoked notice. This is the single place the back stack is cleared, so Feature 2 step 4 and Feature 3 step 3 behave identically.
- **Constants:** `EXTRA_MODE`, `MODE_SIGN_IN`, `MODE_VERIFICATION_REQUIRED`, `EXTRA_NOTICE`, `NOTICE_REVOKED = "Your access has been revoked. Please contact the administrator."`, `VERIFICATION_REQUIRED_MESSAGE = "This device has been offline for more than 24 hours. Connect to the internet to continue."`.

### 2. `app/src/main/java/com/techcity/techcitysuite/LoginActivity.kt`

`AppCompatActivity` with View Binding and a per-Activity `CoroutineScope` cancelled in `onDestroy`, matching the other Activities.

- Reads `EXTRA_MODE` and `EXTRA_NOTICE` from the Intent (and from a re-delivered Intent in `onNewIntent`, since the Activity is launched with `CLEAR_TASK`).
- **Sign In mode:** email + password fields, Sign In button. Empty check → inline "Please enter your email and password". Otherwise disable form, show progress, call `AuthManager.signIn`. `Success` → start `MenuActivity`, `finish()`. `Failure` → re-enable form, show message inline, clear and focus the password field. IME Done on the password field triggers Sign In. If `EXTRA_NOTICE` is present it is shown in the inline error area on entry (the revoked-access notice).
- **Verification Required mode:** email field shows `AuthManager.currentEmail()` read-only (disabled), password field hidden, message text visible with `VERIFICATION_REQUIRED_MESSAGE`, Sign In button hidden, Retry button visible. Retry → progress, `AuthManager.verifyAccess`: `Valid` → Main Menu; `Revoked` → `AuthManager.signOut` and switch the same screen to Sign In mode with the revoked notice; `Offline` → inline "Still offline. Please check your connection and try again." and re-enable Retry.
- **Back:** `OnBackPressedCallback` calling `finishAffinity()` in both modes (exits the app).
- **Rotation:** the Activity is locked to portrait in the manifest (the same approach `BarcodeScannerActivity` already uses), which satisfies the spec's "typed email retained, request not duplicated" without extra state handling.
- Sets the version text from `BuildConfig.VERSION_NAME`-free approach: reuses the same static "Version 1.0" string resource the Splash and Main Menu layouts use, to stay consistent with them.

### 3. `app/src/main/res/layout/activity_login.xml`

`ScrollView` (fillViewport) → vertical `LinearLayout`, white background, 24dp padding, centred. Mirrors the Splash/Main Menu branding block:

- `ImageView` logo (`@drawable/techcity_logo`, 120dp).
- Title "TechCity Suite" (28sp bold, `@color/techcity_blue`), subtitle "Sign in to continue" (14sp, `@color/gray`).
- `TextInputLayout` + `TextInputEditText` **`emailInput`** (hint "Email", `inputType=textEmailAddress`, single line).
- `TextInputLayout` **`passwordInputLayout`** with `app:endIconMode="password_toggle"` + `TextInputEditText` **`passwordInput`** (hint "Password", `inputType=textPassword`, `imeOptions=actionDone`).
- `TextView` **`verificationMessage`** (gone by default, 14sp, `@color/gray`, centred).
- `TextView` **`errorMessage`** (gone by default, `@color/red`, 13sp, centred). Same pattern as `dialog_password.xml`.
- `ProgressBar` **`progressBar`** (gone by default, `indeterminateTint=@color/techcity_blue`).
- `Button` **`signInButton`** (full width, default TechCity button style, text "Sign In").
- `Button` **`retryButton`** (full width, gone by default, text "Retry").
- `TextView` **`versionText`** at the bottom (12sp, darker gray, `@string/version_1_0`).

All new strings that are user-visible in the layout are added to `strings.xml` (see below); Kotlin-side messages live as constants in `AuthManager`, matching how `StoreLocationHelper` keeps its messages.

### 4. `firestore.rules` (project root)

Rules version 2, single wildcard match on all documents, `allow read, write: if request.auth != null;`. A leading comment states it is **not deployed** by this feature and that deploying it locks out every install and web client that does not sign in. No `firebase.json` is added, so nothing tries to deploy it.

---

## Files to be modified

### 5. `app/build.gradle.kts`

Add one line directly under the existing `firebase-firestore` implementation, inside the existing BOM block:

- `implementation("com.google.firebase:firebase-auth")`

Nothing else changes. The BOM pinned in this file (33.5.1) resolves the matching Auth version. No `google-services.json` change is needed for Email/Password.

### 6. `app/src/main/java/com/techcity/techcitysuite/Constants.kt`

Add one key to the `AppConstants` SharedPreferences section, under "User and Location":

- `const val KEY_LAST_VERIFIED_TIME = "auth_last_verified_time"` (stored in the existing `TechCitySettings` prefs file, epoch milliseconds, `Long`).

### 7. `app/src/main/res/values/strings.xml`

Append (additive only): `login_title` "Sign in to continue", `login_email` "Email", `login_password` "Password", `login_sign_in` "Sign In", `login_retry` "Retry", `settings_account_section` "Account", `settings_signed_in_as` "Signed in as", `settings_sign_out` "Sign Out".

### 8. `app/src/main/AndroidManifest.xml`

Add one `<activity>` entry after `MenuActivity`:

- `.LoginActivity`, `exported="false"`, `label="Sign In"`, `screenOrientation="portrait"`, `windowSoftInputMode="adjustResize"`.

Splash remains the launcher. No other manifest change.

### 9. `app/src/main/java/com/techcity/techcitysuite/SplashActivity.kt`

Replace the single `Handler.postDelayed` navigation with a routing coroutine (per-Activity scope, cancelled in `onDestroy`):

1. Record start time. Keep the existing **2-second minimum** display so branding behaviour is unchanged.
2. If `AuthManager.isSignedIn()` is false → after the minimum delay, `goToLogin(MODE_SIGN_IN)`.
3. Otherwise run `AuthManager.verifyAccess(this)` inside `withTimeoutOrNull(10_000)`:
   - `Valid` → `MenuActivity`.
   - `Revoked` → `AuthManager.handleRevoked(this)` (sign out + Login with revoked notice).
   - `Offline` or timeout (`null`) → if `isWithinGracePeriod` → `MenuActivity`; else `goToLogin(MODE_VERIFICATION_REQUIRED)`.
4. Every branch waits for the 2-second minimum before navigating, then `finish()`.

The spec's mention of permission-denied handling in Splash has nothing to implement: Splash makes no Firestore calls. Auth failures are handled by the `Revoked`/`Offline` results above.

### 10. `app/src/main/java/com/techcity/techcitysuite/MenuActivity.kt`

Minimal insertions, no restructuring of existing methods:

- **`onCreate`**, first thing after `super.onCreate`: if `!AuthManager.isSignedIn()` → `AuthManager.goToLogin(this, MODE_SIGN_IN)` and `return` before inflating. This is the "Main Menu refuses to show without a signed-in user" guard.
- **`onResume`**: add a call to a new private `checkAccessOnResume()` after the existing two calls. It does: if not signed in → `goToLogin`; else if `AuthManager.needsVerification(this)` → launch `verifyAccess` in `scope`: `Valid` → nothing; `Revoked` → `handleRevoked`; `Offline` → if `!isWithinGracePeriod` → `goToLogin(MODE_VERIFICATION_REQUIRED)`. Resumes within the hour skip everything (a last verified time under one hour is necessarily under 24 hours, so the grace rule is satisfied implicitly, as the spec describes).
- **`loadAppSettings`** catch block: add `if (AuthManager.isPermissionDenied(e)) AuthManager.handleRevoked(this@MenuActivity)`; otherwise unchanged (still silent).
- **`setupDeviceTransactionListener`** error branch: change `if (error != null) { return }` to check `AuthManager.isPermissionDenied(error)` first and call `handleRevoked`, then return. The listener is already removed in `onDestroy`, which runs when `handleRevoked` finishes the Activity.

The existing `LedgerManager.clearAll()` call in `onCreate` stays as is.

### 11. `app/src/main/res/layout/activity_program_settings.xml`

Insert a new **PART 5: ACCOUNT CARD** between the end of the Feature Settings card and the buttons layout, following the exact card pattern already used (MaterialCardView, 16dp margins, 8dp corner radius, 1dp stroke), with a distinct stroke/title colour (`@color/red` family to signal the destructive action, or `@color/gray`; implementation will use `@color/gray` for the card and `@color/red` for the button):

- Card id **`accountCard`**, `layout_constraintTop_toBottomOf="@id/featureSettingsCard"`.
- Title "Account" (16sp bold).
- Row: label "Signed in as" (12sp gray) above `TextView` **`signedInEmailText`** (14sp bold black).
- `Button` **`signOutButton`**, `style="@style/Widget.MaterialComponents.Button.OutlinedButton"`, `app:strokeColor="@color/red"`, `android:textColor="@color/red"`, text "Sign Out", full width, 12dp top margin.

Then change **one attribute** on the existing `buttonsLayout`: `app:layout_constraintTop_toBottomOf="@id/featureSettingsCard"` → `"@id/accountCard"`. Renumber the existing PART 5 banner comment for the buttons to PART 6 only if the banner is edited anyway; otherwise leave the comment untouched (the plan prefers leaving it untouched and titling the new block "PART 4B" to avoid comment churn).

### 12. `app/src/main/java/com/techcity/techcitysuite/ProgramSettingsActivity.kt`

- **`onCreate`**: add `setupAccountSection()` call after `setupButtonListeners()`.
- New private `setupAccountSection()`: set `binding.signedInEmailText.text = AuthManager.currentEmail()`; `signOutButton` click → `AlertDialog` titled "Sign Out?" with the spec message "Sign out of TechCity Suite on this device? You will need the account email and password to sign in again.", buttons Cancel / Sign Out. Confirm → `AuthManager.signOut(this)` then `AuthManager.goToLogin(this, MODE_SIGN_IN)` (the `CLEAR_TASK` flag finishes Main Menu as well).

No change to `saveSettings`, `loadSettings`, or the store location logic.

---

## Implementation order

1. `app/build.gradle.kts`: add `firebase-auth`; Gradle sync.
2. `Constants.kt`: add `KEY_LAST_VERIFIED_TIME`.
3. `AuthManager.kt`: full singleton (compiles standalone; unit-testable timing helpers).
4. `strings.xml` additions, then `activity_login.xml`.
5. `LoginActivity.kt`.
6. `AndroidManifest.xml`: register `LoginActivity`.
7. `SplashActivity.kt`: routing.
8. `MenuActivity.kt`: guard, resume check, permission-denied hooks.
9. `activity_program_settings.xml` + `ProgramSettingsActivity.kt`: Account card and Sign Out.
10. `firestore.rules` at project root.
11. `./gradlew assembleDebug`, then the manual test pass below.

## Dependencies

- **New:** `com.google.firebase:firebase-auth` via the existing BOM. Transitively pulls in the reCAPTCHA and Play Services Auth-API libraries; expect a modest APK size increase. No new permissions (INTERNET already declared).
- **Firebase Console (owner, manual, before testing):** Authentication → Sign-in method → enable **Email/Password**; Authentication → Users → add one user per branch. No SHA fingerprint is required for Email/Password.
- No changes to `gradle/libs.versions.toml` (the app module pins its BOM directly and this plan follows that).

## Migration and data considerations

- **No Firestore data migration.** No new collections, no document shape changes.
- **Existing installs updated in place:** on first launch after the update the user lands on Login once; every existing SharedPreferences value (store location, user, feature toggles, account names) is preserved. `TechCityDevicePrefs` (device ID) is untouched, so `app_settings/{deviceId}` continues to resolve.
- **Old installs not yet updated:** unaffected, indefinitely, because the rules are not deployed (spec Feature 5, decision 14).
- **Web app:** out of scope; unaffected until the owner deploys rules.
- **`firestore.rules`** is inert until deployed. When the owner eventually deploys it, every client that does not sign in (old APKs, un-updated web app) loses access immediately.

## Risks and things to watch

- **Google Play services on store devices.** Firebase Auth on Android expects Play services to be present; devices without it can fail at sign-in with a generic error. The existing app already relies on Play-services-adjacent components, so this is unlikely, but verify on one real store device before rolling out.
- **SDK auto-sign-out.** When `reload()` discovers a deleted user, the Auth SDK may null out `currentUser` on its own. `verifyAccess` treats "exception or null user afterwards" as `Revoked` so the revoked notice is still shown rather than a bare Login screen.
- **Error-code drift.** Newer Firebase projects return `INVALID_LOGIN_CREDENTIALS` for both unknown email and wrong password. The mapping collapses all `FirebaseAuthInvalidCredentialsException` cases except invalid-email into "Incorrect email or password.", so behaviour is correct either way.
- **10-second splash cap on very slow links.** A slow but live connection that exceeds 10 seconds is treated as offline and falls into the grace rule; the next Main Menu resume (after an hour) or relaunch retries. Acceptable per spec.
- **Device clock.** Both the 1-hour throttle and 24-hour grace use `System.currentTimeMillis()`; the spec accepts clock-change effects.
- **Dormant permission-denied paths.** The two Main Menu hooks cannot be exercised until rules are deployed. They are simple and guarded by `isPermissionDenied`, so they cannot misfire on other errors.
- **Firestore offline cache and the listener.** After rules are deployed someday, the device transaction listener's error callback fires `PERMISSION_DENIED` once; `handleRevoked` finishes the Activity and `onDestroy` removes the listener, so it does not loop.
- **Testing the 24-hour path.** There is no debug switch (adding one would be out of scope). Test by signing in, then moving the device clock forward more than 24 hours with airplane mode on and relaunching; expect the Verification Required state.
- **Spec wording note.** The spec places the Account card "below the feature toggles and password section"; Program Settings has no password section, so the card goes directly after the Feature Settings card.

## Manual test checklist (after implementation)

1. Fresh install, no account → Login shown; empty submit → inline prompt; wrong password → "Incorrect email or password."; malformed email → "Please enter a valid email address."; airplane mode → no-internet message.
2. Correct credentials → Main Menu; kill and relaunch → Main Menu directly (no Login).
3. Program Settings → Account card shows the email; Sign Out → confirm → Login; Back on Login exits the app.
4. Disable the user in the console → relaunch → revoked notice on Login. Re-enable → sign in works.
5. Delete the user in the console → relaunch → revoked notice.
6. Change the user's password in the console → relaunch → revoked notice; new password signs in.
7. Signed in, airplane mode, relaunch within 24 hours → Main Menu. Clock forward more than 24 hours, relaunch → Verification Required with Retry; airplane mode off → Retry → Main Menu without password.
8. Existing device with saved store location updated in place → after login the branch label and all settings are intact.
