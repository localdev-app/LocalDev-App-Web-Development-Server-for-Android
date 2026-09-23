import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localDevPhpUrl = "https://github.com/pmmp/PHP-Binaries/releases/download/pm5-php-8.4-latest/PHP-8.4-Android-arm64-PM5.tar.gz"
val localDevPhpSha256 = "d8867966340121f821591b9bb29c80a58ad77abd6cc8e0e44d1cbbb3aaedd70c"
val localDevPhpArchive = rootProject.file(".runtime-cache/PHP-8.4-Android-arm64-PM5.tar.gz")
val generatedPhpJniDir = layout.buildDirectory.dir("generated/localdevPhpJni")
val customPhpRuntime = System.getenv("LOCALDEV_PHP_RUNTIME")?.let { rootProject.file(it) }
    ?: rootProject.file(".runtime-cache/localdev-php-8.4-android-arm64")

val localDevMariaDbArchive = System.getenv("LOCALDEV_MARIADB_RUNTIME")?.let { rootProject.file(it) }
    ?: rootProject.file(".runtime-cache/LocalDev-MariaDB-Android-arm64.tar.gz")
val generatedMariaDbJniDir = layout.buildDirectory.dir("generated/localdevMariaDbJni")
val generatedMariaDbAssetsDir = layout.buildDirectory.dir("generated/localdevMariaDbAssets")
val requireNativeDb = providers.gradleProperty("localdevRequireNativeDb").orNull?.equals("true", ignoreCase = true) == true

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val prepareLocalDevPhpRuntime by tasks.registering {
    group = "localdev"
    description = "Downloads, verifies, and embeds the Android ARM64 PHP runtime before packaging."

    val outFileProvider = generatedPhpJniDir.map { it.file("arm64-v8a/liblocaldev_php.so") }
    outputs.file(outFileProvider)

    doLast {
        localDevPhpArchive.parentFile.mkdirs()

        val outFile = outFileProvider.get().asFile
        outFile.parentFile.mkdirs()

        if (customPhpRuntime.isFile) {
            logger.lifecycle("LocalDev: using custom PHP runtime with MySQL support: ${customPhpRuntime.absolutePath}")
            customPhpRuntime.copyTo(outFile, overwrite = true)
            outFile.setExecutable(true, false)
            return@doLast
        }

        if (requireNativeDb) {
            throw GradleException(
                "localdevRequireNativeDb=true requires a custom PHP ARM64 binary with pdo_mysql at " +
                    customPhpRuntime.absolutePath + " or LOCALDEV_PHP_RUNTIME."
            )
        }

        fun verifyArchive(): Boolean =
            localDevPhpArchive.isFile && localDevPhpArchive.sha256().equals(localDevPhpSha256, ignoreCase = true)

        if (!verifyArchive()) {
            if (localDevPhpArchive.exists()) localDevPhpArchive.delete()
            logger.lifecycle("LocalDev: downloading fallback PHP 8.4 Android ARM64 runtime…")

            val connection = URI(localDevPhpUrl).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "LocalDev-Android-Build/1.1.1")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw GradleException("LocalDev PHP runtime download failed: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                localDevPhpArchive.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
        }

        val actual = localDevPhpArchive.sha256()
        if (!actual.equals(localDevPhpSha256, ignoreCase = true)) {
            localDevPhpArchive.delete()
            throw GradleException("LocalDev PHP runtime checksum mismatch. Expected $localDevPhpSha256, got $actual")
        }

        val extractDir = layout.buildDirectory.dir("tmp/localdevPhpExtract").get().asFile
        delete(extractDir)
        extractDir.mkdirs()
        copy {
            from(tarTree(resources.gzip(localDevPhpArchive)))
            into(extractDir)
        }

        val candidates = extractDir.walkTopDown()
            .filter { it.isFile && it.name == "php" }
            .toList()
        val php = candidates.firstOrNull { it.invariantSeparatorsPath.contains("/bin/php7/bin/php") }
            ?: candidates.firstOrNull()
            ?: throw GradleException("PHP executable was not found inside ${localDevPhpArchive.name}")

        php.copyTo(outFile, overwrite = true)
        outFile.setExecutable(true, false)
        logger.lifecycle("LocalDev: fallback PHP runtime prepared at ${outFile.absolutePath}")
    }
}


val prepareLocalDevMariaDbRuntime by tasks.registering {
    group = "localdev"
    description = "Embeds an optional LocalDev-compatible MariaDB Android ARM64 runtime."

    doLast {
        val jniOut = generatedMariaDbJniDir.get().asFile
        val assetsOut = generatedMariaDbAssetsDir.get().asFile
        delete(jniOut, assetsOut)
        jniOut.mkdirs()
        assetsOut.mkdirs()

        if (!localDevMariaDbArchive.isFile) {
            if (requireNativeDb) {
                throw GradleException(
                    "localdevRequireNativeDb=true but MariaDB runtime is missing: ${localDevMariaDbArchive.absolutePath}"
                )
            }
            logger.warn("LocalDev: native MariaDB runtime not found at ${localDevMariaDbArchive.absolutePath}. APK will build, but Native DB will show NOT BUNDLED.")
            return@doLast
        }

        val extractDir = layout.buildDirectory.dir("tmp/localdevMariaDbExtract").get().asFile
        delete(extractDir)
        extractDir.mkdirs()
        copy {
            from(tarTree(resources.gzip(localDevMariaDbArchive)))
            into(extractDir)
        }

        val server = extractDir.walkTopDown().firstOrNull { it.isFile && it.name == "mariadbd" }
            ?: throw GradleException("MariaDB runtime archive does not contain bin/mariadbd")
        val client = extractDir.walkTopDown().firstOrNull { it.isFile && it.name == "mariadb" }
            ?: throw GradleException("MariaDB runtime archive does not contain bin/mariadb")
        val seed = extractDir.walkTopDown().firstOrNull { it.isDirectory && it.name == "seed" }
            ?: throw GradleException("MariaDB runtime archive does not contain seed/ initialized datadir")

        val abi = File(jniOut, "arm64-v8a").apply { mkdirs() }
        server.copyTo(File(abi, "liblocaldev_mariadbd.so"), overwrite = true)
        client.copyTo(File(abi, "liblocaldev_mariadb.so"), overwrite = true)

        copy {
            from(seed)
            into(File(assetsOut, "localdev-mariadb/seed"))
        }
        logger.lifecycle("LocalDev: MariaDB runtime embedded from ${localDevMariaDbArchive.name}")
    }
}

android {
    namespace = "com.webrun.php"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webrun.php"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.1.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets.getByName("main").jniLibs.srcDirs(generatedPhpJniDir, generatedMariaDbJniDir)
    sourceSets.getByName("main").assets.srcDir(generatedMariaDbAssetsDir)

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // The PHP executable is packaged as a native artifact so Android extracts it
            // into nativeLibraryDir, which is executable by the app process.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareLocalDevPhpRuntime, prepareLocalDevMariaDbRuntime)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
