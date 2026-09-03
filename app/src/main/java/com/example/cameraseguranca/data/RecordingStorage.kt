package com.example.cameraseguranca.data

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

data class StoredRecording(
    val file: File,
    val storageLabel: String
)

/**
 * Mantém os vídeos dentro dos diretórios privados do app. No cartão removível, usa
 * Android/data/<pacote>/files/recordings, sem expor os arquivos à galeria.
 */
object RecordingStorage {
    private const val DIRECTORY_NAME = "recordings"
    private const val WATERMARKED_FILE_PREFIX = "tvde_insight_"
    private const val TEMPORARY_CHUNK_PREFIX = "tvde_insight_chunk_"
    private const val FINAL_RECORDING_PREFIX = "tvde_insight_final_"

    fun hasSdCard(context: Context): Boolean = sdDirectoryOrNull(context) != null

    fun outputDirectory(context: Context, requestedLocation: StorageLocation): File {
        val requestedDirectory = when (requestedLocation) {
            StorageLocation.LOCAL -> localDirectory(context)
            StorageLocation.SD_CARD -> sdDirectoryOrNull(context) ?: localDirectory(context)
        }
        check(requestedDirectory.exists() || requestedDirectory.mkdirs()) {
            "Não foi possível criar o diretório privado de gravações."
        }
        return requestedDirectory
    }

    fun newOutputFile(context: Context, requestedLocation: StorageLocation, segmentIndex: Int): File {
        return File(
            outputDirectory(context, requestedLocation),
            "${TEMPORARY_CHUNK_PREFIX}${System.currentTimeMillis()}_${segmentIndex.toString().padStart(3, '0')}.mp4"
        )
    }

    fun newFinalOutputFile(
        context: Context,
        requestedLocation: StorageLocation
    ): File = File(
        outputDirectory(context, requestedLocation),
        "$FINAL_RECORDING_PREFIX${System.currentTimeMillis()}.mp4"
    )

    /**
     * Mantém apenas os segmentos mais recentes da janela circular. Assim, a
     * quantidade de ficheiros permanece limitada mesmo depois de reiniciar a
     * gravação noutra hora do dia.
     */
    fun pruneCircularSegments(
        context: Context,
        requestedLocation: StorageLocation,
        maxSegments: Int
    ): Int {
        val directory = outputDirectory(context, requestedLocation)
        val candidates = directory.listFiles().orEmpty()
            .asSequence()
            .filter { file -> file.isFile && isTemporaryChunk(file) }
            .sortedBy { it.lastModified() }
            .toList()
        val excess = (candidates.size - maxSegments).coerceAtLeast(0)
        return candidates.take(excess).count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    /** Só permite exportar arquivos criados já com a marca d’água incorporada. */
    fun canDownload(file: File): Boolean = file.name.startsWith(WATERMARKED_FILE_PREFIX)

    private fun isTemporaryChunk(file: File): Boolean =
        file.extension.equals("mp4", ignoreCase = true) &&
            (file.name.startsWith(TEMPORARY_CHUNK_PREFIX) ||
                // Compatibilidade com segmentos criados pela versão anterior.
                (file.name.startsWith(WATERMARKED_FILE_PREFIX) &&
                    !file.name.startsWith(FINAL_RECORDING_PREFIX)))

    /**
     * Cria uma cópia solicitada pela pessoa em Downloads. A cópia é byte a byte, logo
     * preserva integralmente os dados e a marca d’água já gravados no MP4 privado.
     */
    fun downloadWatermarkedCopy(context: Context, file: File): Uri {
        check(isManagedRecording(context, file) && canDownload(file)) {
            "Apenas vídeos com marca d’água incorporada podem ser baixados."
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/TVDE Insight"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Não foi possível criar a cópia em Downloads.")
        try {
            file.inputStream().use { input ->
                resolver.openOutputStream(destination)?.use { output -> input.copyTo(output) }
                    ?: throw IOException("Não foi possível abrir o destino do download.")
            }
            resolver.update(destination, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return destination
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    fun list(context: Context): List<StoredRecording> {
        return managedDirectories(context)
            .flatMap { (directory, label) ->
                directory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) && it.length() > 0L }
                    .map { StoredRecording(file = it, storageLabel = label) }
                    .toList()
            }
            .sortedByDescending { it.file.lastModified() }
    }

    /**
     * Apaga apenas MP4s privados deste app cuja última alteração ultrapasse o período escolhido.
     * Retorna quantos arquivos foram efetivamente removidos.
     */
    fun deleteExpired(
        context: Context,
        interval: AutoDeleteInterval,
        nowMillis: Long = System.currentTimeMillis()
    ): Int {
        val expirationTime = nowMillis - interval.milliseconds
        return managedDirectories(context)
            .asSequence()
            .flatMap { (directory, _) -> directory.listFiles().orEmpty().asSequence() }
            .filter { file ->
                file.isFile &&
                    file.extension.equals("mp4", ignoreCase = true) &&
                    file.lastModified() < expirationTime
            }
            .count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    /** Exclui manualmente somente um MP4 presente em um diretório privado gerenciado. */
    fun delete(context: Context, file: File): Boolean {
        check(isManagedRecording(context, file) && file.extension.equals("mp4", ignoreCase = true)) {
            "Esta gravação não pertence ao armazenamento privado do app."
        }
        return file.delete()
    }

    fun isManagedRecording(context: Context, file: File): Boolean {
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return managedDirectories(context).any { (directory, _) ->
            val root = runCatching { directory.canonicalFile }.getOrNull() ?: return@any false
            candidate.path.startsWith(root.path + File.separator)
        }
    }

    private fun localDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    private fun sdDirectoryOrNull(context: Context): File? {
        val removableDirectory = context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { directory ->
                Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED &&
                    Environment.isExternalStorageRemovable(directory)
            }
        return removableDirectory?.resolve(DIRECTORY_NAME)
    }

    /** Lê vídeos da localização privada usada por versões anteriores, sem voltar a gravar nela. */
    private fun legacySdDirectoryOrNull(context: Context): File? {
        val removableDirectory = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            .filterNotNull()
            .firstOrNull { directory ->
                Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED &&
                    Environment.isExternalStorageRemovable(directory)
            }
        return removableDirectory?.resolve(DIRECTORY_NAME)
    }

    private fun managedDirectories(context: Context): List<Pair<File, String>> {
        val locations = mutableListOf(localDirectory(context) to StorageLocation.LOCAL.label)
        sdDirectoryOrNull(context)?.let { locations += it to StorageLocation.SD_CARD.label }
        legacySdDirectoryOrNull(context)
            ?.takeIf { legacy -> locations.none { (directory, _) -> directory == legacy } }
            ?.let { locations += it to StorageLocation.SD_CARD.label }
        return locations
    }
}
