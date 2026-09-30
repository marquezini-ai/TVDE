package com.daniel.tvdeinsight.service.ocr

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** Borrows source; caller closes result only AFTER ML Kit finishes reading it. */
internal object OpenCvOcrPreprocessor {
    private val available by lazy { OpenCVLoader.initLocal() }

    /**
     * Fast, allocation-bounded visual gate before native/OpenCV work. Uber cards
     * contain a large neutral (very light or very dark) bottom sheet; a regular
     * map normally does not. This is intentionally permissive: it may admit a
     * map, but must never reject a genuine light or dark offer card.
     */
    fun hasLikelyOfferPanel(source: Bitmap): Boolean {
        if (source.width < 160 || source.height < 160) return false
        val width = 96
        val height = 128
        val sample = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val pixels = IntArray(width * height)
            sample.getPixels(pixels, 0, width, 0, 0, width, height)
            var qualifyingRows = 0
            var broadRows = 0
            var currentQualifyingStreak = 0
            var longestQualifyingStreak = 0
            for (y in height / 3 until height) {
                var longestRun = 0
                var currentRun = 0
                for (x in 0 until width) {
                    val color = pixels[y * width + x]
                    val red = color shr 16 and 0xff
                    val green = color shr 8 and 0xff
                    val blue = color and 0xff
                    val highChroma = maxOf(red, green, blue) - minOf(red, green, blue) > 34
                    val luma = (red * 299 + green * 587 + blue * 114) / 1000
                    val neutralPanel = !highChroma && (luma >= 205 || luma <= 75)
                    if (neutralPanel) {
                        currentRun++
                        longestRun = maxOf(longestRun, currentRun)
                    } else {
                        currentRun = 0
                    }
                }
                if (longestRun >= 48) {
                    qualifyingRows++
                    currentQualifyingStreak++
                    longestQualifyingStreak = maxOf(longestQualifyingStreak, currentQualifyingStreak)
                } else {
                    currentQualifyingStreak = 0
                }
                if (longestRun >= 70) broadRows++
            }
            // A card is a coherent sheet, not a handful of unrelated neutral
            // patches from roads or map labels. Keep the gate permissive for
            // both themes, while rejecting most plain-map captures before JNI.
            return qualifyingRows >= 16 && broadRows >= 6 && longestQualifyingStreak >= 8
        } finally {
            if (sample !== source && !sample.isRecycled) sample.recycle()
        }
    }

    fun prepare(source: Bitmap, bottomHalf: Boolean = true, binarize: Boolean = true): PreparedBitmap {
        val top = if (bottomHalf) source.height / 2 else 0
        val scale = minOf(1.0, kotlin.math.sqrt(3_000_000.0 / (source.width.toLong() * source.height))).toFloat()
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var output: Bitmap? = null
        val mats = ArrayList<Mat>(4)
        fun owned(mat: Mat): Mat = mat.also { mats.add(it) }
        try {
            scaled = if (scale < 1f) Bitmap.createScaledBitmap(source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1), true) else source
            if (!binarize) {
                val scaledTop = if (bottomHalf) scaled.height / 2 else 0
                cropped = Bitmap.createBitmap(scaled, 0, scaledTop, scaled.width, scaled.height - scaledTop)
                output = checkNotNull(cropped.copy(Bitmap.Config.ARGB_8888, false))
            } else {
                check(available) { "OpenCV initialization failed" }
                val rgba = owned(Mat())
                val gray = owned(Mat())
                val binary = owned(Mat())
                Utils.bitmapToMat(scaled, rgba)
                val roi = owned(rgba.submat(if (bottomHalf) rgba.rows() / 2 else 0, rgba.rows(), 0, rgba.cols()))
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
                // adaptiveThreshold alone does NOT normalize dark themes; invert luminance first.
                val histogram = IntArray(256)
                val row = ByteArray(gray.cols())
                var samples = 0
                for (y in 0 until gray.rows() step 8) {
                    gray.get(y, 0, row)
                    for (x in row.indices step 8) { histogram[row[x].toInt() and 255]++; samples++ }
                }
                var cumulative = 0
                val median = histogram.indices.firstOrNull { cumulative += histogram[it]; cumulative >= samples / 2 } ?: 255
                if (median < 128) Core.bitwise_not(gray, gray)
                val maxBlock = minOf(gray.rows(), gray.cols(), 21)
                val block = if (maxBlock % 2 == 0) maxBlock - 1 else maxBlock
                require(block >= 3) { "OCR region too small" }
                Imgproc.adaptiveThreshold(gray, binary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 10.0)
                // No morphology by default: a 2x2 opening can erase decimal points/thin digits.
                output = Bitmap.createBitmap(binary.cols(), binary.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(binary, output)
            }
            return PreparedBitmap(checkNotNull(output), scale, top).also { output = null }
        } finally {
            mats.asReversed().forEach { it.release() }
            output?.recycle()
            if (cropped !== source && cropped !== scaled) cropped?.recycle()
            if (scaled !== source) scaled?.recycle()
        }
    }

    internal data class PreparedBitmap(val bitmap: Bitmap, val scale: Float, val sourceTop: Int) : AutoCloseable {
        override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}
