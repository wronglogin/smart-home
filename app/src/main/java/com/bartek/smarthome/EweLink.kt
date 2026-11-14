package com.bartek.smarthome

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * eWeLink (Sonoff) cloud API v2. Needs your own app from dev.ewelink.cc (APPID + APP SECRET);
 * you log in once through eWeLink's own OAuth page shown inside the app.
 */
class EweLinkSource(private val store: Store) : DeviceSource {
    override val provider = Provider.EWELINK

    override fun isConfigured() = store.get(K_AT).isNotBlank()
    fun hasApp() = store.get(K_APPID).isNotBlank() && store.get(K_SECRET).isNotBlank()
    fun redirectUrl() = store.get(K_REDIRECT).ifBlank { DEFAULT_REDIRECT }
    fun logout() = store.remove(K_AT, K_RT, K_EXP)

    private fun appId() = store.get(K_APPID).trim()
    private fun secret() = store.get(K_SECRET).trim()
    private fun host(region: String = store.get(K_REGION).ifBlank { "eu" }) =
        if (region == "cn") "https://cn-apia.coolkit.cn" else "https://$region-apia.coolkit.cc"

    private fun nonce() = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    fun loginUrl(): String {
        val seq = System.currentTimeMillis().toString()
        val auth = Net.hmacB64(secret(), "${appId()}_$seq")
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://c2ccdn.coolkit.cc/oauth/index.html?state=smarthome&clientId=${e(appId())}" +
            "&authorization=${e(auth)}&seq=$seq&redirectUrl=${e(redirectUrl())}&nonce=${nonce()}" +
            "&grantType=authorization_code&showQRCode=false"
    }

    private fun parse(s: String): JSONObject {
        val o = JSONObject(s)
        val err = o.optInt("error")
        if (err != 0) throw IOException("eWeLink: ${o.optString("msg")} (błąd $err)")
        return o.optJSONObject("data") ?: JSONObject()
    }

    suspend fun exchange(code: String, region: String) {
        val body = JSONObject().put("code", code).put("redirectUrl", redirectUrl())
            .put("grantType", "authorization_code").toString()
        val req = Request.Builder().url(host(region) + "/v2/user/oauth/token")
            .header("X-CK-Appid", appId())
            .header("X-CK-Nonce", nonce())
            .header("Authorization", "Sign " + Net.hmacB64(secret(), body))
            .post(body.toRequestBody(Net.JSON))
            .build()
        val data = parse(Net.call(req))
        val at = data.optString("accessToken")
        if (at.isBlank()) throw IOException("eWeLink nie zwrócił tokenu")
        store.set(K_AT, at)
        store.set(K_RT, data.optString("refreshToken"))
        store.set(K_REGION, region)
        store.setLong(K_EXP, data.optLong("atExpiredTime", System.currentTimeMillis() + 25L * 86_400_000))
    }

    private suspend fun ensureToken() {
        val exp = store.getLong(K_EXP)
        if (exp == 0L || System.currentTimeMillis() < exp - 3L * 86_400_000) return
        val body = JSONObject().put("rt", store.get(K_RT)).toString()
        val req = Request.Builder().url(host() + "/v2/user/refresh")
            .header("X-CK-Appid", appId())
            .header("X-CK-Nonce", nonce())
            .header("Authorization", "Bearer " + store.get(K_AT))
            .post(body.toRequestBody(Net.JSON))
            .build()
        val d = try {
            parse(Net.call(req))
        } catch (e: Exception) {
            throw IOException("Sesja eWeLink wygasła – zaloguj się ponownie w Ustawieniach")
        }
        store.set(K_AT, d.optString("at", d.optString("accessToken")))
        store.set(K_RT, d.optString("rt", d.optString("refreshToken")))
        store.setLong(K_EXP, System.currentTimeMillis() + 25L * 86_400_000)
    }

    private fun authed(path: String) = Request.Builder().url(host() + path)
        .header("X-CK-Appid", appId())
        .header("Authorization", "Bearer " + store.get(K_AT))

