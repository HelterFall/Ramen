package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnVisualLayerConfigurationStoreTest {

    @Test
    fun snapshotIsIndependentAndCanBeCommittedAtDialogueBoundary() {
        val store = SystemNnnVisualLayerConfigurationStore()
        val configuration = SystemNnnVisualLayerConfiguration(
            sourceRect = SystemNnnNativeRect(10, 20, 100, 200),
            destinationRect = SystemNnnNativeRect(40, 50, 100, 200),
            effectId = 52,
            effectParameters = listOf(24),
            effectCommandOffset = 128L
        )
        store.replaceWith(mapOf(3 to configuration))

        val pending = store.snapshot()
        pending.remove(3)
        pending[5] = configuration.copy(effectId = 40)

        assertEquals(configuration, store.snapshot()[3])
        assertFalse(store.snapshot().containsKey(5))

        store.replaceWith(pending)

        assertFalse(store.snapshot().containsKey(3))
        assertEquals(40, store.snapshot()[5]?.effectId)
    }

    @Test
    fun clearingTheStoreRemovesAllLayerVisualConfiguration() {
        val store = SystemNnnVisualLayerConfigurationStore()
        store.replaceWith(
            mapOf(
                0 to SystemNnnVisualLayerConfiguration(effectId = 9, effectParameters = listOf(10)),
                7 to SystemNnnVisualLayerConfiguration(destinationRect = SystemNnnNativeRect(0, 0, 10, 10))
            )
        )

        store.clear()

        assertTrue(store.snapshot().isEmpty())
        assertNull(store.snapshot()[0])
        assertNull(store.snapshot()[7])
    }
}
