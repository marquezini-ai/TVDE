package com.example.cameraseguranca.data

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converte os segmentos temporários do loop numa única gravação final.
 *
 * A duração é calculada pelos metadados reais dos MP4, e não pelo nome do
 * ficheiro. Isso permite que o último segmento parcial seja incluído e que o
 * corte corresponda ao instante em que a pessoa carregou em Parar.
 */
@UnstableApi
class RollingRecordingFinalizer(
    private val context: Context
) {
    private data class Source(val file: File, val durationMs: Long)

    suspend fun createFinalMp4(
        chunks: List<File>,
        windowMs: Long,
        outputFile: File,
        includeAudio: Boolean
    ): File {
        require(windowMs > 0L) { "A janela circular deve ser maior que zero." }

        val sources = withContext(Dispatchers.IO) {
            chunks
                .filter { it.isFile && it.length() > 0L }
                .sortedBy { it.lastModified() }
                .mapNotNull { file ->
                    readDurationMs(file).takeIf { it > 0L }?.let { Source(file, it) }
                }
        }
        require(sources.isNotEmpty()) { "Nenhum segmento válido encontrado." }

        val totalDurationMs = sources.sumOf { it.durationMs }
        val trimFromMs = (totalDurationMs - windowMs).coerceAtLeast(0L)
        var consumedMs = 0L
        val editedItems = buildList {
            for (source in sources) {
                val startMs = (trimFromMs - consumedMs)
                    .coerceIn(0L, source.durationMs)
                val endMs = (totalDurationMs - consumedMs)
                    .coerceIn(0L, source.durationMs)

                if (endMs > startMs) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(source.file.toUri())
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build()
                        )
                        .build()
                    add(EditedMediaItem.Builder(mediaItem).build())
                }
                consumedMs += source.durationMs
            }
        }

        require(editedItems.isNotEmpty()) { "Não foi possível montar a janela circular." }

        val trackTypes = buildSet {
            add(C.TRACK_TYPE_VIDEO)
            if (includeAudio) add(C.TRACK_TYPE_AUDIO)
        }
        val sequence = EditedMediaItemSequence.Builder(trackTypes)
            .addItems(editedItems)
            .build()
        val composition = Composition.Builder(sequence).build()

        outputFile.parentFile?.mkdirs()
        val partialFile = File(outputFile.parentFile, "${outputFile.name}.partial")
        withContext(Dispatchers.IO) {
            if (partialFile.exists()) partialFile.delete()
        }

        // Transformer deve ser criado e controlado numa única thread. A
        // transcodificação é usada para tornar os limites entre chunks contínuos,
        // sem depender de keyframes coincidentes entre ficheiros.
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            continuation.resumeWith(Result.success(Unit))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            continuation.resumeWith(Result.failure(exportException))
                        }
                    })
                    .build()

                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, partialFile.absolutePath)
            }
        }

        withContext(Dispatchers.IO) {
            require(partialFile.isFile && partialFile.length() > 0L) {
                "O MP4 final não foi criado."
            }
            if (outputFile.exists()) outputFile.delete()
            check(partialFile.renameTo(outputFile)) {
                "Não foi possível confirmar o MP4 final."
            }
        }
        return outputFile
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        } finally {
            retriever.release()
        }
    }
}
