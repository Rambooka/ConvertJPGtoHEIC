package com.example.convertjpgtoheic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The HEIC encoder on the phone's real HEVC encoder and GPU, on generated pictures. */
@RunWith(AndroidJUnit4::class)
class HeicEncoderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var encoder: HeicEncoder

    @Before
    fun setUp() {
        assumeTrue(EncoderSupport.hasHevcEncoder)
        dir = File(context.cacheDir, "device-test-heic").apply { deleteRecursively(); mkdirs() }
        encoder = HeicEncoder(context, PhotoRepository(context))
    }

    @After
    fun tearDown() {
        if (::encoder.isInitialized) {
            encoder.clearStaging()
            encoder.release()
        }
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun picture(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat): SourcePhoto {
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(format, 95, it) }
        val now = System.currentTimeMillis()
        return SourcePhoto(
            uri = Uri.fromFile(file),
            displayName = name,
            relativePath = "Pictures/",
            volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
            bucketName = null,
            sizeBytes = file.length(),
            dateTakenMs = now,
            dateModifiedSec = now / 1000,
            dateAddedSec = now / 1000,
        )
    }

    private fun encode(photo: SourcePhoto, rotation: Int = 0): Bitmap {
        val metadata = JpegMetadata(MinimalExif.forDate(photo.dateTakenMs), false, false, false)
        val result = encoder.encodeToStaging(photo, metadata, 80, rotation, allowShrink = true)
        assertTrue("encode failed: $result", result is EncodeResult.Success)
        val file = (result as EncodeResult.Success).file!!
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun scene(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.rgb(30, 90, 160))
                drawRect(0f, 0f, width / 2f, height / 2f, Paint().apply { color = Color.rgb(220, 200, 40) })
            }
        }

    @Test
    fun jpegBecomesAHeicOfTheSameSize() {
        val decoded = encode(picture("plain.jpg", scene(1200, 800), Bitmap.CompressFormat.JPEG))
        assertEquals(1200, decoded.width)
        assertEquals(800, decoded.height)
    }

    @Test
    fun rotationIsAppliedByTheContainer() {
        // Android's decoder applies the HEIF rotation, as every viewer that matters does.
        val decoded = encode(picture("turned.jpg", scene(1200, 800), Bitmap.CompressFormat.JPEG), rotation = 90)
        assertEquals(800, decoded.width)
        assertEquals(1200, decoded.height)
    }

    @Test
    fun transparentPngComesOutWhiteNotBlack() {
        val png = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            Canvas(this).drawRect(150f, 150f, 250f, 250f, Paint().apply { color = Color.RED })
        }
        val decoded = encode(picture("clear.png", png, Bitmap.CompressFormat.PNG))
        val corner = decoded.getPixel(20, 20)
        assertTrue("transparent area came out ${Integer.toHexString(corner)}",
            Color.red(corner) > 235 && Color.green(corner) > 235 && Color.blue(corner) > 235)
        val middle = decoded.getPixel(200, 200)
        assertTrue("opaque area came out ${Integer.toHexString(middle)}", Color.red(middle) > 200 && Color.green(middle) < 60)
    }
}
