package com.daniel.tvdeinsight.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registo persistente da aplicação. O ficheiro fica no armazenamento privado
 * e só sai do dispositivo quando o utilizador o exporta pela tela de configuração.
 */
object AppLogger {
    private const val CURRENT_FILE_NAME = "tvde-insight-current.log"
    private const val LEGACY_FILE_NAME = "tvde-insight.log"
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private const val MAX_ARCHIVED_LOGS = 5

    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)
    private var applicationContext: Context? = null
    private var logFile: File? = null
    private var writer: BufferedWriter? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (applicationContext != null) return
            applicationContext = context.applicationContext
            logFile = File(applicationContext!!.filesDir, CURRENT_FILE_NAME)
        }
        installUncaughtExceptionHandler()
        info("AppLogger inicializado; Android=${Build.VERSION.SDK_INT}, modelo=${Build.MODEL}")
    }

    fun debug(message: String) = write("DEBUG", message)

    fun info(message: String) = write("INFO", message)

    fun warn(message: String, error: Throwable? = null) = write("WARN", message, error)

    fun error(message: String, error: Throwable? = null) = write("ERROR", message, error)

    fun exportTo(uri: Uri): Boolean {
        val context = applicationContext ?: return false
        return synchronized(lock) {
            runCatching {
                writer?.flush()
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeCompleteLog(output)
                } ?: throw IOException("Não foi possível abrir o destino do log")
                true
            }.getOrElse {
                write("ERROR", "Falha ao exportar o log", it)
                false
            }
        }
    }

    /** Cria uma cópia completa e temporária para partilha através do WhatsApp. */
    fun createShareFile(): File? {
        val context = applicationContext ?: return null
        return synchronized(lock) {
            runCatching {
                writer?.flush()
                val shareDirectory = File(context.cacheDir, "shared-logs").apply { mkdirs() }
                shareDirectory.listFiles()?.forEach(File::delete)
                File(shareDirectory, "tvde-insight-${System.currentTimeMillis()}.log").also { target ->
                    target.outputStream().use(::writeCompleteLog)
                }
            }.getOrElse {
                write("ERROR", "Falha ao preparar o log para partilha", it)
                null
            }
        }
    }

    private fun writeCompleteLog(output: OutputStream) {
        allLogFiles().forEach { file ->
            output.write("--- ${file.name} ---\n".toByteArray(Charsets.UTF_8))
            file.inputStream().use { input -> input.copyTo(output) }
            output.write("\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun write(level: String, message: String, error: Throwable? = null) {
        val line = buildString {
            append(dateFormat.format(Date()))
            append(" [")
            append(level)
            append("] [")
            append(Thread.currentThread().name)
            append("] ")
            append(message.replace('\n', ' '))
            error?.let {
                append(" | ")
                append(it::class.java.name)
                append(": ")
                append(it.message.orEmpty().replace('\n', ' '))
                append('\n')
                append(it.stackTraceToString())
            }
            append('\n')
        }

        synchronized(lock) {
            val target = logFile ?: return
            runCatching {
                target.parentFile?.mkdirs()
                val currentWriter = writer ?: BufferedWriter(
                    OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8)
                ).also { writer = it }
                currentWriter.write(line)
                currentWriter.flush()
                rotateIfNeeded(target)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.length() <= MAX_LOG_BYTES) return
        writer?.flush()
        writer?.close()
        writer = null
        val archive = File(file.parentFile, "tvde-insight-${System.currentTimeMillis()}.log")
        if (!file.renameTo(archive)) {
            file.copyTo(archive, overwrite = true)
            file.delete()
        }
        pruneArchivedLogs(file.parentFile)
    }

    private fun allLogFiles(): List<File> {
        val directory = applicationContext?.filesDir ?: return emptyList()
        return directory.listFiles { file ->
            file.name == CURRENT_FILE_NAME ||
                file.name == LEGACY_FILE_NAME ||
                (file.name.startsWith("tvde-insight-") && file.name.endsWith(".log"))
        }.orEmpty().sortedBy { it.lastModified() }
    }

    private fun pruneArchivedLogs(directory: File?) {
        val archives = directory?.listFiles { file ->
            file.name.startsWith("tvde-insight-") &&
                file.name.endsWith(".log") &&
                file.name != CURRENT_FILE_NAME
        }.orEmpty().sortedBy { it.lastModified() }
        archives.dropLast(MAX_ARCHIVED_LOGS).forEach(File::delete)
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error("Exceção não tratada na thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
