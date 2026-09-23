# LocalDev 1.1.2


## v1.1.0 Web Extractor

- Menu `🌐 Extract Web HTML + CSS` dari burger menu.
- Input URL HTTP/HTTPS publik.
- HTML diambil oleh PHP cURL dari runtime LocalDev.
- `<link rel="stylesheet">` diunduh ke `css/style-N.css`.
- `<style>...</style>` diekstrak ke `css/inline.css`.
- URL relatif pada HTML/CSS dibuat absolut agar preview lokal tetap bekerja.
- Opsi download gambar, font, favicon, dan asset yang dirujuk CSS ke folder `assets/`.
- Hasil langsung menjadi project LocalDev dengan `index.html`.
- Aksi hasil: Preview HTML, Buka index.html, Lihat CSS, Copy Source HTML, Download ZIP, Project Files.
- URL localhost/private/non-HTTP diblokir dan ukuran resource dibatasi.
- Halaman yang kontennya baru dibuat setelah JavaScript berjalan tidak selalu bisa diekstrak penuh karena extractor bekerja dari HTML response server.

**Code. Run. Test. Anywhere.**

LocalDev is an Android local web development environment for importing, editing, running and previewing web projects directly on-device.

## v1.0.1 File Manager hotfix

- Memperbaiki dialog Burger Menu dan Project Files yang sebelumnya dapat hanya menampilkan judul/ringkasan tanpa daftar item pada beberapa versi Android/Material Dialog.
- Project Files sekarang menampilkan folder dan file hasil import ZIP sebagai daftar scrollable dan dapat diketuk.
- Tambah file baru langsung dari folder aktif, termasuk `index.php`, HTML, CSS, JavaScript, JSON, SQL, `.htaccess`, dan file teks lain.
- Tambah folder baru dari Project Files.
- Tambah pembuatan project kosong PHP (`index.php`) atau static web (`index.html`) tanpa harus import ZIP.
- Jika `index.php` atau `public/index.php` baru dibuat, LocalDev otomatis menjadikannya entry PHP project.
- Database menu diperbaiki agar daftar aksinya tidak hilang akibat kombinasi message + list pada AlertDialog.


## GitHub source package

This repository contains the Android Studio source for **LocalDev 1.1.2** (`versionCode 21`).

### Open in Android Studio

1. Clone/download this repository.
2. Open the repository root in Android Studio.
3. Use JDK 17 and Android SDK / compileSdk 35.
4. Sync Gradle.
5. Build the app or use **Build → Generate Signed App Bundle / APK** for a signed release.

The normal build prepares the ARM64 PHP runtime automatically using the URL and SHA-256 configured in `app/build.gradle.kts`.

For the optional native MariaDB mode, read:

- `PHP_RUNTIME.md`
- `NATIVE_DATABASE_RUNTIME.md`

### Before publishing your own release

Do **not** commit your signing keystore, `local.properties`, production API keys, database credentials, tokens, or other secrets. The included `.gitignore` excludes common signing/runtime files.

No private signing key is included in this source package.

## Main features

- HTML, CSS, JavaScript and PHP editor
- PHP 8.x ARM64 runtime
- Project ZIP import and export
- Web Extractor powered by PHP cURL for HTML/CSS snapshots, with optional image/font/assets
- Hamburger navigation menu
- Folder-based Project Files browser for every file imported from ZIP
- Fullscreen preview and draggable Live Preview
- Local HTTP preview at `http://127.0.0.1:8080`
- PDO SQLite / SQLite3 support when available
- PDO MySQL / mysqli support when available in the bundled PHP runtime
- LocalDevDB MySQL-compatible local testing server
- SQL import/testing workspace
- Pretty-route and static-asset compatibility layer
- Sticky LocalDev session compatibility for multi-request PHP testing
- In-app About, Privacy Policy, Terms, Disclaimer and component/license information

## Local database profile

For compatible MySQL projects LocalDev uses the local profile:

```text
Host: 127.0.0.1
Port: 3306
Database: localdev
User: localdev
Password: localdev
Charset: utf8mb4
```

A project's own database name/user/password can also be preserved by the compatibility layer where supported.

## Build requirements

- Android Studio / Android Gradle Plugin compatible with compileSdk 35
- JDK 17+
- ARM64 Android target

Normal build can use the fallback PHP runtime configured in `app/build.gradle.kts`.

Strict native database build:

```bash
./gradlew assembleRelease -PlocaldevRequireNativeDb=true
```

Strict mode requires a custom PHP runtime with MySQL support and a compatible MariaDB Android ARM64 runtime.

## Important limitations

LocalDev is a development/testing environment, not a production hosting server. Compatibility depends on the project's PHP extensions, server features, database dialect, rewrite rules and external services.

The current PHP execution bridge is CLI-based. Session compatibility is implemented by LocalDev, but web-SAPI-specific behavior such as native `Location` header handling can still differ from Apache/Nginx/PHP-FPM. A future FastCGI/FPM runtime is the correct route for maximum production-like PHP compatibility.

## Privacy

Imported projects are stored inside LocalDev app storage. LocalDev itself is not designed to upload project source to a developer server. User project code can still make its own network requests via JavaScript, cURL, APIs or third-party SDKs.
