#!/usr/bin/env python3
"""CloudXtream V5.0.7: extensions removed or disabled in the catalog are uninstalled."""
from pathlib import Path
import sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path("upstream").resolve()
files={}
def patch(path,before,after):
    current=files.get(path)
    if current is None: current=(ROOT/path).read_text(encoding="utf8")
    count=current.count(before)
    if count!=1: sys.exit("FAIL: V5.0.7 anchor count="+str(count)+" in "+path+": "+before[:70])
    files[path]=current.replace(before,after,1)

patch("gradle/libs.versions.toml",'versionCode = "50006"','versionCode = "50007"')
patch("gradle/libs.versions.toml",'versionName = "5.0.6"','versionName = "5.0.7"')
p="app/src/main/java/com/lagradost/cloudstream3/plugins/LequePluginSync.kt"
patch(p,"import kotlinx.coroutines.sync.Mutex","import java.io.File\nimport kotlinx.coroutines.sync.Mutex")
patch(p,"""                val preferred = getApiFromNameNull(DEFAULT_PROVIDER)""",
"""                // The owner removed or disabled an extension in the Leque catalog:
                // uninstall it, but only if it was installed from this repository's folder.
                // Extensions the user installed from other repositories are never touched.
                val activeNames = all.filter { it.status == 1 }.map { it.internalName }.toSet()
                var removed = 0
                if (activeNames.isNotEmpty()) {
                    for (data in PluginManager.getPluginsOnline()) {
                        if (data.internalName in activeNames) continue
                        val ownPath = PluginManager.getPluginPath(activity, data.internalName, repo.url)
                        if (data.filePath != ownPath.absolutePath) continue
                        try {
                            if (PluginManager.deletePlugin(File(data.filePath))) {
                                removed++
                                Log.i(TAG, "Removed extension no longer in catalog: " + data.internalName)
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "Catalog removal error: " + data.internalName, ex)
                        }
                    }
                }
                val preferred = getApiFromNameNull(DEFAULT_PROVIDER)""")
patch(p,"""", newlyInstalled=" + successes + ", failed=" + failed)""",
"""", newlyInstalled=" + successes + ", removed=" + removed + ", failed=" + failed)""")
for path,source in files.items():
    (ROOT/path).write_text(source,encoding="utf8")
print("PASS: V5.0.7 catalog removal sync",len(files),"files")
