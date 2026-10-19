# Fase 1 — Technique Analysis: Adobe Scan (Android)

> Tujuan dokumen: mempelajari **teknik, alur, dan arsitektur** aplikasi referensi untuk merancang implementasi sendiri. Tidak ada kode, model, bobot, shader, atau aset proprietary yang disalin. Yang dicatat hanya nama komponen, bentuk tensor, jenis operator, dan perilaku yang teramati.

## 0. Ruang lingkup & metode

| Item | Nilai |
|---|---|
| Artefak | `com.adobe.scan.android_21.03.20-regular-211142876_minAPI23(arm64-v8a,armeabi-v7a)(nodpi).apk` |
| SHA-256 | `b23d10b35d6fd203c14abf45634c43c1774a59643a86399a8c79bb2192126789` |
| Versi | 21.03.20 (Maret 2021), minSdk 23, ABI arm64-v8a + armeabi-v7a |
| Alat | REA 6.5/6.7 (`open_binary`, `inspect_artifact`, `extract_artifact`) + skrip statis read-only (ekstraksi string, parser header flatbuffer TFLite) |
| Cakupan | 5.300 entri ZIP diinventaris lengkap; 2 DEX dan 10 library native (per ABI) dipindai string-nya; 4 model TFLite tertanam diparse strukturnya (tanpa membaca bobot) |

**Label keyakinan** yang dipakai di dokumen ini:
- **[T] Teramati** — langsung terlihat di artefak (nama kelas, string, bentuk tensor, operator).
- **[D] Disimpulkan** — inferensi dari beberapa bukti; masuk akal tapi belum diverifikasi lewat dekompilasi/runtime.

**Batasan:** analisis statis saja (aplikasi tidak dijalankan). JADX belum dikonfigurasi, jadi isi method Java/Kotlin (nilai threshold, resolusi, timing) **tidak** terbaca — kode app juga di-obfuscate R8 sebagian. Versi APK dari 2021; versi terbaru Adobe Scan kemungkinan sudah berbeda (lihat §9).

---

## 1. Inventaris komponen

### 1.1 Library native (`lib/arm64-v8a`)

| Library | Ukuran | Peran | Asal / lisensi |
|---|---|---|---|
| `libMagicClean.so` | 11,6 MB | **Inti pipeline**: deteksi dokumen, klasifikasi, deteksi batas/sudut, crop+warp, shadow removal, cleaning. Berisi 4 model TFLite tertanam + kernel Halide. [T] | Adobe, proprietary |
| `libtensorflowlite.so` | 2,2 MB | Runtime inferensi; dipanggil dari native (tidak dari Java). [T] | TensorFlow Lite — Apache 2.0 |
| `libtensorflowlite_gpu_gl.so` | 1,6 MB | GPU delegate (OpenGL ES). [T] | Apache 2.0 |
| `libScan2PDF.so` | 1,7 MB | Penulis PDF sendiri: font CID, layer teks OCR tersembunyi (`/HiddenHorzOCR`, `/HiddenVertOCR`), `OCRGraphicLineFinder`. [T] | Adobe, proprietary |
| `libpdfviewer.so` | 27,8 MB | Viewer PDF (pratinjau). [T] | Adobe, proprietary |
| `libspectrum*.so` (4 file) | ~1,5 MB | Encode/decode JPEG, rotate/crop lossless (`LibJpegCompressor`, `LibJpegLosslessRotateAndCropRecipe`). [T] | Facebook Spectrum — MIT (+ libjpeg, lisensi IJG) |
| `libc++_shared.so` | 0,9 MB | C++ runtime NDK. [T] | Apache 2.0 w/ LLVM exception |

**Tidak ditemukan OpenCV** di APK ini. [T] Pemrosesan citra klasik ditulis sendiri oleh Adobe dengan **Halide** (MIT), yaitu DSL yang mengompilasi pipeline citra menjadi kode NEON dengan thread pool. Generator Halide yang teramati [T]:
`Gray`, `Blur`, `Dilate`, `Gradient`, `Gradient3Channels`, `Histogram`, `StretchLChannelHistogram`, `Interpolation_Int`.

Tipe bitmap internal [T]: `CRGBABitmap`, `CALABBitmap` (Lab), `CLBitmap` (channel L), `CYBitmap` (luma Y), `CMonoBitmap`, plus `CThreadedBitmapOperation`, yang menunjukkan operasi per-tile yang paralel.

