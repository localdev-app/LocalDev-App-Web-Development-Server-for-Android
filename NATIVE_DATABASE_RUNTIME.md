# Native database runtime

LocalDev has two database paths:

1. **LocalDevDB compatibility** — built into the Kotlin source. It listens on `127.0.0.1:3306`, speaks MySQL wire protocol, and uses the project-local database behind it. This is the default fallback when native MariaDB is not bundled.
2. **Native MariaDB** — optional Android ARM64 runtime. The Gradle wiring looks for `LOCALDEV_MARIADB_RUNTIME` or `.runtime-cache/LocalDev-MariaDB-Android-arm64.tar.gz`.

A native archive must include:

```text
bin/mariadbd
bin/mariadb
seed/   # initialized datadir template
```

For a strict release build that must contain native MariaDB:

```bash
./gradlew assembleRelease -PlocaldevRequireNativeDb=true
```

The build should fail when the native runtime is missing rather than shipping a misleading “native” feature.
