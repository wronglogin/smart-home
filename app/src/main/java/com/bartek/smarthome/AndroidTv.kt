package com.bartek.smarthome

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.coroutines.resume

// ---------- Minimal protobuf, enough for the Android TV Remote v2 protocol ----------

class PbWriter {
    private val o = ByteArrayOutputStream()

    fun varint(v: Long): PbWriter {
        var x = v
        while ((x and 0x7FL.inv()) != 0L) {
            o.write(((x and 0x7FL) or 0x80L).toInt())
            x = x ushr 7
        }
        o.write(x.toInt())
        return this
    }

    fun int(field: Int, v: Long): PbWriter {
        varint((field shl 3).toLong())
        return varint(v)
    }

    fun bytes(field: Int, b: ByteArray): PbWriter {
        varint(((field shl 3) or 2).toLong())
        varint(b.size.toLong())
        o.write(b)
        return this
    }

    fun str(field: Int, s: String) = bytes(field, s.toByteArray())
    fun msg(field: Int, w: PbWriter) = bytes(field, w.build())
    fun build(): ByteArray = o.toByteArray()
}

typealias PbMsg = Map<Int, List<Any>>

fun pbParse(b: ByteArray): PbMsg {
    val m = HashMap<Int, MutableList<Any>>()
    var i = 0
    fun rv(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val x = b[i++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl s)
            if ((x and 0x80) == 0) return r
            s += 7
        }
    }
    while (i < b.size) {
        val tag = rv()
        val f = (tag ushr 3).toInt()
        val v: Any = when ((tag and 7L).toInt()) {
            0 -> rv()
            2 -> {
                val n = rv().toInt()
                val s = b.copyOfRange(i, i + n)
                i += n
                s
            }
            1 -> { i += 8; 0L }
            5 -> { i += 4; 0L }
            else -> return m
        }
        m.getOrPut(f) { mutableListOf() }.add(v)
    }
    return m
}

fun pbLong(m: PbMsg, f: Int): Long? = m[f]?.firstOrNull() as? Long
fun pbSub(m: PbMsg, f: Int): PbMsg? = (m[f]?.firstOrNull() as? ByteArray)?.let { pbParse(it) }

fun pbWrite(out: OutputStream, msg: ByteArray) {
    out.write(PbWriter().varint(msg.size.toLong()).build())
    out.write(msg)
    out.flush()
}

fun pbRead(inp: InputStream): ByteArray {
    var len = 0
    var shift = 0
    while (true) {
        val x = inp.read()
        if (x < 0) throw EOFException()
        len = len or ((x and 0x7F) shl shift)
        if ((x and 0x80) == 0) break
        shift += 7
    }
    val b = ByteArray(len)
    DataInputStream(inp).readFully(b)
    return b
}

// ---------- Client certificate (the TV remembers it after pairing) ----------

object TvCert {
    private val PASS = "smarthome".toCharArray()

    @Synchronized
    fun ensure(store: Store) {
        if (store.get("tv_key").isNotBlank()) return
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=atvremote")
        val start = Date(System.currentTimeMillis() - 86_400_000L)
        val end = Date(System.currentTimeMillis() + 20L * 365 * 86_400_000L)
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.currentTimeMillis()), start, end, name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        store.set("tv_key", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
        store.set("tv_cert", Base64.encodeToString(holder.encoded, Base64.NO_WRAP))
    }

    fun load(store: Store): Pair<PrivateKey, X509Certificate> {
        ensure(store)
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(store.get("tv_key"), Base64.DEFAULT)))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(store.get("tv_cert"), Base64.DEFAULT))) as X509Certificate
        return key to cert
    }

    fun sslContext(store: Store): SSLContext {
        val (key, cert) = load(store)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setKeyEntry("client", key, PASS, arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASS)
        // The TV uses its own self-signed certificate.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
    }

    fun connect(store: Store, ip: String, port: Int, timeout: Int = 4000): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, port), timeout)
        val s = sslContext(store).socketFactory.createSocket(raw, ip, port, true) as SSLSocket
        s.soTimeout = 8000
        s.startHandshake()
        s.soTimeout = 0
        return s
    }
}

