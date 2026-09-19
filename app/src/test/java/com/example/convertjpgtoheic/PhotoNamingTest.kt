package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The output name decides where the HEIC lands and whether the original is considered already
 * converted, so the awkward names matter more than they look.
 */
class PhotoNamingTest {

    @Test
    fun `swaps a normal extension`() {
        assertEquals("IMG_1234.heic", PhotoNaming.heicName("IMG_1234.jpg"))
    }

    @Test
    fun `keeps everything before the last dot`() {
        assertEquals("holiday.2024.05.heic", PhotoNaming.heicName("holiday.2024.05.jpg"))
    }

    @Test
    fun `handles a name with no extension at all`() {
        assertEquals("screenshot.heic", PhotoNaming.heicName("screenshot"))
    }

    @Test
    fun `does not turn a leading-dot name into a hidden file`() {
        // ".jpg" has an empty part before the dot; naively trimming yields the hidden file ".heic".
        assertEquals(".jpg.heic", PhotoNaming.heicName(".jpg"))
    }

    @Test
    fun `is case preserving for the base name`() {
        assertEquals("MiXeD_Case.heic", PhotoNaming.heicName("MiXeD_Case.JPEG"))
    }

    @Test
    fun `handles uppercase extensions`() {
        assertEquals("DSC_0001.heic", PhotoNaming.heicName("DSC_0001.JPG"))
    }

    // The images collection only accepts DCIM/ and Pictures/ as the top-level folder; anything
    // else makes the insert throw, which used to abort the entire run.

    @Test
    fun `leaves a camera folder alone`() {
        assertEquals("DCIM/Camera/", PhotoNaming.targetRelativePath("DCIM/Camera/"))
    }

    @Test
    fun `leaves a pictures folder alone`() {
        assertEquals("Pictures/Screenshots/", PhotoNaming.targetRelativePath("Pictures/Screenshots/"))
    }

    @Test
    fun `nests a downloads folder under pictures rather than failing the insert`() {
        assertEquals("Pictures/Download/", PhotoNaming.targetRelativePath("Download/"))
    }

    @Test
    fun `keeps the sub-folder structure when relocating`() {
        assertEquals(
            "Pictures/Download/Telegram/",
            PhotoNaming.targetRelativePath("Download/Telegram/"),
        )
    }

    @Test
    fun `accepts the allowed folders whatever their case`() {
        assertEquals("dcim/Camera/", PhotoNaming.targetRelativePath("dcim/Camera/"))
    }

    @Test
    fun `falls back to pictures for an empty path`() {
        assertEquals("Pictures/", PhotoNaming.targetRelativePath(""))
    }

    @Test
    fun `always returns a trailing slash`() {
        assertEquals("DCIM/Camera/", PhotoNaming.targetRelativePath("DCIM/Camera"))
    }
}
