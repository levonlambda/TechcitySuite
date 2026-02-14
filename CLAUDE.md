# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the project
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a single unit test class
./gradlew test --tests "com.example.techcitysuite.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean assembleDebug
```

## Project Overview

TechcitySuite is a business management Android app for a tech shop. It handles device sales (phones, tablets, laptops), accessory transactions, service payments (cash in/out, mobile loading, Skyro, Home Credit), accounts receivable with in-house installments, financing account tracking (Home Credit, Skyro, Samsung Finance), multi-ledger financial tracking, end-of-day reconciliation, and phone inventory management with barcode scanning.

## Architecture

**Single-module Android app** (`app/`) using Activity-based MVC — no MVVM, no Jetpack Navigation, no ViewModel/LiveData.

- **Activities** (`app/src/main/java/com/techcity/techcitysuite/`) — Each screen is an Activity (28 registered). Navigation is Intent-based. `SplashActivity` → `MenuActivity` → feature activities.
- **Singleton Managers** — `LedgerManager` (in-memory ledger state for CASH, GCASH, PAYMAYA, OTHERS) and `AppSettingsManager` (Firebase-backed settings with local caching). These hold global mutable state.
- **Models** — Kotlin data classes: `InventoryItem`, `DeviceTransaction`, `ServiceTransaction`, `AccessoryTransaction`, `LedgerEntry`, `Ledger`, `AppSettings`, `FinancingAccount`.
- **Utilities** — `TransactionProcessor` (ledger entry generation), `NotificationHelper`, `Constants`/`AppConstants` (Firebase collection names, config keys).

## Backend & Data

**Firebase Cloud Firestore** is the sole backend — no local database. Key collections: `inventory`, `device_transactions`, `service_transactions`, `accessory_transactions`, `financing_accounts`, `app_settings`, `app_config`, `suppliers`, `procurements`.

Async pattern: Kotlin Coroutines with `Dispatchers.Main`/`Dispatchers.IO` and Firebase `.await()` extensions. Coroutine scopes are created per-Activity and cancelled in `onDestroy()`.

Feature visibility is controlled by SharedPreferences (`"TechCitySettings"`) flags: `account_receivable_enabled`, `end_of_day_enabled`, `phone_inventory_enabled`.

## Key Technologies

- **Compile/Target SDK 36**, Min SDK 24
- **View Binding** enabled (no DataBinding, no Compose)
- **Firebase BOM 34.5.0** (Firestore + Analytics)
- **CameraX 1.3.4** + **ML Kit Barcode Scanning 17.3.0** (bundled, offline) for inventory barcode scanning
- **ZXing 3.5.2** for barcode generation
- **Kotlin Coroutines 1.8.0** for async
- **Material Design 3** with custom TechCity branding (`techcity_blue` palette)
- **Gradle 8.13.0** with Kotlin DSL and version catalog (`gradle/libs.versions.toml`)

## Conventions

- All timestamps use GMT+8 timezone
- Kotlin source and layout XML use View Binding (access views via `binding.viewId`)
- Firebase document IDs follow app-specific patterns; transactions include Firestore server timestamps
- No dependency injection — Firebase instances created directly in Activities
- Test coverage is minimal (placeholder tests only)

## Development Workflow

All features and tasks follow a strict 3-step process. **Each step requires explicit approval before proceeding to the next.** Do NOT combine steps or skip ahead.

### Step 1: Feature Spec

When asked to implement a new feature or task, **first create a spec document only**. Save it as a Markdown file in the `_specs/` folder at the project root (e.g., `_specs/feature-name.md`).

The spec document should include:
- Feature name and description
- User stories / use cases
- UI/UX flow (screens, navigation, user interactions)
- Data model (new or modified Firestore collections/documents, model classes)
- Business rules and logic
- Edge cases and error handling
- Any open questions

**STOP after creating the spec.** Do not proceed until I explicitly review, update, and ask you to move to the next step.

### Step 2: Technical Plan

When asked to create the technical plan, generate a plan document in the `_plans/` folder using the **same filename** as the spec (e.g., `_plans/feature-name.md`). Use plan mode (`/plan`) for this step.

The technical plan should include:
- Files to be created (with full paths)
- Files to be modified (with full paths and summary of changes)
- Implementation order / sequence of steps
- Dependencies or libraries needed (if any)
- Any migration or data considerations
- Risks or things to watch out for

**STOP after creating the plan.** Do not proceed until I explicitly review, update, and ask you to implement.

### Step 3: Implementation

When asked to implement, follow the approved technical plan **exactly**. During implementation:

#### CRITICAL — Scope Rules
- **ONLY make changes directly related to the feature or task I asked you to do.**
- **Do NOT refactor, optimize, rename, reorganize, or "improve" any existing code** that is not part of the current task.
- **Do NOT fix unrelated bugs, warnings, lint issues, or TODOs** you happen to notice.
- **Do NOT change code style, formatting, or structure** of files outside the current scope.
- **Do NOT add features, enhancements, or "nice-to-haves"** that were not in the approved spec and plan.
- **Do NOT modify, remove, or rewrite existing comments** in the code unless they are directly related to the current task.
- **Do NOT change spacing, indentation, formatting, or line breaks** in existing code that is not being modified for the current task. Unnecessary whitespace changes clutter the diff and make it hard to review what actually changed.
- If you notice something that should be fixed or improved outside the current scope, **mention it in a comment at the end of your response** instead of changing it. I will decide whether to address it separately.
- **Do NOT run any git commands** (`git commit`, `git push`, `git merge`, `git checkout`, etc.). I will handle all version control operations myself.

**Violating these scope rules risks breaking existing functionality.** Stay focused on the approved plan.