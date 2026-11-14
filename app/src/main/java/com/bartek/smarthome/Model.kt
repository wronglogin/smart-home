package com.bartek.smarthome

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val DEFAULT_ROOM = "Bez pokoju"

enum class Provider(val label: String) {
    SWITCHBOT("SwitchBot"), GOVEE("Govee"), EWELINK("eWeLink"), TAPO("Tapo"), TV("Android TV")
}

enum class Kind { LIGHT, PLUG, CURTAIN, BLIND, BOT, SENSOR, TV, IR, OTHER }

data class Device(
    val provider: Provider,
    val id: String,
    val name: String,
    val kind: Kind,
    val model: String = "",
    val online: Boolean = true,
    val on: Boolean? = null,
    val brightness: Int? = null,
    val color: Int? = null,          // 0xRRGGBB
    val position: Int? = null,       // percent open
    val temperature: Double? = null, // °C
    val humidity: Double? = null,
    val battery: Int? = null,
    val extra: String? = null,
    val canPower: Boolean = false,
    val canBrightness: Boolean = false,
    val canColor: Boolean = false,
    val canPosition: Boolean = false,
    val meta: Map<String, String> = emptyMap(),
) {
    val key: String get() = "${provider.name}:$id"
}

sealed class Command {
    data class Power(val on: Boolean) : Command()
    data class Brightness(val value: Int) : Command()
    data class Color(val rgb: Int) : Command()
    data class Position(val openPercent: Int) : Command()
    object Press : Command()
    data class Key(val code: Int) : Command()
    data class App(val link: String) : Command()
}

interface DeviceSource {
    val provider: Provider
    fun isConfigured(): Boolean
    suspend fun devices(): List<Device>
    suspend fun send(device: Device, cmd: Command)
}

/** Everything the app keeps: account keys, rooms, names, favourites. Stays on the phone only. */
class Store(context: Context) {
    private val p = context.getSharedPreferences("smarthome", Context.MODE_PRIVATE)

    fun get(key: String): String = p.getString(key, "") ?: ""
    // commit() writes to disk immediately, so nothing is lost if the app is closed or updated right after.
    fun set(key: String, value: String) { p.edit().putString(key, value).commit() }
    fun getLong(key: String): Long = p.getLong(key, 0L)
    fun setLong(key: String, value: Long) { p.edit().putLong(key, value).commit() }
    fun remove(vararg keys: String) {
        val e = p.edit()
        keys.forEach { e.remove(it) }
        e.commit()
    }

    fun array(key: String): JSONArray = try {
        JSONArray(get(key).ifBlank { "[]" })
    } catch (e: Exception) {
        JSONArray()
    }

    fun setArray(key: String, a: JSONArray) = set(key, a.toString())

    fun isFavorite(k: String) = p.getBoolean("fav:$k", false)
    fun setFavorite(k: String, v: Boolean) { p.edit().putBoolean("fav:$k", v).commit() }
    fun room(k: String) = get("room:$k")
    fun setRoom(k: String, r: String) = set("room:$k", r.trim())
    fun alias(k: String) = get("alias:$k")
    fun setAlias(k: String, a: String) = set("alias:$k", a.trim())

    /** All settings (accounts, rooms, names, favourites, paired TVs) as one JSON file. */
    fun exportJson(): String {
        val data = JSONObject()
        for ((k, v) in p.all) {
            val entry = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                else -> null
            }
            if (entry != null) data.put(k, entry)
        }
        return JSONObject().put("app", "MojDom").put("format", 1).put("data", data).toString(2)
    }

    fun importJson(text: String) {
        val root = JSONObject(text)
        if (root.optString("app") != "MojDom") throw IllegalArgumentException("To nie jest plik ustawień Mój Dom")
        val data = root.getJSONObject("data")
        val e = p.edit().clear()
        for (k in data.keys()) {
            val x = data.getJSONObject(k)
            when (x.optString("t")) {
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "s" -> e.putString(k, x.getString("v"))
            }
        }
        e.commit()
    }
}

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    fun hmacB64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha1(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)

    suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(200)}")
            body
        }
    }
}
