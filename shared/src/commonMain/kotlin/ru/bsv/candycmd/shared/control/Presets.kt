package ru.bsv.candycmd.shared.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Preset(val id: String, val name: String, val settings: Settings) {
    val usable: Boolean get() = settings.recipe() != null
}

/** Unknown recipe versions remain visible for deletion, never silently upgraded. */
object PresetCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(presets: List<Preset>): String { validate(presets); return json.encodeToString(presets) }
    fun decode(text: String): List<Preset> {
        require(text.length <= 64 * 1024)
        return json.decodeFromString<List<Preset>>(text).also(::validate)
    }
    private fun validate(presets: List<Preset>) {
        require(presets.size <= 50 && presets.map { it.id }.distinct().size == presets.size)
        presets.forEach {
            require(it.id.length in 1..64 && it.name.isNotBlank() && it.name.length <= 60)
            require(it.name.none(Char::isISOControl))
        }
    }
}
