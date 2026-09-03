package com.daniel.tvdeinsight.data.screenshot

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armazena capturas exclusivamente nos ficheiros privados da aplicação.
 * Não usa MediaStore nem a galeria: uma imagem só é exposta quando o
 * utilizador a descarrega explicitamente a partir do detalhe da viagem.
 */
@Singleton
class OfferScreenshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun save(entryId: Long, bitmap: Bitmap): String? = runCatching {
        val name = fileName(entryId)
        val directory = storageDirectory().apply { mkdirs() }
        val destination = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Não foi possível comprimir a captura"
            }
        }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination)) { "Não foi possível guardar a captura" }
        name
    }.getOrNull()

    fun fileFor(fileName: String?): File? {
        val safeName = fileName?.takeIf { it.matches(FILE_NAME_PATTERN) } ?: return null
        val file = File(storageDirectory(), safeName)
        return file.takeIf(File::isFile)
    }

    /** Remove imagens vencidas sem as publicar fora da aplicação. */
    fun deleteOlderThan(retentionHours: Int, nowMillis: Long = System.currentTimeMillis()): Int {
        val threshold = nowMillis - retentionHours.coerceIn(24, 7 * 24).toLong() * 60L * 60L * 1_000L
        return storageDirectory().listFiles()
            ?.filter { file -> file.isFile && file.name.matches(FILE_NAME_PATTERN) && file.lastModified() < threshold }
            ?.count { it.delete() }
            ?: 0
    }

    private fun storageDirectory(): File = File(context.filesDir, DIRECTORY_NAME)

    private fun fileName(entryId: Long): String = "oferta-$entryId.jpg"

    private companion object {
        const val DIRECTORY_NAME = "offer_screenshots"
        const val JPEG_QUALITY = 92
        val FILE_NAME_PATTERN = Regex("oferta-[0-9]+\\.jpg")
    }
}
