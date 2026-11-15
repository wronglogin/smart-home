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

/** Govee Developer API (key requested in the Govee Home app, arrives by e-mail). */
class GoveeSource(private val store: Store) : DeviceSource {
    override val provider = Provider.GOVEE
    private val base = "https://openapi.api.govee.com/router/api/v1"

    override fun isConfigured() = store.get(K_KEY).isNotBlank()

    private fun builder(path: String) =
        Request.Builder().url(base + path).header("Govee-API-Key", store.get(K_KEY).trim())

    private fun check(o: JSONObject) {
        val c = o.optInt("code", 200)
        if (c != 200) throw IOException("Govee: ${o.optString("msg", o.optString("message"))} (kod $c)")
    }

    private suspend fun post(path: String, payload: JSONObject): JSONObject {
        val body = JSONObject().put("requestId", UUID.randomUUID().toString()).put("payload", payload)
        val o = JSONObject(Net.call(builder(path).post(body.toString().toRequestBody(Net.JSON)).build()))
        check(o)
        return o
    }

    override suspend fun devices(): List<Device> = coroutineScope {
        val o = JSONObject(Net.call(builder("/user/devices").build()))
        check(o)
        val arr = o.optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).map { i -> val d = arr.getJSONObject(i); async { build(d) } }.awaitAll()
    }

    private suspend fun build(d: JSONObject): Device {
        val sku = d.optString("sku")
        val mac = d.optString("device")
        val type = d.optString("type")
        val caps = d.optJSONArray("capabilities") ?: JSONArray()
        val inst = (0 until caps.length()).map { caps.getJSONObject(it).optString("instance") }.toSet()
        val kind = when {
            type.contains("light") -> Kind.LIGHT
            type.contains("socket") -> Kind.PLUG
            type.contains("thermometer") || type.contains("sensor") -> Kind.SENSOR
            else -> Kind.OTHER
        }
        val dev = Device(
            provider = provider, id = "$sku|$mac", name = d.optString("deviceName").ifBlank { sku }, kind = kind,
            model = sku, canPower = "powerSwitch" in inst, canBrightness = "brightness" in inst,
            canColor = "colorRgb" in inst, meta = mapOf("sku" to sku, "device" to mac),
        )
        return state(dev)
    }

    private suspend fun state(dev: Device): Device = try {
        val o = post("/device/state", JSONObject().put("sku", dev.meta["sku"]).put("device", dev.meta["device"]))
        val cs = o.optJSONObject("payload")?.optJSONArray("capabilities") ?: JSONArray()
        var r = dev.copy(online = true)
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val v = c.optJSONObject("state")?.opt("value")
            r = when (c.optString("instance")) {
                "online" -> r.copy(online = v == true || v == "true")
                "powerSwitch" -> r.copy(on = (v as? Number)?.toInt() == 1)
                "brightness" -> r.copy(brightness = (v as? Number)?.toInt())
                "colorRgb" -> r.copy(color = (v as? Number)?.toInt()?.takeIf { it > 0 })
                // Govee reports thermometers in °F
                "sensorTemperature" -> r.copy(temperature = num(v)?.let { if (it > 45) (it - 32) * 5 / 9 else it })
                "sensorHumidity" -> r.copy(humidity = num(v))
                "battery" -> r.copy(battery = num(v)?.toInt())
                else -> r
            }
        }
        r
    } catch (e: Exception) {
        dev.copy(online = false, extra = e.message)
    }

    override suspend fun refresh(list: List<Device>): List<Device> = coroutineScope {
        list.map { d -> async { state(d) } }.awaitAll()
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        is JSONObject -> v.optDouble("currentHumidity", v.optDouble("currentTemperature", Double.NaN)).takeIf { !it.isNaN() }
        else -> null
    }

    override suspend fun send(device: Device, cmd: Command) {
        val (type, instance, value) = when (cmd) {
            is Command.Power -> Triple("devices.capabilities.on_off", "powerSwitch", if (cmd.on) 1 else 0)
            is Command.Brightness -> Triple("devices.capabilities.range", "brightness", cmd.value.coerceIn(1, 100))
            is Command.Color -> Triple("devices.capabilities.color_setting", "colorRgb", cmd.rgb and 0xFFFFFF)
            else -> return
        }
        post(
            "/device/control",
            JSONObject().put("sku", device.meta["sku"]).put("device", device.meta["device"])
                .put("capability", JSONObject().put("type", type).put("instance", instance).put("value", value)),
        )
    }

    companion object {
        const val K_KEY = "govee_key"
    }
}