private fun hexToBytes(h: String): ByteArray {
    val s = if (h.length % 2 == 1) "0$h" else h
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun bigHex(n: BigInteger): ByteArray = hexToBytes(n.toString(16))

// ---------- Pairing (port 6467): TV shows a 6-character code ----------

class TvPairing(private val store: Store, val ip: String) {
    private var sock: SSLSocket? = null

    private fun pairingMsg(field: Int, inner: PbWriter) =
        PbWriter().int(1, 2).int(2, 200).msg(field, inner).build()

    private fun exchange(s: SSLSocket, msg: ByteArray): PbMsg {
        pbWrite(s.outputStream, msg)
        val r = pbParse(pbRead(s.inputStream))
        val status = pbLong(r, 2) ?: 0L
        if (status != 200L) throw IOException("Telewizor odrzucił parowanie (status $status)")
        return r
    }

    /** Blocking. After this returns the TV shows the code. */
    fun start() {
        val s = TvCert.connect(store, ip, 6467)
        s.soTimeout = 120_000
        sock = s
        val hex6 = PbWriter().int(1, 3).int(2, 6) // HEXADECIMAL, 6 symbols
        exchange(s, pairingMsg(10, PbWriter().str(1, "com.bartek.smarthome").str(2, "Moj Dom")))
        exchange(s, pairingMsg(20, PbWriter().msg(1, hex6).int(3, 1)))
        exchange(s, pairingMsg(30, PbWriter().msg(1, PbWriter().int(1, 3).int(2, 6)).int(2, 1)))
    }

    /** Blocking. Sends the code typed by the user. */
    fun finish(code: String) {
        val s = sock ?: throw IOException("Brak połączenia z telewizorem")
        val c = code.trim().uppercase()
        if (c.length != 6) throw IOException("Kod musi mieć 6 znaków")
        val client = TvCert.load(store).second.publicKey as RSAPublicKey
        val server = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val hash = Net.sha256(
            bigHex(client.modulus), bigHex(client.publicExponent),
            bigHex(server.modulus), bigHex(server.publicExponent),
            hexToBytes(c.substring(2)),
        )
        if ((hash[0].toInt() and 0xFF) != c.substring(0, 2).toInt(16)) throw IOException("Nieprawidłowy kod")
        try {
            exchange(s, pairingMsg(40, PbWriter().bytes(1, hash)))
        } finally {
            close()
        }
    }

    /** Closing sends a TLS alert, so it must not run on the main thread. */
    fun close() {
        val s = sock ?: return
        sock = null
        thread(isDaemon = true) { try { s.close() } catch (_: Exception) {} }
    }
}

// ---------- Remote control (port 6466) ----------

class TvConnection(store: Store, val ip: String) {
    @Volatile var powered: Boolean? = null
    @Volatile var ready = false
    @Volatile var alive = true
    private val sock: SSLSocket = TvCert.connect(store, ip, 6466)
    private val out = sock.outputStream

    init {
        thread(isDaemon = true, name = "tv-$ip") { loop() }
    }

    private fun send(w: PbWriter) = synchronized(this) { pbWrite(out, w.build()) }

    private fun loop() {
        try {
            val inp = sock.inputStream
            while (alive) {
                val m = pbParse(pbRead(inp))
                when {
                    m.containsKey(1) -> send(
                        PbWriter().msg(
                            1,
                            PbWriter().int(1, 622).msg(
                                2,
                                PbWriter().str(1, "Moj Dom").str(2, "Bartek").int(3, 1).str(4, "1")
                                    .str(5, "com.bartek.smarthome").str(6, "1.0"),
                            ),
                        ),
                    )
                    m.containsKey(2) -> {
                        send(PbWriter().msg(2, PbWriter().int(1, 622)))
                        ready = true
                    }
                    m.containsKey(8) -> {
                        val v = pbSub(m, 8)?.let { pbLong(it, 1) } ?: 0L
                        send(PbWriter().msg(9, PbWriter().int(1, v)))
                    }
                    m.containsKey(40) -> powered = (pbSub(m, 40)?.let { pbLong(it, 1) } ?: 0L) == 1L
                }
            }
        } catch (_: Exception) {
        } finally {
            alive = false
            ready = false
            try { sock.close() } catch (_: Exception) {}
        }
    }

    fun waitReady(ms: Long = 5000) {
        val end = System.currentTimeMillis() + ms
        while (!ready && alive && System.currentTimeMillis() < end) Thread.sleep(50)
        if (!ready) {
            close()
            throw IOException("Telewizor nie odpowiada – sparuj go ponownie w Ustawieniach")
        }
    }

    fun waitPower(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (powered == null && alive && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    fun key(code: Int) = send(PbWriter().msg(10, PbWriter().int(1, code.toLong()).int(2, 3)))
    fun link(url: String) = send(PbWriter().msg(90, PbWriter().str(1, url)))

    fun close() {
        alive = false
        thread(isDaemon = true) { try { sock.close() } catch (_: Exception) {} }
    }
}

/** Android TV / Google TV over the official TV Remote protocol (same as the Google TV app). */
class TvSource(private val store: Store, private val context: Context) : DeviceSource {
    override val provider = Provider.TV
    private val conns = ConcurrentHashMap<String, TvConnection>()

    override fun isConfigured() = tvs().isNotEmpty()

    fun tvs(): List<Pair<String, String>> {
        val a = store.array(K_LIST)
        return (0 until a.length()).map { val o = a.getJSONObject(it); o.optString("ip") to o.optString("name") }
    }

    fun addTv(ip: String, name: String) {
        val l = tvs().filter { it.first != ip } + (ip to name)
        store.setArray(K_LIST, JSONArray(l.map { JSONObject().put("ip", it.first).put("name", it.second) }))
    }

    fun removeTv(ip: String) {
        store.setArray(K_LIST, JSONArray(tvs().filter { it.first != ip }.map { JSONObject().put("ip", it.first).put("name", it.second) }))
        conns.remove(ip)?.close()
    }

    fun pairing(ip: String) = TvPairing(store, ip)

    private fun conn(ip: String): TvConnection {
        conns[ip]?.let { if (it.alive && it.ready) return it }
        conns.remove(ip)?.close()
        val c = TvConnection(store, ip)
        c.waitReady()
        conns[ip] = c
        return c
    }

    override suspend fun devices(): List<Device> = coroutineScope {
        tvs().map { (ip, name) ->
            async(Dispatchers.IO) {
                try {
                    val c = conn(ip)
                    c.waitPower(1500)
                    Device(provider = provider, id = ip, name = name, kind = Kind.TV, model = "Android TV", on = c.powered, canPower = true, meta = mapOf("ip" to ip))
                } catch (e: Exception) {
                    Device(provider = provider, id = ip, name = name, kind = Kind.TV, model = "Android TV", online = false, canPower = true, extra = e.message, meta = mapOf("ip" to ip))
                }
            }
        }.awaitAll()
    }

    override suspend fun send(device: Device, cmd: Command) {
        withContext(Dispatchers.IO) {
            val c = conn(device.id)
            when (cmd) {
                is Command.Power -> {
                    c.waitPower(1500)
                    val p = c.powered
                    if (p == null) {
                        c.key(if (cmd.on) KEY_WAKEUP else KEY_SLEEP)
                    } else if (p != cmd.on) {
                        c.key(KEY_POWER)
                    }
                }
                is Command.Key -> c.key(cmd.code)
                is Command.App -> c.link(cmd.link)
                else -> {}
            }
            Unit
        }
    }

    /** Finds TVs on the network that advertise the remote service (mDNS). */
    suspend fun discover(timeoutMs: Long = 5000): List<Pair<String, String>> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.add(serviceInfo) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        delay(timeoutMs)
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        val out = mutableListOf<Pair<String, String>>()
        for (s in found.toList().distinctBy { it.serviceName }) {
            val r = resolve(nsd, s) ?: continue
            @Suppress("DEPRECATION")
            val host = r.host?.hostAddress ?: continue
            out += host to r.serviceName
        }
        return out.distinctBy { it.first }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            })
        }
    }

    companion object {
        const val K_LIST = "tv_list"
        const val KEY_POWER = 26
        const val KEY_WAKEUP = 224
        const val KEY_SLEEP = 223
    }
}
