<p align="center">
  <img src="docs/brand/scanme-logo-512.png" alt="ScanMe" width="128">
</p>

# ScanMe

Aplikasi Android document scanner yang menghasilkan gambar setara hasil scanner flatbed: deteksi tepi real-time, koreksi perspektif, dewarping, penghapusan bayangan, dan filter warna, lalu export ke PDF/JPG. Seluruh pipeline ditulis ulang dengan library open-source berlisensi permisif (Apache 2.0 / MIT / BSD).

> **Status:** Fase 3 sedang berjalan. Pipeline klasik di `:core:imaging` sudah lengkap (kecuali dewarp) dan teruji pada sampel sintetis. Layar kamera, editor, daftar dokumen, dan export masih placeholder. Detail: [`docs/03-phase3-progress.md`](docs/03-phase3-progress.md).

## Fitur

| Fitur | Status |
|---|---|
| Deteksi tepi dokumen (Canny + region, refinement sisi, scoring, fusi) | ✅ pipeline |
| Stabilisasi sudut antar frame (One-Euro) + state machine auto-capture | ✅ pipeline |
| Koreksi perspektif dengan estimasi rasio aspek (Zhang–He) | ✅ pipeline |
| Normalisasi iluminasi / hapus bayangan (Lab, masker objek, push-pull fill) | ✅ pipeline |
| Filter Original, Auto Color, Grayscale, B&W (Sauvola) | ✅ pipeline |
| Dewarping halaman melengkung (klasik, lalu ML) | ⏳ |
| Kamera live + overlay poligon + auto-capture + batch | ⏳ |
| Editor: crop 4 sudut + loupe, rotasi, slider intensitas filter | ⏳ |
| Daftar dokumen, rename, reorder, share, export PDF/JPG | ⏳ |
| Halaman debug output tiap tahap pipeline (build debug saja) | ⏳ |

## Tech stack

Kotlin 2.0, Jetpack Compose (Material 3), MVVM + Clean Architecture, Hilt, Coroutines/Flow, CameraX, OpenCV 4.9.0, Room, LiteRT (TFLite) untuk model opsional, `PdfDocument` untuk export.
minSdk 24, targetSdk/compileSdk 35. Versi lengkap ada di [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Struktur modul

| Modul | Isi |
|---|---|
| `:app` | `Application`, Hilt root, navigasi, inisialisasi OpenCV, layar About |
| `:core:imaging` | **Kotlin/JVM murni**: seluruh pipeline image processing, bisa diuji tanpa device |
| `:core:camera` | Wrapper CameraX, konversi `ImageProxy` → `Mat` |
| `:core:ml` | Implementasi LiteRT untuk antarmuka model di `:core:imaging` |
| `:core:domain` | Model `Document`/`Page`, use case, interface repository |
| `:core:data` | Room, penyimpanan file halaman, export PDF/JPG |
| `:core:ui` | Tema dan komponen Compose bersama |
| `:feature:scanner` | Layar kamera, live overlay, auto-capture, batch |
| `:feature:editor` | Crop manual, rotasi, filter |
| `:feature:documents` | Daftar dokumen dan export |
| `:feature:debug` | Visualisasi tiap tahap pipeline untuk tuning parameter (debug only) |

Konfigurasi build bersama ada di convention plugins [`build-logic/`](build-logic).

## Build & test

Prasyarat: JDK 17, Android SDK (API 35). Buat `local.properties` berisi `sdk.dir=<path Android SDK>` jika Android Studio belum membuatnya.

```bash
./gradlew assembleDebug          # APK debug
./gradlew test                   # semua unit test
./gradlew :core:imaging:test     # test pipeline saja
```

Di Windows gunakan `gradlew.bat`. Setelah `:core:imaging:test`, contact sheet visual per kategori sampel tersimpan di `core/imaging/build/test-output/pipeline_*.png`.

Catatan:
- APK debug berukuran besar (~139 MB) karena OpenCV native untuk 4 ABI. Build release membatasi ke `arm64-v8a` dan `armeabi-v7a`.
- Build release saat ini ditandatangani dengan keystore debug, khusus untuk uji lokal. Ganti dengan keystore rilis sebelum distribusi.
- OpenCV dipin ke 4.9.0 karena artefak desktop untuk test JVM (`org.openpnp:opencv`) hanya tersedia sampai versi itu.

## Dokumentasi

1. [Technique Analysis](docs/01-technique-analysis.md): analisis aplikasi referensi dan padanan open-source per tahap
2. [Arsitektur](docs/02-architecture.md): scope, stack, modul, desain pipeline
3. [Progres Fase 3](docs/03-phase3-progress.md): hasil test, temuan, keterbatasan, langkah berikutnya

Aturan kerja proyek untuk agent ada di [`AGENTS.md`](AGENTS.md).

## Lisensi pihak ketiga

Semua dependensi wajib berlisensi permisif. Daftar lengkapnya akan ditampilkan di layar About aplikasi. APK atau aset aplikasi referensi yang dipakai untuk analisis tidak boleh di-commit; `.gitignore` sudah memblokir file tersebut.
