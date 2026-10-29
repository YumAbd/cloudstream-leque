#!/usr/bin/env python3
"""CloudXtream V5.0.3: name, isolated catalog URL, and verified plugin reconciliation."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path("upstream").resolve()
changes = {}

def patch(path: str, before: str, after: str) -> None:
    src = changes.get(path)
    if src is None:
        src = (ROOT / path).read_text(encoding="utf-8")
    count = src.count(before)
    if count != 1:
        sys.exit(f"FAIL: V5.0.3 anchor mismatch ({path}) expected 1 got {count}: {before[:80]}")
    changes[path] = src.replace(before, after, 1)

for path in [
    "app/src/main/res/values/donottranslate-strings.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/debug/res/values/strings.xml",
    "shared/src/commonMain/composeResources/values/strings.xml",
]:
    patch(path, "CloudStream by Leque", "CloudXtream by Leque")

p = "gradle/libs.versions.toml"
patch(p, 'versionCode = "50002"', 'versionCode = "50003"')
patch(p, 'versionName = "5.0.2"', 'versionName = "5.0.3"')

p = "app/src/main/java/com/lagradost/cloudstream3/plugins/RepositoryManager.kt"
patch(p, 'name = "CloudStream AutoPlaylist by Leque"', 'name = "CloudXtream AutoPlaylist by Leque"')
patch(
    p,
    'url = "https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json"',
    'url = "https://raw.githubusercontent.com/YumAbd/cloudstream-leque/apk-build/repo.json"',
)

p = "app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt"
patch(
    p,
    'import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull',
    'import com.lagradost.cloudstream3.APIHolder\nimport com.lagradost.cloudstream3.APIHolder.getApiFromNameNull',
)
patch(
    p,
    'import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins',
    'import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins\nimport com.lagradost.cloudstream3.plugins.RepositoryManager.sha256',
)
patch(
    p,
    """                    val alreadyInstalled = local.isFile &&
                        PluginManager.getPluginsOnline().any {
                            it.internalName == name && it.filePath == local.absolutePath
                        }
                    if (alreadyInstalled) continue""",
    """                    val installedData = PluginManager.getPluginsOnline().firstOrNull {
                        it.internalName == name && it.filePath == local.absolutePath
                    }
                    // A file on disk alone does not prove that the right plugin is loaded.
                    // Verify source URL, pinned hash (where published), and an actual MainAPI.
                    val binaryMatches = local.isFile &&
                        (hash == null || runCatching { sha256(local) == hash }.getOrDefault(false))
                    val providerRegistered = APIHolder.apis.any {
                        it.sourcePlugin == local.absolutePath
                    }
                    val alreadyInstalled = binaryMatches && installedData?.url == item.url &&
                        providerRegistered
                    if (alreadyInstalled) continue
                    Log.i(TAG, "Reconciling missing/stale provider: $name")""",
)
patch(
    p,
    '                Log.i(TAG, "Leque catalog installed: $successes; failures: $failed")',
    """                val installed = toInstall.count {
                    val target = PluginManager.getPluginPath(activity, it.internalName, repo.url)
                    target.isFile
                }
                val registered = toInstall.count {
                    val target = PluginManager.getPluginPath(activity, it.internalName, repo.url)
                    APIHolder.apis.any { api -> api.sourcePlugin == target.absolutePath }
                }
                Log.i(TAG, "Catalog active=" + toInstall.size + ", files=" + installed +
                    ", providers=" + registered + ", newlyInstalled=" + successes + ", failed=" + failed)""",
)

for path, content in changes.items():
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
print("PASS: CloudXtream V5.0.3 UI/catalog/plugin reconciliation:", len(changes), "files")

