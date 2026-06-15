# Add WifiManager MulticastLock around croc RECEIVE operations

## Context

`croc` (the bundled Go binary) discovers peers on the local network using
multicast via the `peerdiscovery` library (default group `239.255.255.250`,
configurable in Settings as `multicastAddress`). On Android the Wi-Fi driver
filters out multicast packets by default, so to actually receive the discovery
broadcasts the app must hold a `WifiManager.MulticastLock` while croc is
running its discovery/transfer phase.

The `CHANGE_WIFI_MULTICAST_STATE` permission is **already** declared in
`app/src/main/AndroidManifest.xml:8`, so no manifest change is required.

What is missing is the runtime `MulticastLock` acquisition/release. There is no
usage of `WifiManager.createMulticastLock(...)` anywhere in the codebase
(confirmed via grep).

## Goal

Acquire a multicast lock **only on the receiver side** for the duration of a
croc `receive` operation, and release it reliably when the operation ends
(success, error, or cancellation). The sender side does **not** take the lock.

## Plan

### 1. Add a multicast-lock helper to `CrocProcess`

File: `app/src/main/java/com/dking/crocapp/croc/CrocProcess.kt`

This is the right place because:
- It owns the lifecycle of every croc invocation (`send`, `sendText`, `receive`).
- All three entry points converge into `executeWithDnsFallback(...)` →
  `runCommand(...)`, which is where discovery + transfer happens.

Changes:

1. Add import:
   - `android.net.wifi.WifiManager`
2. Add lazily-created Wi-Fi plumbing:
   ```kotlin
   private val wifiManager: WifiManager? =
       context.applicationContext
           .getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager

   private var multicastLock: WifiManager.MulticastLock? = null
   ```
3. Add helpers (tag `"croc"`, non-reference-counted):
   ```kotlin
   private fun acquireMulticastLock() {
       val wm = wifiManager ?: return
       releaseMulticastLock()
       val lock = wm.createMulticastLock("croc").apply {
           setReferenceCounted(false)
           acquire()
       }
       multicastLock = lock
   }

   private fun releaseMulticastLock() {
       multicastLock?.let {
           try { if (it.isHeld) it.release() } catch (_: Exception) {}
       }
       multicastLock = null
   }
   ```

### 2. Scope the lock to the receiver path only

`executeWithDnsFallback(...)` is shared by `send`, `sendText`, and `receive`.
To restrict the lock to receiving, add a boolean parameter and only enable it
from `receive(...)`.

1. Add parameter to `executeWithDnsFallback`:
   ```kotlin
   private suspend fun executeWithDnsFallback(
       baseCommand: MutableList<String>,
       workDir: File,
       waitingState: CrocTransferState,
       extraEnv: Map<String, String>,
       prefs: UserPreferencesRepository.CrocPreferences,
       opName: String,
       holdMulticastLock: Boolean = false   // NEW
   )
   ```
2. Inside `executeWithDnsFallback`, wrap the body so the lock is acquired
   before the first `runCommand(...)` and released in a `finally` block
   (guarantees release on success, error, retry path, and cancellation):
   ```kotlin
   if (holdMulticastLock) acquireMulticastLock()
   try {
       // ... existing logic: runCommand, optional internal-dns retry,
       //     waitDone, state update ...
   } finally {
       if (holdMulticastLock) releaseMulticastLock()
   }
   ```
3. In `receive(...)` pass `holdMulticastLock = true`:
   ```kotlin
   executeWithDnsFallback(
       baseCommand = command,
       workDir = outputDir,
       waitingState = CrocTransferState.WaitingForPeer(code),
       extraEnv = secretEnv(code),
       prefs = prefs,
       opName = "Receive",
       holdMulticastLock = true   // NEW
   )
   ```
4. `send(...)` and `sendText(...)` keep the default `false` — **no lock**.
5. `cancel()` / `reset()` should also call `releaseMulticastLock()`
   defensively (belt-and-suspenders), since `cancel()` can be invoked from
   outside the coroutine that holds the lock (see `ReceiveViewModel.cancelTransfer`).

### 2. (Optional / follow-up) TransferService

The `TransferService` is currently a foreground-notification-only service and
does not run croc itself. No change required there. If multicast discovery
should also cover a future service-driven transfer, the same helper pattern
can be reused, but it is out of scope for this change.

## Verification

- `./gradlew :app:assembleDebug` (or the project’s build command) compiles.
- Run on a device on Wi-Fi, start a **receive** → observe in logcat that the
  lock is acquired, then released at end of transfer.
- Run a **send** → confirm the lock is **not** acquired.
- Verify local-network discovery now works reliably between two devices on the
  same Wi-Fi (previously discovery packets may have been dropped on the
  receiver).
- Confirm cancellation releases the lock (no `MulticastLock` leak warnings).

## Notes / decisions

- Lock tag: `"croc"` (as suggested by the user).
- `setReferenceCounted(false)` simplifies lifecycle: acquire once, release once.
- Guard `wifiManager` nullability (some emulators / non-Wi-Fi contexts).
