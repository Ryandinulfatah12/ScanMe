# Fase 3 — Progres: scaffolding + pipeline klasik

Status per 19-10-2026. Langkah 1–2 dari urutan kerja Fase 3 (`02-architecture.md` §12) selesai.

## 1. Yang sudah ada

| Bagian | Isi |
|---|---|
| Build | Gradle 8.10.2 wrapper, version catalog, convention plugins (`build-logic`), 11 modul sesuai §3 arsitektur. `./gradlew assembleDebug` dan `./gradlew test` hijau. |
| `:core:imaging` | Pipeline klasik lengkap (tanpa dewarp): deteksi quad (2 profil Canny + region) → refinement sisi (fit garis Huber) → scoring → fusi; One-Euro smoother, motion estimator, state machine auto-capture; koreksi perspektif dengan estimasi rasio aspek (Zhang–He); normalisasi iluminasi di Lab dengan deteksi objek + push-pull fill; filter Original / Auto Color / Grayscale / B&W (Sauvola). |
| `:core:data` | Room (entitas dokumen & halaman, DAO, database), modul Hilt. |
| `:core:camera` | `ImageProxy.lumaToMat()` (plane Y → Mat tanpa konversi warna). |
| `:core:domain` | Model `Document`/`Page`, `DocumentRepository`. |
| `:app` + feature | Hilt, inisialisasi OpenCV, navigasi, layar placeholder untuk scanner/editor/documents/debug. |

## 2. Hasil test (29 test, semua lulus)

Sampel: generator scene **sintetis** (6 kategori, foto A4 diproyeksikan kamera pinhole, ada ground truth sudut & tinta). Belum ada foto nyata.

| Kriteria (AGENTS.md) | Target | Hasil sintetis |
|---|---|---|
| Error sudut deteksi (datar, bayangan, kusut, background ramai, kontras rendah, buku) | ≤ 2% diagonal | **0,04–0,34%** (3 seed per kategori) |
| Waktu deteksi @640 px | < 50 ms (device mid-range) | 6–13 ms di desktop JVM (indikatif; verifikasi di device belum) |
| Latar kertas Auto | L* > 90 | **L* 100** di semua kategori; < 2% piksel kertas di bawah L* 85 pada scene bayangan |
| Teks tipis tidak hilang | — | 92% piksel teks tipis tetap lebih gelap dari L* 75 (Auto); recall 88% (B&W) |
| Warna stempel/logo natural | — | stempel merah a=187 (netral 128); logo biru tetap gelap (L* 33) dan biru (b=75) |
| B&W | — | recall tinta 100%, noise di kertas 0% |
| Rasio aspek | — | tepat (< 0,5%) dengan focal length dari metadata kamera; < 2% self-calibrated; < 3% dengan prior |
| Deviasi baseline dewarp | < 2% tinggi halaman | **belum** (dewarp = langkah 6) |
| Pipeline penuh < 2 s | device mid-range | ~70–120 ms per filter di desktop untuk halaman ~820×1160 px; perlu diukur ulang di resolusi kamera penuh dan di device |

Contact sheet visual per kategori: `core/imaging/build/test-output/pipeline_*.png` (dibuat ulang setiap `./gradlew :core:imaging:test`).

## 3. Temuan & keputusan selama implementasi

1. **OpenCV dipin ke 4.9.0** (Android AAR dan desktop untuk test). Artefak desktop `org.openpnp:opencv` hanya sampai 4.9.0. Upgrade ke 4.10+ butuh sumber native desktop lain (mis. bytedeco) atau test instrumented.
2. **Rasio aspek butuh focal length.** Rumus Zhang–He tidak bisa menghitung focal saat kamera hanya pitch/yaw murni. Solusi: `ProcessRequest.focalLengthPx` diisi dari metadata kamera (CameraX/Camera2); fallback self-calibration, lalu prior 0,8 × sisi panjang. Untuk impor galeri: pakai EXIF `FocalLengthIn35mmFilm` bila ada.
3. **Logo/area gelap besar sempat terhapus jadi abu-abu** oleh normalisasi iluminasi. Diperbaiki dengan masker objek: region yang dikelilingi tepi tajam dan lebih gelap dari kertas sekitarnya dikeluarkan dari estimasi latar, lalu diisi push-pull. Test regresi ditambahkan.
4. **Bias sudut dari hull** (2–4 px) membuat kandidat yang benar kalah skor pada background ramai. Diperbaiki dengan `QuadRefiner` (fit garis per sisi) + pencarian edge tegak lurus di scorer.
5. **B&W**: Sauvola membuat area gelap besar berlubang; ditambah aturan "lebih gelap dari L* ~43 = pasti tinta".

## 4. Keterbatasan yang diketahui

- Bayangan bertepi keras (matahari langsung) dianggap objek sehingga tidak dihilangkan.
- Sisa bayangan sangat tipis di tepi halaman pada area bayangan tergelap (median kertas tetap L* 100).
- APK debug 138,7 MB karena OpenCV native untuk 4 ABI. Untuk rilis: distribusi AAB atau `abiFilters` (arm64-v8a, armeabi-v7a).
- Semua angka di atas dari sampel sintetis; wajib divalidasi dengan foto nyata.

## 5. Langkah berikutnya

3. `:feature:debug` — tampilan tiap tahap pipeline + slider parameter (tuning).
4. Kamera live + auto-capture (`:core:camera`, `:feature:scanner`), termasuk focal length dari metadata.
5. Editor, daftar dokumen, export PDF/JPG.
6. Dewarp klasik (opsi A), lalu ML setelah lisensi terverifikasi.
