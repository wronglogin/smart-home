package com.bartek.smarthome

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Plain HTTP on the home network (Tapo encrypts the payload itself with KLAP). */
object TapoHttp {
    val lan: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    val probe: OkHttpClient = lan.newBuilder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()
    private val OCTET = "application/octet-stream".toMediaType()

    class Resp(val code: Int, val body: ByteArray, val cookies: List<String>)

    fun post(ip: String, path: String, body: ByteArray, cookie: String?, client: OkHttpClient = lan): Resp {
        val b = Request.Builder().url("http://$ip$path").post(body.toRequestBody(OCTET))
        if (!cookie.isNullOrEmpty()) b.header("Cookie", cookie)
        client.newCall(b.build()).execute().use { r ->
            return Resp(r.code, r.body?.bytes() ?: ByteArray(0), r.headers("Set-Cookie"))
        }
    }
}

/** One KLAP session with one Tapo device (handshake1 + handshake2, then AES-CBC requests). */
class KlapSession(private val ip: String, private val authHash: ByteArray) {
    private var key = ByteArray(0)
    private var iv = ByteArray(0)
    private var sig = ByteArray(0)
    private var seq = 0
    private var cookie = ""
    private var ready = false

    fun invalidate() { ready = false }

    private fun handshake() {
        val local = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val r1 = TapoHttp.post(ip, "/app/handshake1", local, null)
        if (r1.code != 200 || r1.body.size < 48) throw IOException("Tapo $ip: brak odpowiedzi (${r1.code})")
        val remote = r1.body.copyOfRange(0, 16)
        val server = r1.body.copyOfRange(16, 48)
        if (!Net.sha256(local, remote, authHash).contentEquals(server)) {
            throw IOException("Tapo $ip: zły e-mail lub hasło konta TP-Link")
        }
        cookie = r1.cookies.firstOrNull { it.startsWith("TP_SESSIONID") }?.substringBefore(";") ?: ""
        val r2 = TapoHttp.post(ip, "/app/handshake2", Net.sha256(remote, local, authHash), cookie)
        if (r2.code != 200) throw IOException("Tapo $ip: logowanie odrzucone (${r2.code})")
        key = Net.sha256("lsk".toByteArray(), local, remote, authHash).copyOf(16)
        val full = Net.sha256("iv".toByteArray(), local, remote, authHash)
        iv = full.copyOf(12)
        seq = ByteBuffer.wrap(full, 28, 4).int
        sig = Net.sha256("ldk".toByteArray(), local, remote, authHash).copyOf(28)
        ready = true
    }

    @Synchronized
    fun request(payload: JSONObject): JSONObject {
        if (!ready) handshake()
        seq += 1
        val seqBytes = ByteBuffer.allocate(4).putInt(seq).array()
        val ivSeq = iv + seqBytes
        val enc = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivSeq))
            doFinal(payload.toString().toByteArray())
        }
        val signature = Net.sha256(sig, seqBytes, enc)
        val r = TapoHttp.post(ip, "/app/request?seq=$seq", signature + enc, cookie)
        if (r.code != 200 || r.body.size <= 32) {
            ready = false
            throw IOException("Tapo $ip: HTTP ${r.code}")
        }
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivSeq))
            doFinal(r.body.copyOfRange(32, r.body.size))
        }
        val res = JSONObject(String(plain))
        val err = res.optInt("error_code")
        if (err != 0) throw IOException("Tapo $ip: błąd $err")
        return res.optJSONObject("result") ?: JSONObject()
    }
}

/** Tapo plugs and bulbs, controlled locally (phone must be on the same Wi-Fi). */
class TapoSource(private val store: Store, private val context: Context) : DeviceSource {
    override val provider = Provider.TAPO
    private val sessions = ConcurrentHashMap<String, KlapSession>()

    override fun isConfigured() = store.get(K_USER).isNotBlank() && ips().isNotEmpty()

    fun ips(): List<String> = store.array(K_IPS).let { a -> (0 until a.length()).map { a.getString(it) } }

    fun addIp(ip: String) {
        val l = ips().toMutableList()
        if (ip !in l) {
            l += ip
            store.setArray(K_IPS, JSONArray(l))
        }
    }

    fun removeIp(ip: String) = store.setArray(K_IPS, JSONArray(ips().filter { it != ip }))

    fun resetSessions() = sessions.clear()

