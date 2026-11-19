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
 * Brings in lights, switches, covers, climate and media players. Areas from HA become suggested rooms.
 *
 * Two addresses: the home one (fast, works without internet) and a remote one (e.g. https://ha.rozanahome.pl)
 * for when the phone is away from the home Wi-Fi. The home address is tried first; when it can't be reached
 * the remote one is used, and for the next few minutes it goes first so nothing waits on the home address.
 */
class HomeAssistantSource(private val store: Store) : DeviceSource {
    override val provider = Provider.HOMEASSISTANT

    override fun isConfigured() = (local().isNotBlank() || remote().isNotBlank()) && store.get(K_TOKEN).isNotBlank()

    private fun normalize(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = (if (u.contains(":8123") || u.first().isDigit()) "http://" else "https://") + u
        return u
    }

    private fun local() = normalize(store.get(K_URL))
    private fun remote() = normalize(store.get(K_URL_EXT))

    /** Addresses to try, in order. */
    private fun bases(): List<String> {
        val l = local()
        val r = remote()
        if (l.isBlank() || r.isBlank() || l == r) return listOf(l.ifBlank { r })
        val homeRecentlyFailed = System.currentTimeMillis() - lastLocalFail < 5 * 60_000
        return if (homeRecentlyFailed) listOf(r, l) else listOf(l, r)
    }

    private fun req(base: String, path: String) = Request.Builder().url(base + path)
        .header("Authorization", "Bearer " + store.get(K_TOKEN).trim())
        .header("Content-Type", "application/json")

    /** Runs the request against the home address, then the remote one if the first can't be reached. */
    private suspend fun call(path: String, body: JSONObject? = null): String {
        val list = bases()
        var last: IOException? = null
        for ((i, base) in list.withIndex()) {
            val isLocal = base == local() && remote().isNotBlank() && list.size > 1
            val r = req(base, path).let { if (body != null) it.post(body.toString().toRequestBody(Net.JSON)) else it }.build()
            try {
                val out = Net.call(r, if (isLocal) QUICK else Net.client)
                if (isLocal) lastLocalFail = 0
                return out
            } catch (e: IOException) {
                val msg = e.message ?: ""
                if (msg.startsWith("HTTP 401")) throw IOException("Home Assistant: zły token")
                if (msg.startsWith("HTTP ")) throw e // HA answered, so the address is fine
                if (isLocal) lastLocalFail = System.currentTimeMillis()
                last = if (i == list.lastIndex && list.size > 1) IOException("Home Assistant nieosiągalny (w domu i zdalnie)") else e
            }
        }
        throw last ?: IOException("Home Assistant: brak adresu")
    }

    private suspend fun get(path: String): String = call(path)

    private suspend fun post(path: String, body: JSONObject): String = call(path, body)

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
            // Sensors, scenes, scripts, buttons and HA automations are left in Home Assistant.
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
            else -> return
        }
        post("/api/services/$domain/$service", data)
    }

    /** Sends a spoken sentence to HA's Polish Assist; returns its answer and the names of the devices it switched. */
    suspend fun converse(text: String): Pair<String, List<String>> {
        val o = JSONObject(post("/api/conversation/process", JSONObject().put("text", text).put("language", "pl")))
        val resp = o.optJSONObject("response") ?: JSONObject()
        val answer = resp.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty().ifBlank { "Gotowe" }
        val ok = resp.optJSONObject("data")?.optJSONArray("success") ?: JSONArray()
        val names = (0 until ok.length()).mapNotNull { ok.optJSONObject(it) }
            .filter { it.optString("type") == "entity" }
            .map { it.optString("name") }.filter { it.isNotBlank() }
        return answer to names
    }

    /** Quick check for the settings screen: checks both addresses separately and says which work. */
    suspend fun test(): String {
        if (store.get(K_TOKEN).isBlank()) return "Brak tokenu"
        val parts = mutableListOf<String>()
        if (local().isNotBlank()) parts += "W domu: " + one(local())
        if (remote().isNotBlank()) parts += "Zdalnie: " + one(remote())
        return parts.joinToString("\n")
    }

    private suspend fun one(base: String): String = try {
        val o = JSONObject(Net.call(req(base, "/api/config").build()))
        "✓ " + o.optString("location_name").ifBlank { "Home Assistant" } + " " + o.optString("version")
    } catch (e: Exception) {
        val m = e.message ?: e.toString()
        "✗ " + if (m.startsWith("HTTP 401")) "zły token" else m.take(80)
    }

    companion object {
        const val K_URL = "ha_url"
        const val K_URL_EXT = "ha_url_ext"
        const val K_TOKEN = "ha_token"

        @Volatile private var lastLocalFail = 0L

        /** Short connect timeout for the home address, so being away from home costs ~2 s once, not 10 s. */
        private val QUICK = Net.client.newBuilder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
    }
}
