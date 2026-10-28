# Setup dan semakan CloudStream Leque

Pemilik: `YumAbd`  
Repository: https://github.com/YumAbd/cloudstream-leque  
Branch: `main`

## 1. Semak fail diterbitkan

Buka pautan berikut:

- Manifest: https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json
- Indeks ekstensi: https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/sumber.json
- CI: https://github.com/YumAbd/cloudstream-leque/actions

`repo.json` hendaklah menunjuk hanya kepada `sumber.json` di repository sendiri.

## 2. Ujian setempat (Windows / Git Bash)

```bash
git clone https://github.com/YumAbd/cloudstream-leque.git
cd cloudstream-leque
python scripts/validate_repo.py
python scripts/check_links.py
```

Jika Python Windows menggunakan pelancar `py`, gantikan `python` dengan `py -3`.

Apabila `sumber.json` masih `[]`, validator akan memaparkan:

```text
PASS: repository format valid; 0 extensions indexed
```

## 3. Repository bebas dan pengurusan ekstensi

Tidak perlu mengimport indeks lain. Tambah hanya fail ekstensi yang telah disemak serta boleh diedarkan:

```bash
python scripts/add_plugin.py --file "./build/Example.cs3" --internal-name Example --name Example --author "Pemilik Sebenar" --version 1
python scripts/validate_repo.py
git add sumber.json extensions/
git commit -m "Add reviewed extension"
git push origin main
```

Untuk menaik taraf ekstensi sedia ada:

```bash
python scripts/replace_plugin.py --file "./build/Example.cs3" --internal-name Example --version 2
python scripts/validate_repo.py
git add sumber.json extensions/
git commit -m "Update extension"
git push origin main
```

Simpan fail `.cs3` dalam `extensions/` dan pastikan nilai `fileHash`/saiz dikemas kini. Jangan menganggap URL baharu menjamin ekstensi berfungsi; uji provider dan pemuatannya dalam aplikasi.

## 4. GitHub Actions

- **Validate Leque repository** — berjalan pada push, pull request dan manual run.
- **Monitor Leque extensions** — semak download dan SHA-256 sekali sehari atau atas permintaan.

Jika GitHub memerlukan pengaktifan Actions, aktifkan di halaman Actions. Fail workflow tidak memasang atau membina kod ekstensi secara automatik.

## 5. Sambungan kepada APK

Selepas manifest dan Actions disahkan, teruskan pembangunan APK berasaskan kod sumber rasmi CloudStream dalam workspace berasingan. Rujuk `docs/APK_INTEGRATION.md`. Identiti, signing key dan saluran kemas kini APK perlu berada di bawah kawalan Leque. Atribusi dan notis lesen wajib untuk kod aplikasi terbitan mesti dikekalkan.
