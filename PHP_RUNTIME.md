# LocalDev PHP runtime

LocalDev memakai PHP CLI sebagai execution engine di balik LocalDev HTTP bridge.

## Fallback

Jika tidak ada custom runtime, Gradle mengambil PHP 8.4 Android ARM64 dari PMMP untuk mempertahankan fitur PHP/SQLite lama.

## Native MySQL mode v0.9

Untuk menjalankan source yang memakai PDO MySQL, runtime PHP wajib memiliki:

```text
mysqlnd
pdo
pdo_mysql
mysqli
```

Configure flags minimum:

```text
--enable-mysqlnd
--with-pdo-mysql=mysqlnd
--with-mysqli=mysqlnd
```

Tambahkan extension lain yang dibutuhkan project, misalnya `mbstring`, `curl`, `openssl`, `zip`, `fileinfo`, `gd`, `dom`.

Custom PHP binary Android ARM64 dapat diberikan melalui:

```text
.runtime-cache/localdev-php-8.4-android-arm64
```

atau:

```text
LOCALDEV_PHP_RUNTIME=/absolute/path/to/php
```

Jangan gunakan `php.exe` Windows atau binary Linux glibc biasa.