### 1.2 Stack Java/Kotlin (dari DEX + `META-INF`)

| Komponen | Versi | Catatan |
|---|---|---|
| CameraX core/camera2/lifecycle/view | 1.0.0-beta12 | Jalur kamera utama `CameraXCaptureActivity`; masih ada fallback `Camera2PreviewController` dan `Camera1PreviewController`. [T] |
| Kotlin + kotlinx-coroutines | — | Masih bercampur dengan `AsyncTask` (`LiveEdgeDetectorAsyncTask`, `LocalCropAndCleanAllPagesAsyncTask`). [T] |
| Room (room-ktx) | — | Penyimpanan lokal. [T] |
| ML Kit Text Recognition | play-services-mlkit-text-recognition 16.1.1 | OCR on-device (`MobileOCREngine`). [T] |
| Play Services Vision | 20.1.2 | Legacy Mobile Vision. [T] |
| Adobe Sensei SDK (native) | — | Wrapper model: metadata JSON, `TFLiteDeviceManager`, enkripsi model (tidak aktif: `"key": "no key required"`). [T] |
| OkHttp, Firebase, Adobe Creative SDK, DC API | — | Jaringan, analitik, akun/cloud. [T] |

---

## 2. Model ML tertanam (struktur saja)

Keempat model disimpan **di dalam** `libMagicClean.so` (tidak ada di `assets/`), masing-masing dengan metadata JSON Sensei. Semua dijalankan pada resolusi kecil.

| Nama (metadata) | Input | Output | Operator utama | Arsitektur (inferensi) | Tipe |
|---|---|---|---|---|---|
| **dd** — Doc Detection (`add_v002`) | 1×160×160×3 float | `[1,2]` softmax | 14 CONV, 13 DEPTHWISE_CONV, 1 FC | Klasifier ala MobileNet: *ada dokumen / tidak*. [D] | Bobot **INT8** + DEQUANTIZE (kuantisasi dynamic-range) [T] |
| **dc** — Doc Classification (`adc_v002`) | 1×384×384×3 float | `[1,5]` softmax `prob` | 33 CONV, 14 CONCAT, 3 MAXPOOL | Pola squeeze → expand 1×1/3×3 → concat seperti **SqueezeNet (fire module)**. [D] Kelas: Whiteboard, Receipt, Form, BusinessCard, Other. [T] | FP32 |
| **BD** — Boundary Detection (`abd_v002`) | 1×256×256×3 float | 2× peta `Sigmoid` 256×256×1 + 1 tensor 256×256×3 | 30 CONV, 13 DEPTHWISE, 8 RESIZE_BILINEAR, SPACE_TO_BATCH/BATCH_TO_SPACE | Encoder **MobileNetV2** (`expanded_conv_*`) + konvolusi atrous + dekoder upsampling bilinear ≈ **DeepLabV3+ ringan**. Dua peta sigmoid kemungkinan **mask dokumen** dan **peta tepi/sudut**. [D] | FP32 |
| **SR** — Shadow Removal (`asr_v001`) | 1×256×256×3 float | 1×256×256×3 uint8 `output_shadow_image` | 9 CONV, 8 TRANSPOSE_CONV, 8 CONCAT, normalisasi (MEAN/RSQRT), leaky-ReLU via ABS | **U-Net generator gaya pix2pix** (`generator/encoder_1..8`, `decoder_1..8`, skip-connection). [T] Kecil (~0,9 MB), jadi jumlah channel dikurangi. [D] | FP32 |

Inferensi dari ukuran I/O [D]: SR hanya beroperasi di 256×256, sehingga hampir pasti dipakai untuk **memperkirakan peta iluminasi/bayangan resolusi rendah** yang lalu di-upsample dan diterapkan ke gambar resolusi penuh (bukan untuk menghasilkan gambar final langsung).

---

## 3. Alur UX (capture → auto-detect → crop → filter → export)

Disusun dari nama Activity/kelas, enum, dan event analitik `DCMScan:*`.

