package io.github.helterfall.ramen.runtime.livemaker

/**
 * Platform-neutral projection of a LiveMaker object used by the Android scene canvas.
 * Bitmap-backed ImgNew objects are drawn by the media renderer; other visual components
 * are represented by their geometry, colors, text, and interaction properties.
 */
internal data class LiveMakerSceneComponent(
    val objectName: String,
    val componentTypeId: Int?,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val priority: Float,
    val color: Int,
    val borderColor: Int,
    val borderWidth: Float,
    val text: String,
    val visible: Boolean,
    val selected: Boolean,
    val clickable: Boolean,
    val source: String?
) {
    val isImage: Boolean
        get() = componentTypeId == COMPONENT_IMG_NEW

    val isRenderable: Boolean
        get() = componentTypeId?.let { it in RENDERED_COMPONENT_TYPES } == true

    companion object {
        const val COMPONENT_BOX_NEW = 8
        const val COMPONENT_IMG_NEW = 9
        const val COMPONENT_MES_NEW = 10
        const val COMPONENT_CINEMA = 23
        const val COMPONENT_CAPTION = 24
        const val COMPONENT_MENU = 25
        const val COMPONENT_BUTTON = 30
        const val COMPONENT_EDIT_NEW = 43
        const val COMPONENT_TILE_NEW = 50
        const val COMPONENT_SLIDER_NEW = 51
        const val COMPONENT_SCROLLBAR_NEW = 52
        const val COMPONENT_CG_CAPTION = 54
        const val COMPONENT_PREV_MENU_NEW = 56

        private val RENDERED_COMPONENT_TYPES = setOf(
            COMPONENT_BOX_NEW,
            COMPONENT_MES_NEW,
            COMPONENT_CINEMA,
            COMPONENT_CAPTION,
            COMPONENT_MENU,
            COMPONENT_BUTTON,
            COMPONENT_EDIT_NEW,
            COMPONENT_TILE_NEW,
            COMPONENT_SLIDER_NEW,
            COMPONENT_SCROLLBAR_NEW,
            COMPONENT_CG_CAPTION,
            COMPONENT_PREV_MENU_NEW
        )

        private val INTERACTIVE_COMPONENT_TYPES = setOf(
            COMPONENT_MENU,
            COMPONENT_BUTTON,
            COMPONENT_SLIDER_NEW,
            COMPONENT_SCROLLBAR_NEW,
            COMPONENT_PREV_MENU_NEW
        )

        fun from(snapshot: LiveMakerObjectStore.Snapshot): LiveMakerSceneComponent {
            val properties = snapshot.properties
            fun number(propertyId: Int, fallback: Float): Float =
                (properties[propertyId] as? Number)?.toFloat() ?: fallback
            fun int(propertyId: Int, fallback: Int): Int =
                (properties[propertyId] as? Number)?.toInt() ?: fallback
            fun flag(propertyId: Int, fallback: Boolean): Boolean =
                when (val value = properties[propertyId]) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    is String -> value.isNotBlank() && value != "0"
                    else -> fallback
                }

            val alphaValue = number(LiveMakerObjectStore.PR_ALPHA, 255f)
            val handlers = listOf(
                LiveMakerObjectStore.PR_ONLBTNDOWN,
                LiveMakerObjectStore.PR_ONLBTNUP,
                LiveMakerObjectStore.PR_ONSELECT
            )
            val hasHandler = handlers.any { propertyId ->
                properties[propertyId]?.let { value ->
                    when (value) {
                        is Number -> value.toInt() != 0
                        is Boolean -> value
                        else -> value.toString().isNotBlank()
                    }
                } == true
            }
            return LiveMakerSceneComponent(
                objectName = snapshot.name,
                componentTypeId = snapshot.componentTypeId,
                left = number(LiveMakerObjectStore.PR_LEFT, 0f),
                top = number(LiveMakerObjectStore.PR_TOP, 0f),
                width = number(LiveMakerObjectStore.PR_WIDTH, 1f).coerceAtLeast(1f),
                height = number(LiveMakerObjectStore.PR_HEIGHT, 1f).coerceAtLeast(1f),
                alpha = (if (alphaValue > 1f) alphaValue / 255f else alphaValue).coerceIn(0f, 1f),
                priority = number(LiveMakerObjectStore.PR_PRIORITY, 0f),
                color = int(LiveMakerObjectStore.PR_COLOR, 0x44000000),
                borderColor = int(LiveMakerObjectStore.PR_BORDERCOLOR, 0xFFFFFFFF.toInt()),
                borderWidth = number(LiveMakerObjectStore.PR_BORDERWIDTH, 0f).coerceAtLeast(0f),
                text = properties[LiveMakerObjectStore.PR_TEXT]?.toString().orEmpty(),
                visible = flag(LiveMakerObjectStore.PR_VISIBLE, true),
                selected = flag(LiveMakerObjectStore.PR_SELECTED, false),
                clickable = snapshot.componentTypeId?.let { it in INTERACTIVE_COMPONENT_TYPES } == true || hasHandler,
                source = properties[LiveMakerObjectStore.PR_SOURCE]?.toString()
                    ?.takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Shared component-command decoder used by both the Android host and JVM regression tests.
 * LSB component properties are paired by the parser using the per-command parameter map.
 */
internal object LiveMakerSceneCommandAdapter {
    val componentCreationCommandIds: Set<Int> = setOf(
        8, 9, 10, 11, 12, 23, 24, 25, 30, 36, 37, 42, 43, 44,
        48, 49, 50, 51, 52, 53, 54, 56
    )

    fun readProperties(
        command: io.github.helterfall.ramen.inspection.LiveMakerCommand,
        evaluate: (io.github.helterfall.ramen.inspection.LiveMakerExpression) -> Any?
    ): Map<Int, Any?> = linkedMapOf<Int, Any?>().apply {
        command.componentPropertyIds.forEachIndexed { index, propertyId ->
            command.expressions.getOrNull(index)?.let { expression ->
                put(propertyId, evaluate(expression))
            }
        }
    }

    fun registerComponent(
        command: io.github.helterfall.ramen.inspection.LiveMakerCommand,
        objectStore: LiveMakerObjectStore,
        evaluate: (io.github.helterfall.ramen.inspection.LiveMakerExpression) -> Any?
    ): Map<Int, Any?> {
        val properties = readProperties(command, evaluate)
        if (command.typeId in componentCreationCommandIds) {
            val name = properties[LiveMakerObjectStore.PR_NAME]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            if (name != null) objectStore.create(name, command.typeId, properties)
        }
        return properties
    }
}
