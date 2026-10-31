# CloudXtream V5.0.9: in-app updates

- Each time the app opens it asks `https://cloudxpanel.infopc.xyz/api/app/latest` for the latest
  signed release (no GitHub API rate limit on users' IPs).
- If newer, CloudStream's update dialog appears: **Update** downloads the APK from
  `https://cloudxpanel.infopc.xyz/download/apk` (Cloudflare R2) and opens Android's installer;
  the user taps **Update** and the app is replaced in place (activation is kept).
- Android only installs it over the existing app when the signing certificate matches, so the
  feed cannot replace the app with anything else. Only URLs on the CloudXtream domain are accepted.
- Updates were off by default since V5.0.1; V5.0.9 switches them on once. Users can turn them off
  in Settings → Updates, and "skip this version" still works.
- Users on V5.0.8 or older must install V5.0.9 once by hand; later versions are offered in the app.

## V5.0.10 fix

Upstream's newer Compose updater (GithubViewModel) also checked on startup and compared the release
asset name (`CloudXtream-by-Leque-v5.0.9`) with the app version (`5.0.9`), so it always offered an
update, even right after updating. V5.0.10 disables its startup check (InAppUpdater with the
CloudXtream feed is the only startup prompt) and makes it compare version numbers, offering only
newer versions. On V5.0.9, tap "Skip this Update" on that dialog until V5.0.10 is installed.