    private fun authHash() = Net.sha256(
        Net.sha1(store.get(K_USER).trim().toByteArray()),
        Net.sha1(store.get(K_PASS).toByteArray()),
    )

    private suspend fun call(ip: String, method: String, params: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("method", method).put("requestTimeMils", System.currentTimeMillis())
            if (params != null) payload.put("params", params)
            val s = sessions.getOrPut(ip) { KlapSession(ip, authHash()) }
            try {
                s.request(payload)
            } catch (e: Exception) {
                s.invalidate()
                s.request(payload) // one retry with a fresh handshake (sessions expire)
            }
        }

    private fun nickname(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT)).ifBlank { s }
    } catch (e: Exception) {
        s
    }

    override suspend fun devices(): List<Device> = coroutineScope {
        ips().map { ip ->
            async {
                try {
                    val r = call(ip, "get_device_info")
                    val type = r.optString("type")
                    val name = nickname(r.optString("nickname")).ifBlank { r.optString("model") }
                    val id = r.optString("device_id").ifBlank { ip }
                    store.set("tapo_id:$ip", id)
                    store.set("tapo_name:$ip", name)
                    val bulb = type.contains("BULB")
                    Device(
                        provider = provider, id = id, name = name, kind = if (bulb) Kind.LIGHT else Kind.PLUG,
                        model = r.optString("model"), on = r.optBoolean("device_on"),
                        brightness = if (r.has("brightness")) r.optInt("brightness") else null,
                        color = if (r.has("hue") && r.optInt("color_temp") == 0) hsv(r.optInt("hue"), r.optInt("saturation")) else null,
                        canPower = true, canBrightness = r.has("brightness"), canColor = r.has("hue"),
                        meta = mapOf("ip" to ip),
                    )
                } catch (e: Exception) {
                    Device(
                        provider = provider, id = store.get("tapo_id:$ip").ifBlank { ip },
                        name = store.get("tapo_name:$ip").ifBlank { "Tapo $ip" }, kind = Kind.PLUG,
                        online = false, canPower = true, extra = e.message, meta = mapOf("ip" to ip),
                    )
                }
            }
        }.awaitAll()
    }

    private fun hsv(h: Int, s: Int): Int =
        android.graphics.Color.HSVToColor(floatArrayOf(h.toFloat(), s / 100f, 1f)) and 0xFFFFFF

    override suspend fun send(device: Device, cmd: Command) {
        val ip = device.meta["ip"] ?: return
        val p = when (cmd) {
            is Command.Power -> JSONObject().put("device_on", cmd.on)
            is Command.Brightness -> JSONObject().put("device_on", true).put("brightness", cmd.value.coerceIn(1, 100))
            is Command.Color -> {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(cmd.rgb or 0xFF000000.toInt(), hsv)
                if (hsv[1] < 0.08f) {
                    JSONObject().put("device_on", true).put("color_temp", if (cmd.rgb == 0xFFFFFF) 4000 else 2700)
                } else {
                    JSONObject().put("device_on", true).put("hue", hsv[0].toInt())
                        .put("saturation", (hsv[1] * 100).toInt()).put("color_temp", 0)
                }
            }
            else -> return
        }
        call(ip, "set_device_info", p)
    }

    /** Tries every address in the phone's /24 network and keeps those that answer like a Tapo device. */
    suspend fun scan(): List<String> = withContext(Dispatchers.IO) {
        val prefix = localPrefix() ?: throw IOException("Telefon nie jest połączony z Wi-Fi")
        val sem = Semaphore(48)
        val found = coroutineScope {
            (1..254).map { n ->
                async { sem.withPermit { val ip = "$prefix.$n"; if (probe(ip)) ip else null } }
            }.awaitAll().filterNotNull()
        }
        found.forEach { addIp(it) }
        sessions.clear()
        found
    }

    private fun probe(ip: String): Boolean = try {
        val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val r = TapoHttp.post(ip, "/app/handshake1", seed, null, TapoHttp.probe)
        r.code == 200 && r.body.size == 48
    } catch (e: Exception) {
        false
    }

    private fun localPrefix(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val a = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        return a.hostAddress?.substringBeforeLast(".")
    }

    companion object {
        const val K_USER = "tapo_user"
        const val K_PASS = "tapo_pass"
        const val K_IPS = "tapo_ips"
    }
}
