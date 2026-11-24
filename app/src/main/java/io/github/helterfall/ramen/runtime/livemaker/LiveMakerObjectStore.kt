package io.github.helterfall.ramen.runtime.livemaker

/**
 * In-memory LiveMaker component state shared by VM functions and the Android host.
 *
 * Property IDs are the numeric PropertyType values from pylivemaker. Values explicitly
 * supplied by creation commands or SetProp override the component's neutral defaults.
 */
class LiveMakerObjectStore {
    data class Snapshot(
        val name: String,
        val componentTypeId: Int?,
        val properties: Map<Int, Any?>
    )

    private data class StoredObject(
        val name: String,
        var componentTypeId: Int?,
        val properties: MutableMap<Int, Any?>
    )

    private val objects = linkedMapOf<String, StoredObject>()

    @Synchronized
    fun create(
        name: String,
        componentTypeId: Int? = null,
        initialProperties: Map<Int, Any?> = emptyMap()
    ) {
        require(name.isNotBlank()) { "LiveMaker component name must not be blank." }
        val stored = objects.getOrPut(name) {
            StoredObject(name, componentTypeId, linkedMapOf())
        }
        if (componentTypeId != null) stored.componentTypeId = componentTypeId
        initialProperties.forEach { (propertyId, value) ->
            validatePropertyId(propertyId)
            stored.properties[propertyId] = value
        }
        stored.properties[PR_NAME] = name
    }

    @Synchronized
    fun getProperty(name: String, propertyId: Int): Any? {
        validatePropertyId(propertyId)
        val stored = objects[name] ?: run {
            create(name)
            objects.getValue(name)
        }
        return stored.properties[propertyId] ?: defaultValue(name, propertyId)
    }

    @Synchronized
    fun setProperty(name: String, propertyId: Int, value: Any?) {
        require(name.isNotBlank()) { "LiveMaker component name must not be blank." }
        validatePropertyId(propertyId)
        val stored = objects[name] ?: run {
            create(name)
            objects.getValue(name)
        }
        stored.properties[propertyId] = value
        stored.properties[PR_NAME] = name
    }

    @Synchronized
    fun remove(name: String): Boolean = objects.remove(name) != null

    @Synchronized
    fun contains(name: String): Boolean = objects.containsKey(name)

    @Synchronized
    fun snapshot(name: String): Snapshot? = objects[name]?.let { stored ->
        Snapshot(stored.name, stored.componentTypeId, propertiesWithDefaults(stored))
    }

    @Synchronized
    fun snapshotAll(): List<Snapshot> = objects.values.map { stored ->
        Snapshot(stored.name, stored.componentTypeId, propertiesWithDefaults(stored))
    }

    private fun propertiesWithDefaults(stored: StoredObject): Map<Int, Any?> =
        linkedMapOf<Int, Any?>().apply {
            putAll(DEFAULT_PROPERTIES)
            put(PR_NAME, stored.name)
            putAll(stored.properties)
        }

    private fun defaultValue(name: String, propertyId: Int): Any? = when (propertyId) {
        PR_NAME -> name
        PR_PARENT, PR_SOURCE -> ""
        PR_LEFT, PR_TOP, PR_WIDTH, PR_HEIGHT -> 0
        PR_ZOOMX, PR_ZOOMY -> 100
        PR_ALPHA -> 255
        PR_PRIORITY -> 0
        PR_VISIBLE -> true
        // Message-box properties used by LiveNovel scripts.
        PR_ROWCOUNT -> 0
        PR_IGNOREMOUSE -> false
        PR_REVERSE -> false
        else -> 0
    }

    private fun validatePropertyId(propertyId: Int) {
        require(propertyId in 0..255) { "LiveMaker property ID is outside the byte range." }
    }

    companion object {
        // PropertyType values from pylivemaker.lsb.core.PropertyType.
        const val PR_NAME = 1
        const val PR_PARENT = 2
        const val PR_SOURCE = 3
        const val PR_LEFT = 4
        const val PR_TOP = 5
        const val PR_WIDTH = 6
        const val PR_HEIGHT = 7
        const val PR_ZOOMX = 8
        const val PR_ALPHA = 12
        const val PR_PRIORITY = 13
        const val PR_VISIBLE = 48
        const val PR_ROWCOUNT = 50
        const val PR_IGNOREMOUSE = 81
        const val PR_ZOOMY = 133
        const val PR_REVERSE = 138

        private val DEFAULT_PROPERTIES: Map<Int, Any?> = mapOf(
            PR_PARENT to "",
            PR_SOURCE to "",
            PR_LEFT to 0,
            PR_TOP to 0,
            PR_WIDTH to 0,
            PR_HEIGHT to 0,
            PR_ZOOMX to 100,
            PR_ZOOMY to 100,
            PR_ALPHA to 255,
            PR_PRIORITY to 0,
            PR_VISIBLE to true,
            PR_ROWCOUNT to 0,
            PR_IGNOREMOUSE to false,
            PR_REVERSE to false
        )
    }
}
