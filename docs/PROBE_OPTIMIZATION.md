# Phone 0.9.2 / TV 0.6.10

Shared probe workers are reused (maximum four per process), with a bounded queue and idle thread expiry after 30 seconds. Each round owns and cancels its futures; failed cleanup cannot skip future cancellation. Cancelled queued tasks are removed directly.

Blocking probe orchestration runs in runInterruptible. Cancellation interrupts the completion wait, whose finally closes probe sockets and cancels siblings. Session cancellation detaches the socket collection under the lock and closes it outside the lock, so late registration cannot be delayed by closing earlier sockets.

Mobile checks capture ephemeral physical network identifiers in addition to the persisted profile. A reconnect with the same interface and DNS no longer permits old probe results to be stored. TV checks monitor the physical network during a probe, cancel sockets on change and discard the result before publication. No periodic network probing has been added.

Sources:
- https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html
- https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback

Validation includes real socket cancellation, queued-work cancellation, concurrent rounds, thread reuse and existing strict eligibility/network profile/routing tests. Android CI builds and verifies both permanent signatures. Device-specific viewing performance still requires testing on the user's network.
