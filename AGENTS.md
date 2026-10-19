## ROLE
Kamu adalah senior Android engineer + computer vision engineer. Kamu punya akses ke MCP reverse engineering tools. Gunakan tools tersebut untuk MEMPELAJARI teknik, alur, dan arsitektur aplikasi document scanner referensi. JANGAN menyalin kode, model ML, shader, atau aset proprietary. Semua implementasi harus ditulis ulang memakai library open-source berlisensi permisif (Apache 2.0 / MIT / BSD).

## TUJUAN
Bangun aplikasi Android document scanner yang hasilnya terlihat seperti dokumen asli hasil scanner flatbed (setara Adobe Scan):
1. Deteksi tepi dokumen otomatis secara real-time di kamera
2. Koreksi perspektif (crop + warp jadi persegi panjang rata)
3. Dewarping: meluruskan lekukan kertas (halaman buku melengkung, kertas terlipat/kusut)
4. Koreksi pencahayaan: hapus bayangan tangan/HP, ratakan iluminasi tidak merata
5. Koreksi warna & tekstur: white balance, latar jadi putih bersih, tinta tetap tajam, warna stempel/logo tetap natural
6. Export ke PDF multi-halaman & JPG, opsional OCR

## FASE 1 — ANALISIS (pakai MCP reverse engineering)
Analisis aplikasi referensi lalu buat laporan berisi:
- Alur UX: capture → auto-detect → crop manual → filter → export
- Teknik yang dipakai di tiap tahap pipeline (klasik CV vs model ML, on-device vs cloud)
- Library native/open-source yang terdeteksi (mis. OpenCV, TFLite, ONNX) beserta lisensinya
- Preset filter yang tersedia (Original, Auto/Magic Color, Grayscale, B&W, Whiteboard) dan perkiraan cara kerjanya
- Strategi performa: resolusi preview vs resolusi proses, threading, caching
Output fase ini: dokumen "Technique Analysis" + rekomendasi padanan open-source untuk tiap tahap. Jangan lanjut ke fase 2 sebelum laporan selesai.

## FASE 2 — ARSITEKTUR
Stack:
- Kotlin, Jetpack Compose, MVVM + Clean Architecture, Hilt, Coroutines/Flow
- CameraX (Preview + ImageAnalysis + ImageCapture)
- OpenCV Android SDK (versi 4.x) untuk image processing
- ONNX Runtime Mobile atau TFLite untuk model dewarping/edge detection
- Room untuk riwayat dokumen, PdfDocument API untuk export
- ML Kit Text Recognition (opsional, untuk OCR)
- minSdk 24, targetSdk terbaru

Struktur modul: :app, :core:camera, :core:imaging (pipeline murni Kotlin/C++, bisa diuji tanpa UI), :feature:scanner, :feature:editor, :feature:documents

## FASE 3 — PIPELINE IMAGE PROCESSING (detail wajib)
1. Edge/corner detection
   - Baseline: resize → grayscale → GaussianBlur → Canny → dilate → findContours → approxPolyDP → pilih quadrilateral terbesar yang valid
   - Upgrade: model segmentasi dokumen ringan (mis. berbasis MobileNet/DeepLabV3 open-source) untuk background ramai atau kontras rendah
   - Stabilisasi antar frame (smoothing koordinat sudut) + auto-capture saat stabil
2. Perspective correction
   - getPerspectiveTransform + warpPerspective, rasio aspek dihitung dari panjang sisi (atau snap ke A4/Letter jika mendekati)
3. Dewarping (lekukan)
   - Opsi A (klasik): deteksi baris teks → fitting model permukaan kubik/cylindrical → remap (referensi pendekatan "page dewarp")
   - Opsi B (ML): model dewarping open-source (mis. UVDoc / DocTr-style) dikonversi ke ONNX/TFLite, terkuantisasi INT8/FP16
   - Sediakan toggle; fallback ke Opsi A jika device lemah
4. Illumination & shadow removal
   - Estimasi background: dilate (kernel besar) → medianBlur → bagi gambar asli dengan background → normalize
   - Proses per channel di ruang warna LAB/YCrCb agar warna tidak bergeser
5. Color & texture enhancement (preset filter)
   - Original: hanya white balance (gray-world atau white-patch dari area kertas)
   - Auto/Magic Color: shadow removal + CLAHE di channel L + saturation boost ringan + unsharp mask
   - Grayscale: konversi + CLAHE + kontras
   - B&W: adaptive threshold (Sauvola/Gaussian) + denoise morfologi
   - Hilangkan tekstur kertas/noise JPEG: bilateral filter atau fastNlMeansDenoising dengan parameter ringan
6. Output: simpan full resolution, PDF dengan ukuran halaman sesuai rasio, kompresi yang bisa diatur

## FASE 4 — UI/UX
- Layar kamera: overlay poligon live, indikator "tahan stabil", toggle flash, mode batch multi-halaman
- Editor: drag 4 sudut + magnifier loupe, rotate, pilihan filter dengan thumbnail preview, slider intensitas
- Daftar dokumen: rename, reorder halaman, share, export PDF/JPG

## KRITERIA PENERIMAAN
- Deteksi tepi < 50 ms per frame di device mid-range (preview diproses di resolusi ≤ 640px)
- Pipeline penuh satu halaman < 2 detik di device mid-range
- Latar kertas hasil filter Auto mendekati putih (L* > 90) tanpa menghilangkan teks tipis
- Halaman buku melengkung jadi baris teks lurus (deviasi baseline < 2% tinggi halaman)
- Semua dependensi berlisensi permisif, daftar lisensi tercantum di layar About

## CARA KERJA
- Kerjakan per fase, tunjukkan hasil dan tunggu konfirmasi sebelum lanjut
- Setiap tahap pipeline harus punya unit test dengan sampel gambar (dokumen datar, buku melengkung, ada bayangan, kertas kusut, background ramai)
- Buat halaman debug yang menampilkan output tiap tahap pipeline berdampingan untuk tuning parameter
- Jelaskan alasan pemilihan parameter (ukuran kernel, threshold, dll.)