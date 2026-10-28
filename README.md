# CloudStream Leque V5 — Repository

Repository rasmi projek Leque: https://github.com/YumAbd/cloudstream-leque

**Alamat pemasangan:** https://raw.githubusercontent.com/YumAbd/cloudstream-leque/main/repo.json

Repository ini dikendalikan secara bebas oleh Leque. Tiada import automatik atau sambungan kepada katalog repository lain. Kod aplikasi Android dan APK akan diuruskan secara berasingan.

## Komponen

- `repo.json` — manifest CloudStream yang menunjuk hanya kepada katalog Leque.
- `sumber.json` — indeks ekstensi. Bermula dengan `[]`, iaitu tiada ekstensi.
- `extensions/` — fail `.cs3` yang dibina/diedarkan dengan kebenaran sah; folder akan wujud selepas penambahan pertama.
- `cloudstream.m3u8` — fail playlist kosong pilihan; tidak dimuat secara automatik oleh CloudStream.
- `scripts/add_plugin.py` — daftar fail ekstensi baharu yang akan dihos oleh repository Leque.
- `scripts/replace_plugin.py` — kemas kini fail ekstensi sedia ada dan naikkan versinya.
- `scripts/validate_repo.py` — semak format, identiti repository dan URL muat turun.
- `scripts/check_links.py` — uji URL muat turun dan hash fail yang diterbitkan.
- `.github/workflows/` — validasi semasa push dan pemantauan fail secara berkala.

## Status semasa

Manifest dan katalog telah disediakan. `sumber.json` masih kosong. Ini normal dan tidak bermaksud ekstensi telah dipasang secara automatik. **Jangan mengisi katalog dengan fail yang hak pengedaran atau keselamatannya belum disahkan.**

## Cara menambah ekstensi sendiri

Pastikan fail `.cs3` adalah build yang sah, dibenarkan untuk diedarkan, dan sepadan dengan `internalName` ekstensi.

```bash
python scripts/add_plugin.py --file "./build/Example.cs3" --internal-name Example --name Example --author "Pemilik Sebenar" --version 1 --language ms
python scripts/validate_repo.py
git add sumber.json extensions/
git commit -m "Add reviewed extension"
git push
```

Skrip akan menyalin fail ke `extensions/Example.cs3`, mengira SHA-256, dan menambah metadata di `sumber.json`. Semua URL fail yang diterbitkan menunjuk kepada repository Leque sendiri.

## Cara mengemas kini ekstensi yang sudah dipasang

```bash
python scripts/replace_plugin.py --file "./build/Example.cs3" --internal-name Example --version 2
python scripts/validate_repo.py
git add sumber.json extensions/
git commit -m "Update reviewed extension"
git push
```

Kekalkan `internalName` dan **naikkan `version`** setiap kali kod berubah. Aplikasi yang telah memasang ekstensi itu boleh menyemak versi baharu apabila online dan auto-update ekstensi aktif. Kemas kini tidak semestinya berlaku serta-merta; ekstensi baharu juga tidak dipasang secara automatik hanya kerana ia disenaraikan.

## Validasi dan pemantauan

```bash
python scripts/validate_repo.py
python scripts/check_links.py
```

Semak tab [Actions](https://github.com/YumAbd/cloudstream-leque/actions). Workflow validasi berjalan pada perubahan repository; pemantauan pautan berjalan secara berjadual atau manual. Jika sesuatu fail rosak, workflow memberi amaran dan **tidak** menggantikan atau menjalankan kod secara automatik.

## Membina APK Leque

Lihat [panduan integrasi Android](docs/APK_INTEGRATION.md). Sasaran:

- Nama aplikasi: **CloudStream Leque V5**
- Nama profil lalai: **Mod v5 by Leque**
- Repository siap tambah: **YumAbd/cloudstream-leque**
- Application ID berasingan: `com.leque.cloudstreamv5`
- Tandatangan APK menggunakan kunci milik Leque, bukan kunci aplikasi lain

Kod aplikasi asal CloudStream tertakluk kepada lesen GPL-3.0. Jika membina dan mengedarkan aplikasi terbitan, kekalkan notis hak cipta, kredit dan kewajipan lesen yang berkaitan. Repository ini tidak mengandungi kod aplikasi rasmi tersebut.

## Had pembaikan melalui GitHub

- URL atau manifest katalog rosak: boleh dibaiki di repository Leque jika URL induk kekal hidup.
- Fail `.cs3` rosak: perlu sediakan build baharu yang diuji, kemudian tingkatkan `version`.
- Laman yang diakses oleh ekstensi berubah: kod ekstensi mesti dibaiki; edit JSON sahaja tidak mencukupi.
- Aplikasi Android sendiri berubah: perlu bina dan tandatangani APK baharu.
- Jangan simpan keystore, token rahsia, kata laluan atau data peribadi dalam repository Public.
