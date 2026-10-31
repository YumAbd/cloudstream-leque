# CloudXtream V5.0.7 catalog removal sync

- When an extension is removed from `sumber.json`, or its `status` is set to 0 (Admin
  Dashboard → Extensions → Remove / Disable), the app uninstalls it on the next catalog
  sync (on open/resume, at most every 15 minutes).
- Only plugins installed from the CloudXtream repository folder are removed. Extensions a
  user installed from any other repository are never touched.
- Nothing is removed when the catalog cannot be read or has no active entries.
- Same package and signing key as v5.0.6: installs over it as an update.