1. **Capture** — `CameraXCaptureActivity` [T]
   - Mode capture (pemilih horizontal `CaptureModeRecyclerView`): **Document, Whiteboard, Form, Business Card** (+ Receipt di enum native). [T]
   - **Live edge detection** dengan overlay quad (`LiveEdgeDetector`, `ATTRIBUTE_QUAD_LIVE_BOUNDARY`). [T]
   - **Auto-capture** saat stabil (`AUTO_CAPTURE_TOGGLE`, `EVENT_STABLE_TIME_INTERVAL`, `WHITEBOARD_AUTO_CAPTURE_TIMEOUT_MS`). Gerakan perangkat diukur dua cara: sensor (`DEVICE_MOVEMENT_ACCELARATION_THRESHOLD`, `DEVICE_MOVEMENT_ROTATION_THRESHOLD`) dan **deteksi gerak berbasis frame** (`YUV420MotionDetection`). Live edge di-restart saat bergerak (`NUMBER_OF_LIVE_EDGE_RESTART_DUE_TO_MOVEMENT`). [T]
   - Petunjuk kualitas: `imageHasGlareAfterTorch` (silau setelah flash), `imageHasDarkShadow`, deteksi "bukan dokumen" dari model **dd**. [T]
   - **Crop-in-capture**: setelah jepret, sudut bisa langsung dikoreksi di layar kamera (`CropInCaptureContainerExpander`, beberapa varian eksperimen A/B). [T]
   - Impor dari galeri (`DCMScan:Capture:Library`). [T]
2. **Crop manual** — `CropActivity` / `ImageCropView` [T]
   - Quad hasil deteksi otomatis sebagai awal (`autoDetectedCrop`), handle sudut yang bisa digeser (`CropHandle`), loupe, rotasi beranimasi, peringatan "crop too small", deteksi crop buruk (`BAD_CROP_*`). [T]
   - `findAllEdges` mengembalikan **beberapa kandidat quad** (Live / HC / LC / NN). [T]
3. **Filter / Clean** — `EditorActivity` + `cleanOptionsDialog` [T]
   - Preset: **Original photo**, **Original color**, **Auto color** (`clean_options_color_document`), **Grayscale**, **Whiteboard**. [T]
   - Thumbnail tiap preset dirender dan di-cache per halaman (`mGrayscaleThumbImageRendition`, `mWhiteboardThumbImageRendition`, lazy delegate). [T]
   - "Terapkan ke semua halaman" (`APPLY_CLEANING_OPTION_TO_ALL_PAGES`), dengan level kebersihan (`CLEAN_LEVEL`, `mCleaningLevel`). [T]
4. **Cleanup / Eraser** — `EraserActivity` [T]
   - Kuas penghapus noda: *fill with surrounding color* atau *fill with selected color*, eyedropper, swatch warna terakhir, undo/redo. Native: `SmartEraserAndroidShim`. [T]
5. **Halaman** — reorder, rotate, resize/ukuran halaman (`ResizeActivity`, `PageSizeAdapter`). [T]
6. **Export** — `CreatePdfActivity` → `PDFCreation` + `libScan2PDF` [T]
   - PDF multi-halaman dengan **layer teks OCR tersembunyi** (PDF bisa dicari), simpan sebagai JPG, kualitas kompresi JPEG (`DEFAULT_JPEG_COMPRESSION_QUALITY`). [T]
   - OCR: **on-device ML Kit** lebih dulu, **fallback ke OCR server** Adobe Document Cloud (`ON_DEVICE_OCR_FALL_BACK_TO_SERVER_OCR`). [T]
   - Quick actions dari OCR: ekstraksi email/telepon/website, saran nama file dari judul (`MobileOCRFileNameProcessor`). [T]

---

## 4. Teknik per tahap pipeline