    override suspend fun devices(): List<Device> {
        ensureToken()
        val data = parse(Net.call(authed("/v2/device/thing?num=0").build()))
        val list = data.optJSONArray("thingList") ?: JSONArray()
        val out = mutableListOf<Device>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optInt("itemType") == 3) continue // groups
            val d = item.optJSONObject("itemData") ?: continue
            val id = d.optString("deviceid")
            val name = d.optString("name")
            val online = d.optBoolean("online", true)
            val params = d.optJSONObject("params") ?: JSONObject()
            val uiid = d.optJSONObject("extra")?.optInt("uiid") ?: 0
            val model = d.optString("productModel")
            val temp = params.optString("currentTemperature").toDoubleOrNull() ?: params.optString("temperature").toDoubleOrNull()
            val hum = params.optString("currentHumidity").toDoubleOrNull() ?: params.optString("humidity").toDoubleOrNull()
            val battery = if (params.has("battery")) params.optInt("battery") else null
            val switches = params.optJSONArray("switches")
            when {
                uiid == 102 || uiid == 154 -> out += Device(
                    provider = provider, id = id, name = name, kind = Kind.SENSOR, model = model, online = online,
                    extra = if (params.optString("switch") == "on") "Otwarte" else "Zamknięte", battery = battery,
                    meta = mapOf("id" to id),
                )
                switches != null -> {
                    val n = CHANNELS[uiid] ?: 1
                    for (ch in 0 until n) {
                        val st = (0 until switches.length()).map { switches.getJSONObject(it) }
                            .firstOrNull { it.optInt("outlet") == ch }?.optString("switch")
                        out += Device(
                            provider = provider, id = "$id#$ch", name = if (n > 1) "$name ${ch + 1}" else name,
                            kind = Kind.PLUG, model = model, online = online, on = st == "on", canPower = true,
                            temperature = temp, humidity = hum, meta = mapOf("id" to id, "ch" to ch.toString()),
                        )
                    }
                }
                params.has("switch") -> {
                    val isLight = params.has("brightness") || params.has("ltype") || params.has("bright")
                    out += Device(
                        provider = provider, id = id, name = name, kind = if (isLight) Kind.LIGHT else Kind.PLUG,
                        model = model, online = online, on = params.optString("switch") == "on",
                        brightness = if (params.has("brightness")) params.optInt("brightness") else null,
                        temperature = temp, humidity = hum, canPower = true,
                        canBrightness = params.has("brightness"), meta = mapOf("id" to id),
                    )
                }
                temp != null || hum != null -> out += Device(
                    provider = provider, id = id, name = name, kind = Kind.SENSOR, model = model, online = online,
                    temperature = temp, humidity = hum, battery = battery, meta = mapOf("id" to id),
                )
                else -> out += Device(
                    provider = provider, id = id, name = name, kind = Kind.OTHER, model = model, online = online,
                    meta = mapOf("id" to id),
                )
            }
        }
        return out
    }

    override suspend fun send(device: Device, cmd: Command) {
        ensureToken()
        val ch = device.meta["ch"]
        val params = when (cmd) {
            is Command.Power -> {
                val s = if (cmd.on) "on" else "off"
                if (ch != null) {
                    JSONObject().put("switches", JSONArray().put(JSONObject().put("switch", s).put("outlet", ch.toInt())))
                } else {
                    JSONObject().put("switch", s)
                }
            }
            is Command.Brightness -> JSONObject().put("switch", "on").put("brightness", cmd.value)
            else -> return
        }
        val body = JSONObject().put("type", 1).put("id", device.meta["id"]).put("params", params)
        parse(Net.call(authed("/v2/device/thing/status").post(body.toString().toRequestBody(Net.JSON)).build()))
    }

    companion object {
        const val K_APPID = "ew_appid"
        const val K_SECRET = "ew_secret"
        const val K_REDIRECT = "ew_redirect"
        const val K_AT = "ew_at"
        const val K_RT = "ew_rt"
        const val K_EXP = "ew_exp"
        const val K_REGION = "ew_region"
        const val DEFAULT_REDIRECT = "https://wronglogin.github.io/smart-home/callback"

        /** Number of relay channels for multi-gang Sonoff models (by eWeLink UIID). */
        val CHANNELS = mapOf(
            2 to 2, 3 to 3, 4 to 4, 7 to 2, 8 to 3, 9 to 4, 29 to 2, 30 to 3, 31 to 4, 41 to 4,
            82 to 2, 83 to 3, 84 to 4, 113 to 2, 114 to 3, 126 to 2, 139 to 2, 140 to 3, 141 to 4,
            161 to 2, 162 to 3, 211 to 2, 212 to 3,
        )
    }
}
