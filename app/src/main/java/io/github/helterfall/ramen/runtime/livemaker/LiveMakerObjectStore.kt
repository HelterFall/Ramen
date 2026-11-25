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
    private val cabinets = linkedMapOf<String, List<Snapshot>>()

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

    /**
     * Save the selected screen objects into an in-memory LiveMaker cabinet.
     *
     * When [inverse] is true, every object except [targetNames] is selected, matching
     * SaveCabinet's Act flag. The runtime has no filesystem save UI yet, so cabinet
     * snapshots remain scoped to this running game session.
     */
    @Synchronized
    fun saveCabinet(
        cabinetName: String = DEFAULT_CABINET,
        targetNames: Set<String> = emptySet(),
        inverse: Boolean = false
    ) {
        val selected = objects.values.filter { stored ->
            if (inverse) stored.name !in targetNames else stored.name in targetNames
        }
        cabinets[cabinetName] = selected.map { stored ->
            Snapshot(stored.name, stored.componentTypeId, propertiesWithDefaults(stored))
        }
    }

    /** Restore objects previously captured by [saveCabinet]. */
    @Synchronized
    fun loadCabinet(
        cabinetName: String = DEFAULT_CABINET,
        targetNames: Set<String> = emptySet(),
        inverse: Boolean = false
    ) {
        val saved = cabinets[cabinetName] ?: return
        saved.filter { snapshot ->
            if (targetNames.isEmpty()) true
            else if (inverse) snapshot.name !in targetNames else snapshot.name in targetNames
        }.forEach { snapshot ->
            val stored = objects.getOrPut(snapshot.name) {
                StoredObject(snapshot.name, snapshot.componentTypeId, linkedMapOf())
            }
            stored.componentTypeId = snapshot.componentTypeId
            stored.properties.clear()
            stored.properties.putAll(snapshot.properties)
            stored.properties[PR_NAME] = snapshot.name
        }
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
        PR_PARENT, PR_SOURCE, PR_ONNOTIFY, PR_ONPLAYEND -> ""
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
        const val PR_COLOR = 9
        const val PR_BORDERWIDTH = 10
        const val PR_BORDERCOLOR = 11
        const val PR_ONNOTIFY = 34
        const val PR_ONLBTNDOWN = 37
        const val PR_ONLBTNUP = 38
        const val PR_ONPLAYEND = 44
        const val PR_ONSELECT = 73
        const val PR_TEXT = 51
        const val PR_SELECTED = 88

        private const val DEFAULT_CABINET = "__live_maker_scene"

        private val DEFAULT_PROPERTIES: Map<Int, Any?> = mapOf(
            PR_PARENT to "",
            PR_SOURCE to "",
            PR_LEFT to 0,
            PR_TOP to 0,
            PR_WIDTH to 0,
            PR_HEIGHT to 0,
            PR_ZOOMX to 100,
            PR_ZOOMY to 100,
            PR_COLOR to 0x44000000,
            PR_BORDERWIDTH to 0,
            PR_BORDERCOLOR to 0xFFFFFFFF.toInt(),
            PR_ALPHA to 255,
            PR_TEXT to "",
            PR_SELECTED to false,
            PR_PRIORITY to 0,
            PR_VISIBLE to true,
            PR_ROWCOUNT to 0,
            PR_IGNOREMOUSE to false,
            PR_REVERSE to false
        )
    }
}
