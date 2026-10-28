# Setup repository CloudStream Leque (akaun GitHub: YumAbd)

## 1. Muat naik template ke repository kosong

Repository telah dicipta: https://github.com/YumAbd/cloudstream-leque

**Pilihan disyorkan: Git Bash (Windows 11)**

1. Extract ZIP template. Pastikan fail `repo.json`, `sumber.json` dan folder `.github` berada di **root**, bukan di subfolder tambahan.
2. Buka Git Bash di folder projek pilihan, misalnya `C:/Users/<nama>/Projects`.
3. Clone repository kosong:

   ```bash
   git clone https://github.com/YumAbd/cloudstream-leque.git
   cd cloudstream-leque
   ```

4. Copy semua fail/folder **daripada ZIP yang sudah diekstrak** ke dalam folder `cloudstream-leque` ini, termasuk `.github` (jika Windows menyembunyikan folder bertitik, pastikan ia disalin).
5. Semak fail dan push:

   ```bash
   ls -la
   py -3 scripts/validate_repo.py
   git add .
   git commit -m "Initial Leque CloudStream repository"
   git branch -M main
   git push -u origin main
   ```

   Jika `py` tidak tersedia, guna `python` menggantikan `py -3`.

   *Jangkaan validator:* `PASS: repository format valid; 0 extensions indexed`. Ini normal kerana indeks masih kosong.

Jika `git commit` mengadu nama/email belum ditetapkan, jalankan `git config --global user.name "Nama Anda"` dan `git config --global user.email "email-GitHub-anda"` (gunakan alamat sebenar yang anda miliki). Jika Git meminta login semasa push, ikut login GitHub di browser/Git Credential Manager; jangan masukkan password GitHub biasa sebagai Git-over-HTTPS password.

**Alternatif tanpa Git CLI:** Pada laman repository kosong klik `uploading an existing file` (atau `Add file > Upload files`), drag-and-drop kandungan folder yang sudah diekstrak (bukan ZIP). Commit ke branch `main`. Pastikan nested folders `scripts/`, `docs/` dan `.github/workflows/` benar-benar wujud selepas upload. Jika browser gagal upload folder tersembunyi, guna Git Bash.

## 2. Uji manifest selepas push

Buka kedua-dua URL ini di browser:

- https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json
- https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/sumber.json

`repo.json` mesti memaparkan:

```json
{
  "name": "CloudStream Mod V5 by Leque",
  "description": "Repository ekstensi yang dikendalikan sendiri oleh Leque",
  "manifestVersion": 1,
  "pluginLists": [
    "https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/sumber.json"
  ]
}
```

`sumber.json` bermula sebagai `[]` (belum ada ekstensi). Di GitHub: tab `Actions` -> `Validate CloudStream repository` harus lulus. `Monitor listed extension files` boleh dijalankan manual, dan berjadual untuk memeriksa pautan **apabila** katalog sudah diisi.

## 3. Isi katalog dengan sumber yang dibenarkan

Memiliki repository sendiri tidak bermaksud memiliki fail `.cs3` di repo pihak lain. Senarai asal Bayu Stecu pula belum mempunyai lesen repo yang menjelaskan hak pengedaran semula. Semak kebenaran dan asal-usul setiap ekstensi sebelum copy metadata atau bina pautan semula.

**Opsyen manual yang paling selamat:** tambah entri ekstensi yang anda memiliki hak gunanya ke `sumber.json`, kemudian `py -3 scripts/validate_repo.py (atau python scripts/validate_repo.py jika `py` tiada)`, `git add sumber.json`, `git commit -m "Add reviewed extension catalog"`, `git push`.

**Opsyen import metadata pihak ketiga hanya jika dibenarkan:** dari root projek, jalankan `python scripts/import_bayu_index.py`. Ia menyalin **indeks metadata** sahaja dan meninggalkan pautan `.cs3` pada hos asal. NSFW dikecualikan secara default. Jalankan `python scripts/import_bayu_index.py --exclude-phisher` untuk tidak menyenaraikan fail yang dihos Phisher98 (termasuk MovieBox). Jangan import secara automatik tanpa review. Seterusnya semak katalog, jalankan `py -3 scripts/validate_repo.py (atau python scripts/validate_repo.py jika `py` tiada)`, dan commit/push semula.

## 4. Ujian dalam CloudStream asal sebelum ubah APK

1. Buka CloudStream -> Settings -> Extensions -> Add repository.
2. Paste `https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json`.
3. Pastikan repo `CloudStream Mod V5 by Leque` muncul.
4. Jika katalog masih `[]`, 0 ekstensi itu **betul**, bukan bug. Setelah anda tambah ekstensi yang dibenarkan dan publish, refresh dan semak senarai.
5. Untuk ekstensi yang sudah dipasang, semak Settings -> Updates & Backups -> Automatic plugin updates (nama menu mungkin berbeza mengikut versi).

## 5. Selepas repo lulus

Barulah sambung kepada APK baharu: bina dari kod sumber CloudStream (bukan APK App Cloner yang sedia ada), patch profil lalai menjadi `Mod v5 by Leque` dan pre-register URL repo di atas. Rujuk `docs/APK_INTEGRATION.md`.

**Had auto-update:** GitHub mengawal metadata; perubahan `sumber.json` tidak akan memasang plugin baharu sendiri. Untuk plugin sedia dipasang, versi `.cs3` baharu perlu sah, `internalName` kekal, `version` naik, hash dan saiz dikemas kini. Jika laman sasaran mati, kod plugin itu mesti dibaiki; tidak cukup menukar `repo.json`.
