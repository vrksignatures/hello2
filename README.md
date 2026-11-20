# SudhaAutoBot (`com.vrksignatures.sudhabot`) — Complete v2.0 Rebuild

Deterministic, state-aware Android Accessibility Service + Floating Control Overlay targeting **Sudha Daily Orders** (`in.vasista.suprabhat`).

## Architecture & Resolved Root Causes
- **Bug 1 Fix (`ScreenInspector.kt`, `BotAutomationEngine.kt`)**: Inspects `Show Retailers` `isChecked` before tapping, taps **at most once** when unchecked, and treats the list as expanded immediately upon any true signal (`isChecked == true`, `Total Customers` jump, or any visible `Delivery Point (Retailer)` card).
- **Bug 2 Fix (`DatePickerEngine.kt`)**: Handles cross-month rollback (`<` arrow), clicks exact day cell (node click + coordinate gesture fallback), and performs **mandatory pre-OK verification** of the orange DatePicker header text before tapping `OK`.
- **Bug 3 Fix (`ScreenInspector.kt`)**: Dialog-First Guard always dismisses open modal dialogs (`OK` / `CANCEL`) before issuing `GLOBAL_ACTION_BACK`.
- **Bug 4 Fix (`ScreenInspector.kt`)**: Centralized `waitUntilIdle()` barrier pauses on `Loading`, `Getting placed orders...`, `Making Payment...`, `In Progress`, or any visible `ProgressBar` (never bypassed by `Fast Mode`).
- **Bug 5 Fix (`ScreenInspector.kt`, `BotAutomationEngine.kt`)**: Immediately recognizes the `Customers` screen on `▶ START` without hunting for the Home screen; `3. Open Sudha Daily Orders` launches the target app in `IDLE` so manual navigation remains unobstructed.
- **Bug 6 Fix (`ScreenInspector.kt`)**: Controlled 68% RecyclerView swipe distance preserves 1–2 overlapping cards across scrolls, deduplicated by `(CustomerId, BoldOrangeName, CardType)` and `processedIdsToday`.
- **Bug 7 Fix (`EntityRecognitionEngine.kt`)**: Zero-hardcoding Distributor vs. Retailer identification with bidirectional adjacent same-name pairing (`Dist -> Retailer` and `Retailer -> Dist`), preserving non-adjacent same-name genuine retailers and supporting zero-distributor lists.
- **Two-Tier Persistent Memory (`PermanentDistributorMemory.kt`, `DailyLedgerMemory.kt`)**: Strictly separated `SharedPreferences` stores; Tier 1 never auto-resets, Tier 2 auto-resets strictly at calendar midnight (`00:00` `LocalDate`).
- **Smart Scroll Jump & Audit (`ScrollAnchorEngine.kt`)**: Direct `perm + temp` jump, expanding UP/DOWN anchor recovery, and `tempRetailerScrollCount` audit recalibration.
- **5-Retry High-Volume Alarm (`AlarmController.kt`, `BotAutomationEngine.kt`)**: Retries known/unknown errors up to 5 times, then halts (`ALARM_HALTED`), logs full screen dump, sounds looping `STREAM_ALARM` + vibration, and surfaces the bright-red `STOP ALARM` button.
- **Quantity Manipulation Modes (`QuantityManipulationEngine.kt`)**: Full 13-SKU Milk table parser supporting Mode A (`Customer Demand` exact pack & smart volume delta) and Mode B (`Employee's Chance` non-consecutive anti-bot randomized scatter).

## Build & Run in Android Studio
1. Extract `SudhaAutoBot_Rebuild.zip` and open the `SudhaAutoBot/` folder in **Android Studio** (JDK 17, Android SDK 34).
2. Run `./gradlew testDebugUnitTest` to execute the JVM unit tests covering entity recognition, two-tier midnight memory, scroll anchor audit, and quantity modes.
3. Run `./gradlew assembleDebug` (or click **Run**) to install on your Android device (Android 8.0+ / API 26+).
4. In the app, tap **1. Allow floating panel**, **2. Enable accessibility service**, then **3. Open Sudha Daily Orders**, open `Customers` manually, and tap **▶ START**.
