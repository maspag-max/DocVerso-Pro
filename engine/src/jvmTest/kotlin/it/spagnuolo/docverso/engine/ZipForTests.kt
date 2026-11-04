package it.spagnuolo.docverso.engine

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Solo per i test sul PC: mette i file del pacchetto Word in un archivio zip. */
fun zip(parts: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z -> for ((n, b) in parts) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    return out.toByteArray()
}
