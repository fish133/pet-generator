package com.doubao.pet.littlewhale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object GifUtils {

    fun extractGifFrames(
        context: Context,
        gifUri: Uri,
        outputDir: File,
        maxFrames: Int = 15,
        targetSize: Int = 512
    ): List<File> {
        if (!outputDir.exists()) outputDir.mkdirs()
        val inputStream = context.contentResolver.openInputStream(gifUri) ?: return emptyList()
        val movie = Movie.decodeStream(inputStream)
        inputStream.close()
        if (movie == null) return emptyList()

        val totalFrames = estimateFrameCount(movie)
        val frameIndices = if (totalFrames <= maxFrames) {
            (0 until totalFrames).toList()
        } else {
            (0 until maxFrames).map { (it * totalFrames / maxFrames).coerceAtMost(totalFrames - 1) }
        }

        val savedFiles = mutableListOf<File>()
        val width = movie.width()
        val height = movie.height()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        for ((index, frameIdx) in frameIndices.withIndex()) {
            val timeMs = if (totalFrames > 1) {
                (frameIdx * movie.duration() / totalFrames).coerceAtMost(movie.duration() - 1)
            } else 0
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            movie.setTime(timeMs)
            movie.draw(canvas, 0f, 0f)

            val scaled = if (width != targetSize || height != targetSize) {
                val scale = minOf(targetSize.toFloat() / width, targetSize.toFloat() / height)
                val sw = (width * scale).toInt()
                val sh = (height * scale).toInt()
                val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val c = Canvas(result)
                c.drawBitmap(Bitmap.createScaledBitmap(bitmap, sw, sh, true),
                    ((targetSize - sw) / 2).toFloat(), ((targetSize - sh) / 2).toFloat(), null)
                result
            } else bitmap.copy(Bitmap.Config.ARGB_8888, false)

            val outFile = File(outputDir, "frame_${index + 1}.png")
            FileOutputStream(outFile).use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (scaled != bitmap) scaled.recycle()
            savedFiles.add(outFile)
        }
        bitmap.recycle()
        return savedFiles
    }

    private fun estimateFrameCount(movie: Movie): Int {
        val duration = movie.duration()
        if (duration <= 0) return 1
        val width = movie.width()
        val height = movie.height()
        val bmp1 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c1 = Canvas(bmp1)
        val c2 = Canvas(bmp2)
        var frameCount = 0
        var t = 0
        val step = 30
        while (t < duration) {
            c2.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            movie.setTime(t)
            movie.draw(c2, 0f, 0f)
            if (t == 0 || !bitmapsEqual(bmp1, bmp2)) {
                frameCount++
                c1.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
                c1.drawBitmap(bmp2, 0f, 0f, null)
            }
            t += step
        }
        bmp1.recycle()
        bmp2.recycle()
        return frameCount.coerceIn(1, 60)
    }

    private fun bitmapsEqual(b1: Bitmap, b2: Bitmap): Boolean {
        if (b1.width != b2.width || b1.height != b2.height) return false
        for (y in 0 until b1.height step 4) {
            for (x in 0 until b1.width step 4) {
                if (b1.getPixel(x, y) != b2.getPixel(x, y)) return false
            }
        }
        return true
    }

    fun getGifRootDir(context: Context): File = File(context.filesDir, "pet_gifs").apply { mkdirs() }

    fun getGifList(context: Context): List<String> {
        val root = getGifRootDir(context)
        return root.listFiles { f -> f.isDirectory && f.listFiles { _, n -> n.startsWith("frame_") }?.isNotEmpty() == true }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    fun loadGifFrames(context: Context, gifName: String, targetSize: Int = 512): List<Bitmap> {
        val dir = File(getGifRootDir(context), gifName)
        val frames = mutableListOf<Bitmap>()
        val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var i = 1
        while (true) {
            val file = File(dir, "frame_$i.png")
            if (!file.exists()) break
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
                if (bmp != null) {
                    val scaled = if (bmp.width != targetSize || bmp.height != targetSize) {
                        val scale = minOf(targetSize.toFloat() / bmp.width, targetSize.toFloat() / bmp.height)
                        val sw = (bmp.width * scale).toInt()
                        val sh = (bmp.height * scale).toInt()
                        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                        val c = android.graphics.Canvas(result)
                        c.drawBitmap(Bitmap.createScaledBitmap(bmp, sw, sh, true),
                            ((targetSize - sw) / 2).toFloat(), ((targetSize - sh) / 2).toFloat(), null)
                        bmp.recycle()
                        result
                    } else bmp
                    frames.add(scaled)
                }
            } catch (_: Exception) {}
            i++
        }
        return frames
    }

    fun deleteGif(context: Context, gifName: String): Boolean = File(getGifRootDir(context), gifName).deleteRecursively()
}