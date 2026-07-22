package androidx.media3.exoplayer.upstream

import androidx.media3.common.SlugYardEngineConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorTest {

    @After
    fun tearDown() {
        // Reset config to stockMode to avoid test cross-pollution
        SlugYardEngineConfig.set(SlugYardEngineConfig.stockMode())
    }

    @Test
    fun testAllocationInStockMode() {
        SlugYardEngineConfig.set(SlugYardEngineConfig.stockMode())
        
        val allocationSize = 65536
        val allocator = DefaultAllocator(true, allocationSize)
        
        val allocation = allocator.allocate()
        assertNotNull("Allocation should not be null", allocation)
        assertNotNull("Allocation data (heap array) should not be null in stock mode", allocation.data)
        assertNull("Allocation buffer (direct ByteBuffer) should be null in stock mode", allocation.buffer)
        assertEquals("Allocation array size should match request", allocationSize, allocation.data!!.size)
        assertEquals("Allocation offset should be 0", 0, allocation.offset)
        
        allocator.release(allocation)
    }

    @Test
    fun testAllocationInSlugyardMode() {
        SlugYardEngineConfig.set(SlugYardEngineConfig.slugyardMode())
        
        val allocationSize = 65536
        val allocator = DefaultAllocator(true, allocationSize, 0, true)
        
        val allocation = allocator.allocate()
        assertNotNull("Allocation should not be null", allocation)
        assertNull("Allocation data (heap array) should be null in slugyard mode", allocation.data)
        assertNotNull("Allocation buffer (direct ByteBuffer) should not be null in slugyard mode", allocation.buffer)
        assertTrue("Allocation buffer should be direct in slugyard mode", allocation.buffer!!.isDirect)
        assertEquals("Allocation buffer capacity should match request", allocationSize, allocation.buffer!!.capacity())
        
        allocator.release(allocation)
    }
}
