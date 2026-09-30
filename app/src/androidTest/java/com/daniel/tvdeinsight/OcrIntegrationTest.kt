package com.daniel.tvdeinsight

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.daniel.tvdeinsight.data.local.AppDatabase
import com.daniel.tvdeinsight.data.local.TripEntityMapper
import com.daniel.tvdeinsight.data.screenshot.OfferScreenshotStore
import com.daniel.tvdeinsight.domain.model.OfferHistoryEntry
import com.daniel.tvdeinsight.domain.model.OfferPlatform
import com.daniel.tvdeinsight.service.ocr.OpenCvOcrPreprocessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun nativeMatsAndBitmapsAreReleasedForBothThemes() {
        for (dark in listOf(false, true)) {
            val source = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(source)
                canvas.drawColor(if (dark) Color.rgb(28, 28, 28) else Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (dark) Color.WHITE else Color.BLACK
                    textSize = 36f
                }
                canvas.drawText("€ 8,03", 40f, 550f, paint)
                canvas.drawText("5 minutos (3.0 km)", 40f, 650f, paint)
                repeat(100) {
                    val prepared = OpenCvOcrPreprocessor.prepare(source)
                    val output = prepared.bitmap
                    try {
                        assertEquals(480, output.height)
                        assertFalse(source.isRecycled)
                        val pixels = IntArray(output.width * output.height)
                        output.getPixels(pixels, 0, output.width, 0, 0, output.width, output.height)
                        val black = pixels.count { Color.red(it) < 128 }
                        assertTrue("Text must survive dark/light normalization", black > 200)
                        assertTrue("Background must be mostly white", black < pixels.size / 3)
                    } finally { prepared.close() }
                    assertTrue(output.isRecycled)
                }
            } finally { source.recycle() }
        }
    }

    @Test fun screenshotsSurviveStoreRecreationAndExpireOnlyAtRetentionBoundary() {
        val directory = File(context.cacheDir, "ocr-retention-test-${System.nanoTime()}").apply { mkdirs() }
        val privateContext = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val name = requireNotNull(OfferScreenshotStore(privateContext).save(123L, bitmap))
            bitmap.recycle()
            val restored = OfferScreenshotStore(privateContext)
            val file = requireNotNull(restored.fileFor(name))
            val savedAt = file.lastModified()
            val hour = 3_600_000L
            assertEquals(0, restored.deleteOlderThan(24, savedAt + 24 * hour - 1))
            assertTrue(file.exists())
            assertEquals(file, restored.fileForEntry(123L, null))
            assertEquals(0, restored.deleteOlderThan(168, savedAt + 167 * hour))
            assertEquals(1, restored.deleteOlderThan(168, savedAt + 169 * hour))
            assertNull(restored.fileFor(name))
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            check(directory.parentFile == context.cacheDir && directory.name.startsWith("ocr-retention-test-"))
            directory.deleteRecursively()
        }
    }

    @Test fun sheetReplacementDoesNotEraseScreenshotReference() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val domain = OfferHistoryEntry(id = 123L, recordedAtMillis = 123L, platform = OfferPlatform.UBER,
                valorPorKm = 1.0, valorPorHora = 20.0, pickupDistanceKm = 1.6,
                destinationDistanceKm = 4.0, tripValue = 4.07, sourceDeviceId = "test",
                screenshotFileName = "oferta-123.jpg")
            val entity = TripEntityMapper.fromDomain(domain)
            database.tripDao().insertIgnore(entity)
            database.tripDao().upsertAll(listOf(entity.copy(screenshotFileName = null)))
            assertEquals("oferta-123.jpg", database.tripDao().screenshotFor("test", 123L))
        } finally { database.close() }
    }
}
