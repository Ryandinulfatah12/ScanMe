package com.ryan.scanme.core.imaging.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuadTest {
    private val tl = Vec2(10.0, 20.0)
    private val tr = Vec2(110.0, 15.0)
    private val br = Vec2(120.0, 160.0)
    private val bl = Vec2(5.0, 150.0)

    @Test
    fun `orders shuffled points clockwise from top-left`() {
        val q = Quad.fromUnordered(listOf(br, tl, bl, tr))
        assertEquals(Quad(tl, tr, br, bl), q)
    }

    @Test
    fun `orders a page rotated about 40 degrees`() {
        // Diamond-ish: top-left corner is the one with the smallest x+y.
        val pts = listOf(Vec2(100.0, 0.0), Vec2(200.0, 90.0), Vec2(110.0, 200.0), Vec2(5.0, 105.0))
        val q = Quad.fromUnordered(pts.shuffled(kotlin.random.Random(1)))
        assertEquals(Vec2(100.0, 0.0), q.tl)
        assertEquals(Vec2(200.0, 90.0), q.tr)
        assertEquals(Vec2(110.0, 200.0), q.br)
        assertEquals(Vec2(5.0, 105.0), q.bl)
    }

    @Test
    fun `area convexity and normalization`() {
        val square = Quad(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(10.0, 10.0), Vec2(0.0, 10.0))
        assertEquals(100.0, square.area(), 1e-9)
        assertTrue(square.isConvex())
        val bowtie = Quad(Vec2(0.0, 0.0), Vec2(10.0, 10.0), Vec2(10.0, 0.0), Vec2(0.0, 10.0))
        assertFalse(bowtie.isConvex())
        assertEquals(square, square.toNormalized(20, 40).toPixels(20, 40))
        square.interiorAngles().forEach { assertEquals(Math.PI / 2, it, 1e-9) }
    }
}
