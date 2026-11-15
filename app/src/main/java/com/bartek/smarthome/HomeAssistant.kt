package com.bartek.smarthome

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Home Assistant over its REST API (address + long-lived access token).
 * Brings in lights, switches, covers, climate, media players, temperature/humidity/motion/door sensors,
 * and scenes, scripts, buttons and HA automations as one-tap tiles. Areas from HA become suggested rooms.
 */
class HomeAssistantSource(private val store: Store) : DeviceSource {
    override val provider = Provider.HOMEASSISTANT

    override fun isConfigured() = store.get(K_URL).isNotBlank() && store.get(K_TOKEN).isNotBlank()

    private fun base(): String {
        var u = store.get(K_URL).trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u
    }

    private fun req(path: String) = Request.Builder().url(base() + path)
        .header("Authorization", "Bearer " + store.get(K_TOKEN).trim())
        .header("Content-Type", "application/json")

    private suspend fun get(path: String): String = try {
        Net.call(req(path).build())
    } catch (e: IOException) {
        if (e.message?.startsWith("HTTP 401") == true) throw IOException("Home Assistant: zły token") else throw e
    }

    private suspend fun post(path: String, body: JSONObject): String = Net.call(req(path).post(body.toString().toRequestBody(Net.JSON)).build())

    /** entity_id → area name, in one request through HA's template engine. */
    private suspend fun areas(): Map<String, String> = try {
        val tpl = "{% for s in states %}{{ s.entity_id }}|{{ area_name(s.entity_id) or '' }}\n{% endfor %}"
        post("/api/template", JSONObject().put("template", tpl))
            .lines().mapNotNull { l -> l.split("|", limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1].trim() } }
            .toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    override suspend fun devices(): List<Device> = coroutineScope {
        val area = async { areas() }
        val arr = JSONArray(get("/api/states"))
        val rooms = area.await()
        (0 until arr.length()).mapNotNull { i -> toDevice(arr.getJSONObject(i), rooms) }
    }

    override suspend fun refresh(list: List<Device>): List<Device> = coroutineScope {
        list.map { d ->
            async {
                try {
                    toDevice(JSONObject(get("/api/states/" + URLEncoder.encode(d.id, "UTF-8"))), emptyMap())?.copy(room = d.room) ?: d.copy(online = false)
                } catch (e: Exception) {
                    d.copy(online = false, extra = e.message)
                }
            }
        }.awaitAll()
    }

    private fun toDevice(o: JSONObject, rooms: Map<String, String>): Device? {
        val id = o.optString("entity_id")
        val domain = id.substringBefore(".")
        val state = o.optString("state")
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val name = a.optString("friendly_name").ifBlank { id }
        val online = state != "unavailable"
        val cls = a.optString("device_class")
        val room = rooms[id] ?: ""
        fun dev(kind: Kind) = Device(provider = provider, id = id, name = name, kind = kind, model = domain, online = online, room = room, meta = mapOf("domain" to domain))

        return when (domain) {
            "light" -> {
                val modes = a.optJSONArray("supported_color_modes")?.let { m -> (0 until m.length()).map { m.getString(it) } } ?: emptyList()
                val dimmable = modes.any { it != "onoff" } || a.has("brightness")
                val color = modes.any { it in setOf("hs", "rgb", "rgbw", "rgbww", "xy") }
                val rgb = a.optJSONArray("rgb_color")
                dev(Kind.LIGHT).copy(
                    on = state == "on", canPower = true, canBrightness = dimmable, canColor = color,
                    brightness = if (a.has("brightness") && !a.isNull("brightness")) (a.optInt("brightness") * 100 / 255.0).roundToInt().coerceIn(1, 100) else null,
                    color = rgb?.let { (it.optInt(0) shl 16) or (it.optInt(1) shl 8) or it.optInt(2) },
                )
            }
            "switch", "input_boolean", "fan" -> dev(Kind.PLUG).copy(on = state == "on", canPower = true)
            "cover" -> dev(Kind.CURTAIN).copy(
                canPosition = true,
                position = if (a.has("current_position")) a.optInt("current_position") else if (state == "open") 100 else if (state == "closed") 0 else null,
            )
            "media_player" -> dev(Kind.TV).copy(
                on = if (online) state != "off" && state != "standby" else null, canPower = true,
                meta = mapOf("domain" to domain, "ir" to "1"), // power only, no Android TV remote
            )
            "climate" -> dev(Kind.SENSOR).copy(
                on = state != "off", canPower = true,
                temperature = a.optDouble("current_temperature").takeIf { !it.isNaN() },
                humidity = a.optDouble("current_humidity").takeIf { !it.isNaN() },
                extra = state,
            )
            "sensor" -> when (cls) {
                "temperature" -> dev(Kind.SENSOR).copy(temperature = state.toDoubleOrNull())
                "humidity" -> dev(Kind.SENSOR).copy(humidity = state.toDoubleOrNull())
                else -> null // HA has hundreds of other sensors; skipped to keep the list readable
            }
            "binary_sensor" -> when (cls) {
                "motion", "occupancy", "presence" -> dev(Kind.SENSOR).copy(extra = if (state == "on") "Wykryto ruch" else "Brak ruchu")
                "door", "window", "opening", "garage_door" -> dev(Kind.SENSOR).copy(extra = if (state == "on") "Otwarte" else "Zamknięte")
                else -> null
            }
            "scene", "script", "button" -> dev(Kind.BOT).copy(room = room.ifBlank { "Sceny Home Assistant" })
            "automation" -> dev(Kind.PLUG).copy(on = state == "on", canPower = true, room = room.ifBlank { "Automatyzacje Home Assistant" })
            else -> null
        }
    }

    override suspend fun send(device: Device, cmd: Command) {
        val domain = device.meta["domain"] ?: device.id.substringBefore(".")
        val data = JSONObject().put("entity_id", device.id)
        val service = when (cmd) {
            is Command.Power -> when (domain) {
                "cover" -> if (cmd.on) "open_cover" else "close_cover"
                else -> if (cmd.on) "turn_on" else "turn_off"
            }
            is Command.Brightness -> { data.put("brightness_pct", cmd.value.coerceIn(1, 100)); "turn_on" }
            is Command.Color -> {
                data.put("rgb_color", JSONArray().put((cmd.rgb shr 16) and 255).put((cmd.rgb shr 8) and 255).put(cmd.rgb and 255))
                "turn_on"
            }
            is Command.Position -> when (cmd.openPercent) {
                100 -> "open_cover"
                0 -> "close_cover"
                else -> { data.put("position", cmd.openPercent); "set_cover_position" }
            }
            is Command.Press -> when (domain) {
                "button" -> "press"
                "automation" -> "trigger"
                else -> "turn_on" // scene, script
            }
            else -> return
        }
        try {
            post("/api/services/$domain/$service", data)
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 401") == true) throw IOException("Home Assistant: zły token") else throw e
        }
    }

    /** Quick check for the settings screen: returns HA's version or throws. */
    suspend fun test(): String {
        val o = JSONObject(get("/api/config"))
        return o.optString("location_name").ifBlank { "Home Assistant" } + " " + o.optString("version")
    }

    companion object {
        const val K_URL = "ha_url"
        const val K_TOKEN = "ha_token"
    }
}
