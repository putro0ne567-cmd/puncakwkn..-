# Apk-mod (NJ Audio / Stereo DSP)

Pemutar audio dengan DSP stereo (L/R terpisah) dan efek untuk aplikasi lain.

## Pemutar internal
- EQ 10 band, HPF/LPF (6-48 dB/okt), gain, **polaritas (balik fase)**, mute, **reverb** (mix & ukuran)
- **Visualizer** spektrum 32 pita (dari sinyal keluaran setelah efek)
- **Jalan di latar belakang**: foreground service + notifikasi (Jeda/Putar, Stop), kontrol layar kunci/headset,
  audio focus (jeda otomatis saat aplikasi lain mulai memutar), wake lock CPU

## Efek untuk aplikasi lain (YouTube dll.)
Memakai `DynamicsProcessing` (gain, HPF/LPF, EQ) dan `PresetReverb` (reverb) yang dipasang ke sesi audio aplikasi lain.
Sesi ditemukan lewat broadcast `OPEN_AUDIO_EFFECT_CONTROL_SESSION` (pemutar yang mengumumkannya) dan
`dumpsys audio` (perlu izin sekali lewat ADB):

    adb shell pm grant com.stereodsp android.permission.DUMP

## Mode Tangkap (tanpa izin DUMP, reverb & fase berfungsi)
Memakai `AudioPlaybackCapture` (MediaProjection): suara YouTube/aplikasi lain ditangkap, diproses mesin DSP yang sama dengan
pemutar internal (EQ penuh, HPF/LPF, **polaritas/balik fase**, **reverb dengan mix & ukuran**), lalu diputar ulang.
Tidak perlu ADB. Cukup izinkan RECORD_AUDIO dan dialog perekaman layar/audio sistem (hanya audio yang diambil).
Catatan: suara asli aplikasi sumber tidak bisa dibisukan tanpa ID sesi, jadi terdengar bersama hasil olahan; aplikasi/konten yang
melarang penangkapan audio (DRM, `allowAudioPlaybackCapture=false`) tidak akan terdengar di mode ini.

Keterbatasan Android pada mode efek (DynamicsProcessing/PresetReverb): **polaritas/balik fase tidak bisa diterapkan ke aplikasi lain** (tidak ada di API efek);
reverb memakai preset ruang tanpa pengaturan mix.

## Build
Push ke GitHub: workflow `.github/workflows/build-apk.yml` menyusun struktur proyek dan membuat APK debug.
