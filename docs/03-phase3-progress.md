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
| Deviasi baris teks dewarp | < 2% tinggi halaman | **0,27–0,29%** (sebelum dewarp 3,3–3,8%; curl buku non-polinomial, 3 seed; diukur dari label ground truth per baris). Estimasi 10–17 ms di desktop |
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

## 5. Pembaruan 23-11-2026

- **Langkah 3 selesai** — `:feature:debug` (hanya build debug, ikon bug di Home): foto galeri → semua tahap `DebugCollector` berdampingan, waktu per tahap, skor deteksi, median L\* kertas (target > 90), slider parameter filter.
- **Langkah 4 selesai** — kamera live, auto-capture, crop, review multi-halaman, focal length dari metadata (sudah ada sebelumnya, belum tercatat di sini).
- **Langkah 5 sebagian** — export PDF + share sudah ada; layar editor (loupe, slider intensitas) dan daftar dokumen tersimpan (Room) belum.
- **Langkah 6 (opsi A) selesai** — `TextLineDewarper`:
  1. adaptive threshold (window 3% lebar, C 15) → closing horizontal 2,5% lebar (lebih lebar dari spasi kata, tinggi 1 px agar baris tidak menyatu) → opening 1×3 (memutus jembatan descender/ascender);
  2. komponen lebar ≥ 8% dan tebal rata-rata ≤ 3,5% tinggi = baris (garis tabel ikut dipakai);
  3. sampel centroid tinta per kolom (~1/60 lebar);
  4. least squares medan pergeseran vertikal D(x,y) = Σ a·(xʲ − 1/(j+1))·yᵏ, j = 1..3, k ≤ 2 (derajat y turun bila baris hanya menutup sebagian tinggi), satu putaran buang outlier > 3×RMS;
  5. D dievaluasi di grid 1/8, di-upsample, lalu satu `remap`.
  Halaman dibiarkan apa adanya bila < 3 baris, lengkung < 0,4% (halaman datar), atau > 15% (fit tidak masuk akal). Toggle "Lurus" di layar review (default on), ikut ke export PDF.
  Keterbatasan: hanya koreksi vertikal — kompresi horizontal dekat punggung buku tetap (opsi B / model 3D bila perlu); beberapa piksel artefak border di tepi atas halaman yang tepinya melengkung; kertas kusut lokal tidak terkoreksi (polinomial global).

- **Langkah 5 selesai** —
  - Dokumen tersimpan: `RoomDocumentRepository` (Room + foto di `filesDir/documents/<id>/`). "Simpan" menulis ulang semua halaman dalam satu transaksi; foto dari cache kamera disalin masuk, foto yang tidak dipakai lagi dihapus setelah commit. Editor hanya menghapus file cache miliknya sendiri, jadi menutup editor tanpa menyimpan tidak pernah merusak dokumen tersimpan.
  - Home: daftar dokumen (thumbnail, jumlah halaman, tanggal), buka untuk diedit, ganti nama, hapus (dengan konfirmasi).
  - Editor (layar review): slider intensitas filter, pindah urutan halaman (‹ ›), simpan JPG per halaman ke Pictures/ScanMe (tanpa kompres ulang), tombol Simpan; menutup dengan perubahan belum disimpan → dialog Simpan/Buang.
  - Layar About: versi + daftar lisensi pustaka yang ikut di APK (semua Apache 2.0).
  - Skema Room berubah (kolom `photoRotation`, `thumbnailPath`) tanpa menaikkan versi DB: belum pernah ada yang menulis ke DB sebelumnya. Mulai sekarang perubahan skema butuh migrasi.

## 5b. Uji di HP (23-11-2026, Samsung Galaxy A36 / SM-A366B, Android 17)

| Kriteria | Hasil |
|---|---|
| Deteksi live < 50 ms/frame | 640 px: 54–81 ms (debug), **49–65 ms (rilis)** dengan kertas di frame. Diturunkan ke 576 px untuk live (foto tetap 640 px): **40–53 ms (rilis)**. Masih sesekali di atas 50 ms; langkah lanjut: profil per tahap, baseline profile |
| Pipeline penuh < 2 s | 2308×2860 px: warp+dewarp 156–230 ms, filter 290–610 ms (preview 1600 px) → **< 1 s** |
| Deteksi kertas nyata | Outline terkunci, "Dokumen terpotong" dan auto-capture bekerja |
| Alur dokumen | Simpan → daftar → buka lagi (filter/intensitas utuh) → dialog Simpan/Buang → Buang tidak menghapus foto tersimpan ✔ |

Diperbaiki dari uji ini:
- Dokumen yang dibuka ulang selalu menampilkan "Tepi tidak terdeteksi".
- Foto cache dari sesi yang prosesnya dimatikan tidak pernah dihapus (43 MB ditemukan); sekarang dibersihkan saat scanner dibuka.
- Kamera (preview + analisis 30 fps) tetap menyala di bawah review/crop dan saat membuka dokumen tersimpan; sekarang hanya aktif saat layar kamera tampil. Thread callback kamera bocor setiap kunjungan; sekarang dimatikan.
- Artefak garis vertikal di tepi atas/bawah hasil dewarp pada foto nyata; pergeseran sekarang diredam ke nol di tepi.

Belum diperbaiki: APK **tidak 16 KB page-aligned** (`libopencv_java4.so` 4.9.0, `libc++_shared.so`; CameraX/androidx.graphics "unknown"). Android 17 menampilkan peringatan; Google Play mewajibkan 16 KB untuk target Android 15+. Butuh OpenCV versi yang 16 KB-aligned, yang bentrok dengan pin 4.9.0 untuk test desktop.

## 6. Langkah berikutnya

1. Validasi dengan foto nyata (buku, bayangan, kusut, background ramai) lewat halaman debug; ukur waktu di device mid-range.
2. Uji alur dokumen di HP: scan → simpan → buka lagi → ubah urutan/filter → simpan; hapus dokumen.
3. Dewarp ML (opsi B) setelah lisensi model terverifikasi.
4. OCR (opsional, ML Kit).
