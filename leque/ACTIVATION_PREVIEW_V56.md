# CloudXtream V5.0.6 Activation Preview

Builds on V5.0.5 (RSA PKCS#1 Android Keystore key, same app ID
`com.leque.cloudstreamv5.debug.activationfix`).

- The online lease now follows the Worker's `revalidate_in` (300 s on
  cloudxtream-control with the D1-quota fix), clamped locally to 30–600 s.
- The 45-second timer in Main and player screens calls the Worker only when
  the lease ends within 60 seconds: about one check every 4–5 minutes instead
  of every 45 seconds. Offline or failed checks still lock the app.
- Requires the Worker version that returns `revalidate_in: 300`; an older
  Worker (60 s) still works, with checks about every 45 seconds.

Debug preview only: runner-local debug signing. If Android refuses to update
over an earlier preview, uninstall it first (activation must then be reset by
the owner and a new PIN issued). Not a production release.
