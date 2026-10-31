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
