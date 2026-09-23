package com.webrun.php.project

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ProjectImporter {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL_UNCOMPRESSED = 250L * 1024L * 1024L
    private const val MAX_SINGLE_FILE = 80L * 1024L * 1024L

    data class ImportedProject(
        /** Full project root, including database.sql/config/vendor/etc. */
        val root: File,
        val entryFile: File,
        val isPhp: Boolean,
        /** HTTP document root. May be root/public for modern PHP projects. */
        val documentRoot: File = entryFile.parentFile ?: root
    )

    fun importZip(
        resolver: ContentResolver,
        uri: Uri,
        destination: File
    ): ImportedProject {
        if (destination.exists()) destination.deleteRecursively()
        destination.mkdirs()

        var entryCount = 0
        var totalWritten = 0L
        val destinationPrefix = destination.canonicalPath + File.separator
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        resolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "ZIP tidak dapat dibaca." }
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_ENTRIES) { "ZIP terlalu besar: lebih dari $MAX_ENTRIES file." }

                    val output = File(destination, entry.name)
                    val canonicalOutput = output.canonicalPath
                    require(canonicalOutput.startsWith(destinationPrefix)) {
                        "ZIP tidak aman: path traversal pada ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        var fileWritten = 0L
                        FileOutputStream(output).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                fileWritten += read
                                totalWritten += read
                                require(fileWritten <= MAX_SINGLE_FILE) {
                                    "Satu file di ZIP terlalu besar: ${entry.name}"
                                }
                                require(totalWritten <= MAX_TOTAL_UNCOMPRESSED) {
                                    "Isi ZIP melebihi batas ${MAX_TOTAL_UNCOMPRESSED / 1024 / 1024} MB."
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val projectRoot = unwrapSingleDirectory(destination)
        val entryFile = findEntryFile(projectRoot)
            ?: error("index.php, public/index.php, index.html, atau index.htm tidak ditemukan di project.")

        val documentRoot = when {
            File(projectRoot, "public").isDirectory && entryFile.canonicalPath.startsWith(File(projectRoot, "public").canonicalPath + File.separator) -> File(projectRoot, "public")
            else -> entryFile.parentFile ?: projectRoot
        }

        return ImportedProject(
            root = projectRoot,
            entryFile = entryFile,
            isPhp = entryFile.extension.equals("php", ignoreCase = true),
            documentRoot = documentRoot
        )
    }

    private fun unwrapSingleDirectory(destination: File): File {
        val children = destination.listFiles()?.filterNot { it.name == "__MACOSX" }.orEmpty()
        return if (children.size == 1 && children.first().isDirectory) children.first() else destination
    }

    private fun findEntryFile(root: File): File? {
        val direct = listOf(
            File(root, "index.php"),
            File(root, "public/index.php"),
            File(root, "index.html"),
            File(root, "index.htm"),
            File(root, "public/index.html"),
            File(root, "public/index.htm")
        ).firstOrNull { it.isFile }
        if (direct != null) return direct

        val files = root.walkTopDown().filter { it.isFile }.toList()
        return files.firstOrNull { it.name.equals("index.php", true) }
            ?: files.firstOrNull { it.name.equals("index.html", true) }
            ?: files.firstOrNull { it.name.equals("index.htm", true) }
    }
}
