package com.bartek.smarthome

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

/** SwitchBot cloud API v1.1 (token + secret from the SwitchBot app's developer options). */
class SwitchBotSource(private val store: Store) : DeviceSource {
    override val provider = Provider.SWITCHBOT
    private val base = "https://api.switch-bot.com/v1.1"

    override fun isConfigured() = store.get(K_TOKEN).isNotBlank() && store.get(K_SECRET).isNotBlank()

    private suspend fun api(path: String, body: JSONObject? = null): JSONObject {
        val token = store.get(K_TOKEN).trim()
        val secret = store.get(K_SECRET).trim()
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val b = Request.Builder().url(base + path)
            .header("Authorization", token)
            .header("sign", Net.hmacB64(secret, token + t + nonce))
            .header("t", t)
            .header("nonce", nonce)
        if (body != null) b.post(body.toString().toRequestBody(Net.JSON))
        val o = JSONObject(Net.call(b.build()))
        val code = o.optInt("statusCode")
        if (code != 100) throw IOException("SwitchBot: ${o.optString("message")} (kod $code)")
        return o.optJSONObject("body") ?: JSONObject()
    }

    override suspend fun devices(): List<Device> = coroutineScope {
        val body = api("/devices")
        val list = body.optJSONArray("deviceList") ?: JSONArray()
        val jobs = (0 until list.length()).mapNotNull { i ->
            val d = list.getJSONObject(i)
            val type = d.optString("deviceType")
            val kind = kindOf(type) ?: return@mapNotNull null
            async { build(d, type, kind) }
        }
        val ir = body.optJSONArray("infraredRemoteList") ?: JSONArray()
        val remotes = (0 until ir.length()).map { i ->
            val r = ir.getJSONObject(i)
            val type = r.optString("remoteType")
            Device(
                provider = provider, id = r.optString("remoteId"), name = r.optString("deviceName"),
                kind = if (type.contains("TV", true)) Kind.TV else Kind.IR, model = "IR: $type", canPower = true,
                meta = mapOf("ir" to "1"),
            )
        }
        jobs.awaitAll() + remotes
    }

    private fun kindOf(t: String): Kind? = when {
        t.startsWith("Hub") && !t.contains("2") && !t.contains("3") -> null // plain hubs have nothing to control
        t.contains("Remote") || t.contains("Cam") -> null
        t.startsWith("Bot") -> Kind.BOT
        t.contains("Curtain") || t.contains("Roller Shade") -> Kind.CURTAIN
        t.contains("Blind") -> Kind.BLIND
        t.contains("Plug") || t.contains("Relay") -> Kind.PLUG
        t.contains("Bulb") || t.contains("Light") || t.contains("Lamp") -> Kind.LIGHT
        t.contains("Meter") || t.contains("Sensor") || t.contains("Hub") || t.contains("Thermo") -> Kind.SENSOR
        else -> Kind.OTHER
    }

    private suspend fun build(d: JSONObject, type: String, kind: Kind): Device {
        val id = d.optString("deviceId")
        val dev = Device(
            provider = provider, id = id, name = d.optString("deviceName"), kind = kind, model = type,
            canPower = kind == Kind.LIGHT || kind == Kind.PLUG,
            canBrightness = kind == Kind.LIGHT,
            canColor = kind == Kind.LIGHT && (type.contains("Color") || type.contains("Strip") || type.contains("Floor")),
            canPosition = kind == Kind.CURTAIN || kind == Kind.BLIND,
        )
        return try {
            val s = api("/devices/$id/status")
            dev.copy(
                on = s.optString("power").takeIf { it.isNotEmpty() }?.let { it.equals("on", true) },
                brightness = if (s.has("brightness")) s.optInt("brightness") else null,
                color = parseRgb(s.optString("color")),
                position = if (s.has("slidePosition")) {
                    val p = s.optInt("slidePosition")
                    if (kind == Kind.BLIND) 100 - abs(p - 50) * 2 else 100 - p
                } else null,
                temperature = if (s.has("temperature")) s.optDouble("temperature") else null,
                humidity = if (s.has("humidity")) s.optDouble("humidity") else null,
                battery = if (s.has("battery")) s.optInt("battery") else null,
                extra = when {
                    s.has("openState") -> if (s.optString("openState") == "close") "Zamknięte" else "Otwarte"
                    s.has("moveDetected") -> if (s.optBoolean("moveDetected")) "Wykryto ruch" else "Brak ruchu"
                    else -> null
                },
            )
        } catch (e: Exception) {
            dev.copy(online = false, extra = e.message)
        }
    }

    private fun parseRgb(s: String): Int? {
        val p = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (p.size != 3) return null
        return (p[0] shl 16) or (p[1] shl 8) or p[2]
    }

    override suspend fun send(device: Device, cmd: Command) {
        val action: Pair<String, Any> = when (cmd) {
            is Command.Power -> (if (cmd.on) "turnOn" else "turnOff") to "default"
            is Command.Brightness -> "setBrightness" to cmd.value.coerceIn(1, 100)
            is Command.Color -> "setColor" to "${(cmd.rgb shr 16) and 255}:${(cmd.rgb shr 8) and 255}:${cmd.rgb and 255}"
            is Command.Position -> when {
                device.kind == Kind.BLIND -> (if (cmd.openPercent >= 50) "fullyOpen" else "closeDown") to "default"
                device.model.contains("Roller") -> "setPosition" to (100 - cmd.openPercent)
                else -> "setPosition" to "0,ff,${100 - cmd.openPercent}"
            }
            is Command.Press -> "press" to "default"
            else -> return
        }
        api(
            "/devices/${device.id}/commands",
            JSONObject().put("command", action.first).put("parameter", action.second).put("commandType", "command"),
        )
    }

    companion object {
        const val K_TOKEN = "sb_token"
        const val K_SECRET = "sb_secret"
    }
}
