# Leque APK build branch

The **main** branch stays minimal with only \`repo.json\` and \`sumber.json\`.

This branch builds a **debug-only** Leque app from an audited official CloudStream Git commit. It does NOT import any modified APK or third-party plugins.

Settings:
- App name: **CloudStream Leque V5**
- Default profile: **Mod v5 by Leque**
- App version: **5.0.0**
- Debug package ID: **com.leque.cloudstreamv5.debug**
- Embedded repository URL: https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json
- App updates point to a separate, future repository, never to the CloudStream upstream releases.
- Extension auto-updates remain enabled.

The debug signing key may change between builds, so this is not a production update channel. No streaming provider is bundled; catalog remains \`[]\` until reviewed sources are added.

Official CloudStream source code is licensed under GPL-3.0; preserve its copyright/license attribution and provide corresponding modified source when distributing a derivative.

[Builds / Actions](https://github.com/YumAbd/cloudstream-leque/actions)
