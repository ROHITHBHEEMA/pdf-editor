package app.pdfeditor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AffineTest {

    // The wrapper matrix C = M*T*M^-1, executed as cm, must move the element by
    // T in page space: the wrapped CTM C*M maps the local origin to M's origin
    // shifted by T.
    @Test
    fun wrapperMatrixMovesInPageSpace() {
        val m = Affine.translate(100f, 200f)
        val t = Affine.translate(10f, 0f)
        val c = m.then(t).then(m.inverse()!!)
        val ctm = c.then(m)
        assertEquals(110f, ctm.mapX(0f, 0f), 1e-3f)
        assertEquals(200f, ctm.mapY(0f, 0f), 1e-3f)
    }

    @Test
    fun inverseRoundTrips() {
        val m = Affine(2f, 0f, 0f, 3f, 10f, 20f)
        val id = m.then(m.inverse()!!)
        assertEquals(1f, id.a, 1e-4f); assertEquals(0f, id.b, 1e-4f)
        assertEquals(0f, id.c, 1e-4f); assertEquals(1f, id.d, 1e-4f)
        assertEquals(0f, id.e, 1e-3f); assertEquals(0f, id.f, 1e-3f)
    }

    @Test
    fun scaleAboutKeepsAnchorFixed() {
        val t = Affine.scaleAbout(2f, 2f, 50f, 60f)
        assertEquals(50f, t.mapX(50f, 60f), 1e-3f)
        assertEquals(60f, t.mapY(50f, 60f), 1e-3f)
        assertEquals(70f, t.mapX(60f, 60f), 1e-3f)
    }

    @Test
    fun degenerateInverseIsNull() {
        assertNull(Affine(0f, 0f, 0f, 0f, 5f, 5f).inverse())
    }
}
