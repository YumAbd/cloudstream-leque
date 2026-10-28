# Integrasi CloudStream Leque V5 — Android

Dokumen ini menerangkan konfigurasi aplikasi Leque selepas repository ekstensi sendiri disahkan. Repository ini mengandungi metadata ekstensi dan skrip, **bukan** kod sumber aplikasi Android atau APK siap pasang.

## Sasaran aplikasi

- Nama launcher: `CloudStream Leque V5`
- Nama profil lalai pemasangan bersih: `Mod v5 by Leque`
- Android application ID: `com.leque.cloudstreamv5`
- Repository pradaftar: `https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json`
- Tiada popup promosi yang ditambah oleh pihak Leque
- Tiada pemasangan automatik ekstensi yang tidak disemak

## Binaan daripada kod sumber

1. Dapatkan kod sumber rasmi CloudStream dalam folder pembangunan Android **berasingan**, bukan menyalinnya ke repository katalog ini.
2. Pasang Android Studio, Android SDK dan JDK/Gradle yang ditentukan oleh projek sumber.
3. Sahkan terlebih dahulu manifest Leque dan skrip validasi repository.
4. Dari folder repository ini, jalankan skrip patch terhadap checkout kod sumber yang bersih:

```bash
python scripts/patch_cloudstream_source.py --source "C:/Projects/cloudstream" --github-user YumAbd
```

5. Semak perubahan pada `RepositoryManager.kt`, `donottranslate-strings.xml`, `app/build.gradle.kts` dan `AndroidManifest.xml`. Skrip patch ini hanya membuat perubahan awal; ia bukan proses binaan lengkap dan mungkin perlu disesuaikan jika struktur kod sumber berubah.
6. Bina APK debug menggunakan Android Studio atau Gradle wrapper dan uji di Android sebenar.
7. Sahkan profil baharu, nama aplikasi, application ID, akses repository sendiri dan tiada popup daripada kod aplikasi.
8. Gunakan keystore persendirian yang konsisten untuk menandatangani release APK; simpan backup keystore di luar GitHub.

## Kemas kini APK dan ekstensi ialah dua perkara berasingan

**APK:** Saluran kemas kini aplikasi perlu menggunakan release Leque sendiri, atau dilumpuhkan sementara sehingga saluran tersebut tersedia. Jangan gunakan feed yang memasang aplikasi dengan package ID atau signing certificate berbeza.

**Ekstensi:** `sumber.json` digunakan untuk menyemak versi fail `.cs3` yang telah dipasang. Bila `internalName` kekal, `version` meningkat, dan muat turun lulus integriti, CloudStream boleh mengemas kini ekstensi tersebut semasa menyemak kemas kini. Fail baharu yang cuma disenaraikan tidak dipasang secara automatik.

**Sumber tontonan:** Jika sesuatu laman atau API dalam ekstensi berhenti berfungsi, kod ekstensi perlu dikemas kini dan diuji; perubahan `repo.json` sahaja tidak menyelesaikan masalah.

## Pemeriksaan sebelum pengedaran

- Uji pemasangan bersih dan permulaan pertama.
- Uji nama profil `Mod v5 by Leque` serta kebolehan pengguna mengedit profil kemudian.
- Uji repository dan satu fail ekstensi yang benar-benar boleh dimuat.
- Uji keadaan offline, pautan rosak, hash salah dan percubaan rollback versi.
- Uji fungsi auto-update ekstensi tanpa memaksa pemasangan ekstensi baharu.
- Pastikan popup promosi tidak dibawa masuk oleh kod atau ekstensi pilihan.
- Sahkan signature, versionCode, application ID, dan proses kemas kini APK.
- Simpan nota perubahan, versi dan bukti ujian.

## Lesen dan hak penggunaan

Kod sumber aplikasi CloudStream menggunakan lesen GPL-3.0. Sebarang pengedaran APK terbitan mesti mengekalkan notis hak cipta, kredit dan syarat lesen termasuk penyediaan kod sumber ubah suai yang berkenaan. Lesen dan atribusi bagi ekstensi juga perlu dihormati. Menghapuskan nama atau pautan import katalog daripada repository Leque **tidak** memberi hak untuk menghilangkan kredit wajib pada kod yang benar-benar digunakan.

## Playlist IPTV

`cloudstream.m3u8` bermula sebagai header kosong `#EXTM3U`. Ia tidak dipasang atau dibaca automatik oleh CloudStream; jika hendak digunakan, anda memerlukan ekstensi berkenaan dan kandungan yang dibenarkan.
