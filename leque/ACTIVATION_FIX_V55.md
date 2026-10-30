# CloudXtream V5.0.5 Activation Fix Preview

- Android 8-compatible \`SHA256withRSA\` PKCS1-v1.5 key from AndroidKeyStore (RSA2048).
- Worker protocol includes \`signature_scheme=rsa-pkcs1-sha256\`, per-device scheme binding.
- Server D1 migration 0005 is mandatory before preview activation.
- All older PSS-bound registered devices remain supported by the backend.
- Android app surfaces safe stage/HTTP failure codes; never logs PIN or private key.
- Registration and subsequent online validation are different stages: registered
  device should use Retry, **not** another one-time PIN.
- Debug preview has a new app ID: com.leque.cloudstreamv5.debug.activationfix.
- Android 8 test APK runs an actual KeyStore sign-and-verify instrumentation test.

**Owner manual production process:** apply migration 0005 first with remote D1 backup,
then deploy Worker on main. Do not install preview until Cloudflare deployment succeeds.
No permanent app-signing key or verified in-place upgrade is available yet.
Do not distribute this debug preview as a production release.
