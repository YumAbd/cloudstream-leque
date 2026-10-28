**Repo GitHub:** https://github.com/YumAbd/cloudstream-leque  
**Install URL:** https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json  
**Panduan langkah pertama:** [SETUP_GITHUB_YUMABD.md](SETUP_GITHUB_YUMABD.md)

# CloudStream Mod V5 by Leque - repository

Status: **project template**, not a ready-to-install APK. GitHub publication and Android build must be done by the repository owner.

## Goal

- Own the **stable** repository manifest (`repo.json`) and plugin catalog (`sumber.json`).
- Optional `cloudstream.m3u8` placeholder is independent of CloudStream extension metadata; connect it from an IPTV extension explicitly if used. Do not assume publishing it loads channels automatically.
- Build a clean CloudStream fork with default local profile `Mod v5 by Leque`.
- Pre-register the Leque repository at first launch.
- Publish versioned plugin fixes to GitHub without forcing every user to reinstall the Android app.

## 1. Create YOUR GitHub repo

1. Public repository `YumAbd/cloudstream-leque` sudah dicipta di GitHub.
2. Extract pakej dan upload/commit **semua kandungan folder utama** ke branch `main`, termasuk `.github/workflows/` dan `scripts/` (jangan upload fail ZIP itu sendiri, secret atau Android keystore).
3. Manifest telah ditetapkan kepada akaun `YumAbd` — tidak perlu jalankan `configure.py` lagi.
4. Verify in your browser:

   `https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json`

   and

   `https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/sumber.json`

5. Until you import metadata or add your own properly licensed extensions, `sumber.json` contains `[]` and the catalog is empty. That is intentional and safe.

## 2. OPTIONAL: import upstream metadata once

Original third-party index is `gvisionXcloudstream/bayustecu`, published without a detected `LICENSE` file (checked 2026-10-28). A public GitHub repository does **not** imply a broad license to redistribute its content. Confirm rights/permission and suitability of individual plugins before public distribution.

   `python scripts/import_bayu_index.py`

This copies **metadata only** from the upstream URL to the local catalog. It does not copy the `.cs3` binaries. Items marked NSFW are omitted by default. You may add `--exclude-phisher` if you prefer not to include Phisher-hosted plugins (including MovieBox, which may be important to your usage). Whether those plugins cause the donation popup must still be tested. See `--help` for filters. The downloaded third-party URLs may later stop working; the import is not a permanent mirror.

   `python scripts/validate_repo.py`

Review `sumber.json`, remove unwanted or unlicensed providers, then commit it to GitHub.

Do **not** set GitHub Actions to blindly copy new upstream plugins or bypass hash checks. Treat updates as new code that needs review.

## 3. Replace a broken plugin

A plugin initially appears like:

   `{"internalName":"Example","name":"Example","version":4,"url":"https://.../Example.cs3", ...}`

The update must retain `internalName` and increment `version` to 5 or higher.

### Option A: have a trusted new file `Example.cs3`

   `python scripts/replace_plugin.py --internal-name Example --version 5 --file ./Example.cs3 --github-user YumAbd`

This copies it into `extensions/Example.cs3`, computes `fileHash` SHA256 and size, updates `sumber.json` to your own raw GitHub URL. Commit `extensions/` and `sumber.json` together.

### Option B: point to a trusted HTTPS build hosted elsewhere

   `python scripts/replace_plugin.py --internal-name Example --version 5 --url https://trusted.example.org/Example.cs3`

The script downloads and calculates SHA256, records its real size, and edits `sumber.json`. The external URL still remains a dependency; hosting an authorized, audited copy in your repository is more durable.

### Required rollout procedure

- Review the source and permission to distribute the replacement plugin.
- Publish/test the binary at the URL in `sumber.json` before end users receive the updated catalog.
- Check that the downloaded file matches `fileHash`.
- Commit/push to GitHub `main`, wait for the GitHub Pages/raw cache to refresh, then relaunch CloudStream with internet and auto-updates enabled.
- Do not assume all installed users update at once: it happens at a suitable app refresh/start and may fail due to network, broken binaries, disabled updates, etc.

## 4. Monitor without silently installing unknown binaries

- `.github/workflows/validate.yml`: validates JSON on every push/PR.
- `.github/workflows/monitor.yml`: daily read-only check of plugin downloads and SHA256. A failed workflow indicates a broken link/integrity mismatch; it does **not** auto-replace code.

In local terminal:

   `python scripts/validate_repo.py`
   `python scripts/check_links.py`

## 5. Build branded APK

See [docs/APK_INTEGRATION.md](docs/APK_INTEGRATION.md).

   `git clone https://github.com/recloudstream/cloudstream.git`

After GitHub repo is public:

   `python scripts/patch_cloudstream_source.py --source C:/Projects/cloudstream --github-user YumAbd`

The patch makes four targeted changes to official CloudStream source: add your fixed prebuilt repo, replace default profile resource, set independent Android application ID and rebrand the launcher label. It does **not** magically patch the old App Cloner package. Build and sign with Android Studio; run clean-install and upgrade tests. Before publishing, disable or redirect CloudStream official app-update feed to releases signed by Leque (see docs/APK_INTEGRATION.md).

## Distinguish issues

| Problem | GitHub JSON change enough? |
| --- | --- |
| Bayu Stecu deletes its index | Yes, once own `repo.json` and `sumber.json` are independent. |
| Link to `.cs3` is dead | Only if you have a working authorized replacement `.cs3` and bump `version`. |
| Provider's target website changes | No; fix provider code and build/version a replacement. |
| Your own repository base URL changes or dies | No; previously installed APKs cannot discover a new root URL unless fallback/update logic was built in. |
| New extension appears in catalog | Users must usually install it manually; auto-update covers installed extensions. |
| Donations popup from third-party plugin | Identify and omit/rework the specific extension with its author's permission; repository JSON alone won't remove injected plugin UI. |

**Version and security:** keep ownership of signing keystore and repo; review PRs; pin/trust download sources; never publish a blanket auto-execute arbitrary `.cs3` pipeline.
