package com.example.harmoniumhost

/**
 * The entities this remote exposes to Home Assistant over the ESPHome API. Each one knows how to
 * read its current state (called on EspServer's writer thread) and, for controls, how to apply a
 * command from HA (called on a socket thread; post to the main thread if it touches UI).
 * A null reading means "unknown right now".
 */
sealed class EspEntity(
    val key: Int, val objectId: String, val name: String, val icon: String, val category: Int,
) {
    companion object {
        const val CAT_NONE = 0
        const val CAT_CONFIG = 1
        const val CAT_DIAGNOSTIC = 2
    }
}

class EspSensor(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val unit: String = "", val deviceClass: String = "", val stateClass: Int = 1, val decimals: Int = 0,
    val read: () -> Float?,
) : EspEntity(key, objectId, name, icon, category)

class EspBinarySensor(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val deviceClass: String = "", val read: () -> Boolean?,
) : EspEntity(key, objectId, name, icon, category)

/** Text state. deviceClass "timestamp" makes HA parse an ISO-8601 time (floats can't hold one to the second). */
class EspTextSensor(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val deviceClass: String = "", val read: () -> String?,
) : EspEntity(key, objectId, name, icon, category)

class EspSwitch(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val read: () -> Boolean?, val write: (Boolean) -> Unit,
) : EspEntity(key, objectId, name, icon, category)

class EspNumber(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val min: Float, val max: Float, val step: Float, val unit: String = "",
    /** HA's NumberMode: 1 = box (type a value), 2 = slider. */
    val mode: Int = 2,
    val read: () -> Float?, val write: (Float) -> Unit,
) : EspEntity(key, objectId, name, icon, category)

class EspSelect(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val options: List<String>, val read: () -> String?, val write: (String) -> Unit,
) : EspEntity(key, objectId, name, icon, category)

/** Editable text (HA's text entity). Used for settings; HA caps text states at 255 characters. */
class EspText(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val maxLength: Int = 255, val read: () -> String?, val write: (String) -> Unit,
) : EspEntity(key, objectId, name, icon, category)

class EspButton(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val press: () -> Unit,
) : EspEntity(key, objectId, name, icon, category)

/** A still image HA can show (used for screenshots). [capture] returns JPEG bytes. */
class EspCamera(
    key: Int, objectId: String, name: String, icon: String = "", category: Int = CAT_NONE,
    val capture: () -> ByteArray?,
) : EspEntity(key, objectId, name, icon, category)
