# CloudXtream signed releases

Workflow: **Release CloudXtream signed APK** (`.github/workflows/leque-release-apk.yml`). Push a tag `vX.Y.Z` on an `apk-build` commit (publishes a pre-release), or
run it manually (`workflow_dispatch`) on branch `apk-build`.

- Builds `:app:assembleStableRelease` (package `com.leque.cloudstreamv5`, no debug suffix,
  so it installs alongside debug previews), applies the same Leque patches as the preview.
- Signs with zipalign + apksigner (v1/v2/v3) using repository secrets
  `CX_RELEASE_KEYSTORE_B64`, `CX_RELEASE_KEYSTORE_PASSWORD`, `CX_RELEASE_KEY_ALIAS`.
  The decoded keystore lives only in `$RUNNER_TEMP` and is deleted after signing.
- Fails closed if a secret is missing, if the tag differs from the app `versionName`,
  if the signer certificate differs from `leque/release-signing-cert.sha256`, if the APK
  crashes or does not open the activation screen on an Android 8.0 emulator, or if the
  release tag already exists (never replace a published signed APK).
- Publishes a GitHub Release with the APK, SHA256SUMS, source patch (GPL-3.0) and
  signing-certificate report.

This repository is **public**: never commit the keystore or password, and never upload
them as artifacts. Back up the keystore and password in a password manager; if they are
lost, existing installs cannot be updated in place.

The release tag sets the app version: `vX.Y.Z` becomes versionName `X.Y.Z` and versionCode
`X*10000 + Y*100 + Z` (Y and Z below 100). A tag older than the patched app version is refused.
To publish: Telegram `/release` (or the "Publish signed release" button after a successful preview
build), or run the workflow manually with the next `release_tag`.
