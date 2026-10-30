# CloudXtream V5.0.4 Activation Preview

This side-by-side Android Debug APK has a distinct package ID:
`com.leque.cloudstreamv5.debug.activationpreview`.

It does not replace the existing V5.0.3 debug APK because that APK was
signed by an unrelated key. Never promise update-in-place without stable
signing identity and validated Android migration tests.

License: six uppercase Base32 characters A-Z,2-7 (30 bits), Android
Keystore RSA-PSS 2048-bit, D1 one-device binding, challenge/response
and fail-closed online checks at startup, foreground resume and every
45 seconds while the main/player screens are visible.

Staged preview only, not a fully certified production release. Security
gates remain for every playback/provider entrypoint, background service
review, locked superuser Settings UI/MFA, true OTA signing, FCM push,
API26 real-device testing and independent audit. Android OS controls
application install prompts and update permissions.