| Tahap | Teknik di Adobe Scan 21.03 | Klasik / ML | Lokasi |
|---|---|---|---|
| Gating "ada dokumen?" | CNN **dd** 160×160 (biner). [T] | ML | On-device |
| Klasifikasi jenis dokumen | CNN **dc** 384×384 (5 kelas). Hasilnya memilih **parameter per jenis dokumen** (`BDParamsDocTypeDocument/Form/WhiteBoard/BusinessCard`, `BDBinarizationParamsDocType`). [T] | ML → parameter klasik | On-device |
| Deteksi tepi/sudut | **Hibrida** dengan tiga mode workflow: `kWorkflowNN`, `kWorkflowHeuristics`, `kWorkflowCombined`. [T] Heuristik: dua profil `BDParamsAlgoHC` / `BDParamsAlgoLC` (kemungkinan *high/low contrast*) [D] memakai kernel Halide Gray → Blur → Gradient/Gradient3Channels → Dilate → binarisasi. [D] NN: model **BD** (segmentasi + peta tepi). Lalu `BDParamsAlgoFusion` menggabungkan kandidat. [T] Live vs post-capture dibedakan (`kCaptureTypeLive` / `kCaptureTypePost`). [T] | Klasik + ML + fusi | On-device |
| Stabilisasi & auto-capture | Sensor akselerometer/rotasi + `YUV420MotionDetection` pada frame preview (luma). [T] Timer stabil sebelum jepret. [T] | Klasik | On-device |
| Koreksi perspektif | `cropAndClean`, warp dengan `Halide_Interpolation_Generator_Int`. [T] Cara menghitung rasio aspek tidak terbaca. | Klasik | On-device |
| **Dewarping lekukan** | **Tidak ditemukan bukti** di versi ini: tidak ada model atau string dewarp/book/curve. [T] | — | — |
| Shadow / iluminasi | CNN **SR** (U-Net pix2pix, 256×256). [T] Diterapkan ke resolusi penuh lewat upsample peta bayangan. [D] Ditambah deteksi `imageHasDarkShadow` untuk petunjuk ke pengguna. [T] | ML | On-device |
| Warna & tekstur | Konversi ke **Lab** (`CALABBitmap`), histogram, **stretch histogram channel L** (`StretchLChannelHistogram`). [T] Kemungkinan dasar "Auto color": kertas jadi putih, tinta gelap, warna (a/b) dipertahankan. [D] Grayscale lewat `Gray`/`CYBitmap`. [T] Whiteboard lewat parameter binarisasi khusus. [T] | Klasik | On-device |
| Hapus noda manual | Smart eraser: isi dengan warna sekitar (*inpainting* sederhana). [D] | Klasik | On-device |
| Encode | JPEG lewat Facebook Spectrum (libjpeg). [T] | — | On-device |
| PDF | Penulis PDF sendiri dengan font CID dan teks OCR tak terlihat. [T] | — | On-device |
| OCR | ML Kit on-device, fallback ke server. [T] | ML | On-device + cloud |

### Perkiraan cara kerja preset filter

| Preset | Perkiraan [D] |
|---|---|
| Original photo | Hanya crop + warp, tanpa koreksi warna. |
| Original color | Crop + warp + koreksi ringan (white balance / shadow ringan). |
| Auto color | Shadow removal (SR) → Lab → stretch histogram L (kertas ke putih, tinta ke hitam) → kroma dipertahankan atau sedikit diperkuat. |
| Grayscale | Seperti Auto color, lalu ambil channel L/Y. |
| Whiteboard | Parameter khusus whiteboard: normalisasi latar agresif dan binarisasi/peningkatan kontras goresan spidol, warna spidol dipertahankan. |

---

## 5. Strategi performa

| Strategi | Bukti |
|---|---|
| **Model di resolusi kecil** (160 / 256 / 384), hasilnya diterapkan ke resolusi penuh. | Bentuk tensor input. [T] |
| Model live (dd, 160 px) dikuantisasi INT8, model berat tetap FP32. | Tipe tensor. [T] |
| **Akselerasi berlapis**: TFLite GPU delegate (GL), NNAPI delegate (`StatefulNnApiDelegate`), fallback CPU; `TFLiteDeviceManager` memilih perangkat. | Simbol native. [T] |
| Kernel citra **Halide** (NEON + `halide_set_num_threads`) dan operasi bitmap per-tile multi-thread (`CThreadedBitmapOperation`). | Simbol native. [T] |
| Analisis live terpisah dari capture: `LiveEdgeDetectorAsyncTask` di background; buffer frame di-reuse (`ByteArrayCache`). | Kelas. [T] |
| Deteksi gerak dari frame YUV (murah, tanpa model) untuk me-restart live edge. | `YUV420MotionDetection`. [T] |
| Thumbnail tiap filter di-cache per halaman (lazy). | `m*ThumbImageRendition$delegate`. [T] |
| Batch "crop & clean semua halaman" di background. | `LocalCropAndCleanAllPagesAsyncTask`. [T] |
| Analitik durasi pembersihan (`CLEANING_DURATION`, `CLEANING_DURATION_ACTUAL`), artinya ada pengukuran performa di lapangan. | Konstanta. [T] |

---

## 6. Library terdeteksi & lisensi

