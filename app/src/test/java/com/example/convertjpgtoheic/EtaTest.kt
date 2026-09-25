package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtaTest {

    private var clock = 1_000_000L
    private fun eta(total: Int) = Eta(total) { clock }

    @Test
    fun `projects the pace so far onto what is left`() {
        val e = eta(1000)
        clock += 60_000 // a minute for the first 250
        assertEquals(180_000L, e.remainingMs(250)) // three more minutes for the other 750
    }

    @Test
    fun `says nothing until there is enough to judge by`() {
        val e = eta(1000)
        clock += 60_000
        assertNull(e.remainingMs(5)) // too few done
        val early = eta(1000)
        clock += 1_000
        assertNull(early.remainingMs(100)) // too little time elapsed
    }

    @Test
    fun `says nothing once the step is done`() {
        val e = eta(100)
        clock += 60_000
        assertNull(e.remainingMs(100))
    }

    @Test
    fun `the refresh step at the pace seen on the phone`() {
        // ~3.7 files a second, 8,281 left of 13,000: about 37 minutes.
        val e = eta(13_000)
        val done = 13_000 - 8_281
        clock += (done / 3.7 * 1000).toLong()
        assertEquals(37, (e.remainingMs(done)!! / 60_000).toInt())
    }
}