| Library | Lisensi | Boleh dipakai di proyek kita? |
|---|---|---|
| TensorFlow Lite (+ GPU delegate) | Apache 2.0 | Ya |
| Halide | MIT | Ya (opsional; build-time toolchain cukup berat) |
| Facebook Spectrum | MIT | Ya, tapi tidak perlu (`Bitmap.compress` / libjpeg-turbo cukup) |
| libjpeg (di Spectrum) | IJG (permisif) | Ya |
| libc++ (NDK) | Apache 2.0 w/ LLVM exception | Ya |
| AndroidX CameraX, Room, Lifecycle | Apache 2.0 | Ya |
| Kotlin, kotlinx-coroutines, OkHttp | Apache 2.0 | Ya |
| **ML Kit Text Recognition** | **Google ML Kit Terms (bukan open-source)** | **Perlu keputusan** (lihat §8) |
| Play Services Vision, Firebase | Google proprietary terms | Tidak diperlukan |
| MagicClean, Sensei, Scan2PDF, pdfviewer, Creative SDK | Adobe proprietary | **Tidak**. Hanya dipelajari, tidak disalin. |

---

## 7. Rekomendasi padanan open-source per tahap

| Tahap | Adobe Scan | Padanan untuk proyek kita (permisif) | Catatan |
|---|---|---|---|
| Runtime ML | TFLite + GPU/NNAPI | **TFLite** (Apache 2.0) dengan GPU delegate, atau **ONNX Runtime Mobile** (MIT) dengan NNAPI/XNNPACK | Pilih satu agar APK tidak membengkak; TFLite lebih matang untuk GPU di Android. |
| Kernel citra | Halide | **OpenCV 4.x Android SDK** (Apache 2.0 sejak 4.5) | Sesuai AGENTS.md. Halide bisa dipertimbangkan nanti untuk hot path. |
| Gating dokumen | CNN dd 160 px | Tidak wajib di awal. Pakai skor/luas mask dari model segmentasi, atau klasifier MobileNetV3-small yang kita latih sendiri. | — |
| Deteksi tepi (baseline) | Heuristik HC/LC berbasis gradien | Pipeline klasik di AGENTS.md (Canny → contour → approxPolyDP), dengan **dua profil parameter** (kontras tinggi/rendah) seperti HC/LC. | Pelajaran dari Adobe: siapkan beberapa kandidat lalu fusi. |
| Deteksi tepi (ML) | MobileNetV2 + dekoder DeepLabV3+ 256 px, output mask + tepi | **DeepLabV3+ / MobileNetV2** dari TF Model Garden (Apache 2.0), **dilatih sendiri** untuk mask dokumen + peta tepi. | **Wajib cek lisensi dataset** (mis. SmartDoc, MIDV): banyak dataset dokumen yang khusus riset/non-komersial. |
| Fusi kandidat | `BDParamsAlgoFusion` | Skor kandidat quad (luas, konveksitas, sudut ≈ 90°, kecocokan dengan mask NN), pilih yang tertinggi. | Rancangan sendiri. |
| Stabilisasi / auto-capture | Sensor + motion YUV | Selisih luma antar-frame (downsampled) + `SensorManager` + smoothing sudut (EMA/One-Euro filter) | Sesuai AGENTS.md. |
| Perspektif | Halide interpolation | `getPerspectiveTransform` + `warpPerspective` (INTER_LINEAR / INTER_CUBIC) | — |
| Dewarping | *(tidak ada di 21.03)* | Opsi A: **page_dewarp** (pendekatan Matt Zucker, MIT), porting ulang ke Kotlin/C++. Opsi B: UVDoc/DocTr-style → ONNX/TFLite. | **Wajib verifikasi lisensi kode DAN bobot** UVDoc/DocTr dan dataset latihnya (Doc3D dll.) sebelum dipakai. |
| Shadow removal | U-Net pix2pix 256 px | Default: **estimasi latar klasik** (dilate → medianBlur → divide → normalize, per channel di Lab/YCrCb). Opsional: U-Net kecil yang dilatih sendiri, diterapkan sebagai peta iluminasi resolusi rendah lalu di-upsample. | Pola "low-res map → full-res apply" layak ditiru. |
| Warna (Auto color) | Lab + stretch histogram L | OpenCV: Lab → CLAHE / stretch percentil di L → boost kroma ringan → unsharp mask | Sesuai AGENTS.md. |
| B&W / Whiteboard | Parameter binarisasi per jenis dokumen | `ximgproc::niBlackThreshold` (mode Sauvola, opencv_contrib Apache 2.0) atau adaptiveThreshold; profil whiteboard terpisah | Pelajaran: **parameter per jenis dokumen**. |
| Eraser | Smart eraser | `cv::inpaint` (Telea/NS) atau isi dengan median sekitar | Fitur tambahan (opsional). |
| JPEG | Spectrum | `Bitmap.compress` atau libjpeg-turbo (BSD/IJG/zlib) | — |
| PDF | Scan2PDF (teks OCR tersembunyi) | `android.graphics.pdf.PdfDocument` (sesuai AGENTS.md) untuk PDF gambar. Untuk PDF yang bisa dicari: **PdfBox-Android** (Apache 2.0), teks render-mode invisible. | `PdfDocument` tidak mendukung layer teks tak terlihat dengan rapi. |
| OCR | ML Kit + server | **ML Kit** (gratis, bukan OSS) **atau Tesseract** (Apache 2.0, via tess-two / build sendiri) | Lihat keputusan di §8. |

---

## 8. Temuan penting & keputusan yang dibutuhkan

1. **Adobe memakai pendekatan hibrida, bukan murni ML.** Heuristik klasik dan CNN berjalan bersama, lalu kandidat digabung (fusi). Ini cocok dengan rencana AGENTS.md (baseline klasik + upgrade ML). Rekomendasi: arsitektur `:core:imaging` sejak awal mendukung **banyak kandidat quad + skor fusi**.
2. **Parameter bergantung jenis dokumen** (Document/Form/Whiteboard/BusinessCard/Receipt). Rekomendasi: jadikan "mode capture" bagian dari domain model, dengan profil parameter per mode.
3. **Semua model berjalan di ≤ 384 px**; hasilnya diterapkan ke resolusi penuh. Ini mendukung target < 50 ms/frame dan < 2 s/halaman.
4. **Dewarping tidak ada di versi 2021 ini.** Untuk kriteria "baseline melengkung < 2%" tidak ada referensi dari APK ini, jadi kita merancang sendiri (Opsi A/B di AGENTS.md).
5. **Konflik lisensi OCR:** AGENTS.md mensyaratkan *semua* dependensi permisif, tetapi ML Kit tidak open-source (Google ML Kit Terms). Perlu keputusan: (a) izinkan ML Kit sebagai pengecualian yang dicatat di layar About, atau (b) pakai Tesseract (Apache 2.0, lebih besar dan lebih lambat).
6. **Lisensi model/dataset dewarp & segmentasi** harus diverifikasi satu per satu sebelum Fase 3; bobot pretrained sering berlisensi non-komersial.

### Keputusan (19-10-2026, setelah review Fase 1)

| # | Keputusan | Dampak |
|---|---|---|
| K1 | **Scope hanya dokumen.** Tidak ada mode Whiteboard, Receipt, Form, Business Card, Other. | Model klasifikasi jenis dokumen (padanan `dc`) tidak diperlukan. Cukup satu profil parameter "Document". Preset Whiteboard dihapus. |
| K2 | **Tanpa OCR.** Fokus pada kualitas hasil scan. | ML Kit tidak dipakai, sehingga temuan #5 selesai: semua dependensi bisa permisif. PDF cukup berisi gambar (tidak perlu PdfBox untuk layer teks). |
| K3 | Runtime ML default: **TFLite (LiteRT)**, kecuali diputuskan lain. | Satu runtime saja di APK. |

---

## 9. Probe lanjutan (opsional)

| Probe | Manfaat | Prasyarat |
|---|---|---|
| Dekompilasi `LiveEdgeDetector`, `YUV420MotionDetection`, `MagicCleanEdgeDetection` | Nilai threshold gerak, resolusi analisis, interval stabil | JDK + `REA_JADX_MCP_JAR` (jadx-headless-mcp 0.7.1) |
| Analisis APK **versi terbaru** Adobe Scan | Cek apakah sekarang ada book mode / dewarp, dan model generasi baru | APK versi terbaru |
| Observasi runtime (adb + logcat `Magic Clean: PerfLogging`) | Waktu per tahap di device nyata sebagai target benchmark | Device + adb (`REA_ADB_PATH`) |

---

*Dihasilkan dari analisis statis REA terhadap artefak di atas. Semua klaim [D] adalah inferensi dan bukan fakta terverifikasi.*
