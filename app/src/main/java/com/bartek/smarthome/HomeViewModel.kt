package com.bartek.smarthome

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

const val K_VOICE_HA = "voice_ha"
const val K_VOICE_SPEAK = "voice_speak"

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val switchBot = SwitchBotSource(store)
    val govee = GoveeSource(store)
    val eweLink = EweLinkSource(store)
    val tapo = TapoSource(store, app)
    val tv = TvSource(store, app)
    val homeAssistant = HomeAssistantSource(store)
    private val sources: List<DeviceSource> = listOf(switchBot, govee, eweLink, tapo, tv, homeAssistant)

    var devices by mutableStateOf<List<Device>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var errors by mutableStateOf<Map<Provider, String>>(emptyMap())
        private set
    var snack by mutableStateOf<String?>(null)

    /** Bumped whenever local settings (rooms, names, favourites, accounts) change, so the UI re-reads them. */
    var version by mutableIntStateOf(0)
        private set

    fun bump() { version++ }

    fun anyConfigured() = sources.any { it.isConfigured() }

    fun refresh() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            val results = sources.filter { it.isConfigured() }
                .map { s -> async { s.provider to runCatching { s.devices() } } }
                .awaitAll()
            val errs = mutableMapOf<Provider, String>()
            val list = mutableListOf<Device>()
            for ((p, r) in results) {
                r.onSuccess { list += it }
                    .onFailure { e ->
                        errs[p] = e.message ?: e.toString()
                        list += devices.filter { it.provider == p }
                    }
            }
            devices = list.distinctBy { it.key }.map { withAssumed(it) }
            errors = errs
            loading = false
        }
    }

    fun onSettingsClosed() {
        tapo.resetSessions()
        bump()
        refresh()
    }

    fun send(d: Device, cmd: Command) {
        devices = devices.map { if (it.key == d.key) optimistic(it, cmd) else it }
        viewModelScope.launch {
            try {
                sources.first { it.provider == d.provider }.send(d, cmd)
            } catch (e: Exception) {
                snack = "${d.provider.label}: ${e.message}"
                if (isAssumed(d)) store.set("assumed:${d.key}", when (d.on) { true -> "1"; false -> "0"; null -> "" })
                devices = devices.map { if (it.key == d.key) d else it }
            }
        }
    }

    /** Turns every given light on or off at once (e.g. a whole room). */
    fun setLights(lights: List<Device>, on: Boolean) {
        lights.filter { it.on != on }.forEach { send(it, Command.Power(on)) }
    }

    /**
     * IR devices (e.g. a lamp on the SwitchBot Hub remote) can't report whether they're on,
     * so the app remembers the last command it sent and shows that.
     */
    private fun isAssumed(d: Device) = d.kind == Kind.IR || (d.meta["ir"] == "1" && d.on == null)

    private fun withAssumed(d: Device): Device =
        if (isAssumed(d) && d.on == null) store.get("assumed:${d.key}").takeIf { it.isNotEmpty() }?.let { d.copy(on = it == "1") } ?: d else d

    private fun remember(d: Device, on: Boolean) {
        if (isAssumed(d)) store.set("assumed:${d.key}", if (on) "1" else "0")
    }

    private fun optimistic(d: Device, cmd: Command): Device = when (cmd) {
        is Command.Power -> { remember(d, cmd.on); d.copy(on = cmd.on) }
        is Command.Brightness -> d.copy(brightness = cmd.value, on = if (d.canPower) true else d.on)
        is Command.Color -> d.copy(color = cmd.rgb, on = if (d.canPower) true else d.on)
        is Command.Position -> d.copy(position = cmd.openPercent)
        else -> d
    }

    // ----- local names, rooms, favourites -----

    fun name(d: Device) = store.alias(d.key).ifBlank { d.name }
    fun room(d: Device) = store.room(d.key).ifBlank { d.room.ifBlank { DEFAULT_ROOM } }
    fun rooms(): List<String> = devices.map { room(it) }.distinct().sortedBy { it.lowercase() }
    fun isFav(d: Device) = store.isFavorite(d.key)
    fun anyFav() = devices.any { isFav(it) }

    /** Category picked by hand, or null when it follows the device type. */
    fun category(d: Device): Filter? = Filter.entries.firstOrNull { it.name == store.category(d.key) }
    fun setCategory(d: Device, f: Filter?) {
        store.setCategory(d.key, f?.name ?: "")
        bump()
    }
    fun matches(f: Filter, d: Device): Boolean = f.match(d, isFav(d), category(d))

    /** Treated as a light: real bulbs, or e.g. a plug with a lamp moved to "Oświetlenie". */
    fun isLight(d: Device): Boolean = category(d)?.let { it == Filter.LIGHTS } ?: (d.kind == Kind.LIGHT)

    fun hiddenFilters(): Set<Filter> = store.hiddenFilters().mapNotNull { n -> Filter.entries.firstOrNull { it.name == n } }.toSet()
    fun setFilterHidden(f: Filter, hidden: Boolean) {
        val s = store.hiddenFilters().toMutableSet()
        if (hidden) s += f.name else s -= f.name
        store.setHiddenFilters(s)
        bump()
    }

    /** Hidden devices disappear from the lists (except Ulubione) but still follow the room and house light switches. */
    fun isHidden(d: Device) = store.isHidden(d.key)
    fun hiddenDevices(): List<Device> = devices.filter { isHidden(it) }
    fun setHidden(d: Device, hidden: Boolean) {
        store.setHidden(d.key, hidden)
        bump()
    }

    fun toggleFav(d: Device) {
        store.setFavorite(d.key, !store.isFavorite(d.key))
        bump()
    }

    fun rename(d: Device, name: String, room: String) {
        store.setAlias(d.key, if (name.trim() == d.name) "" else name)
        store.setRoom(d.key, room)
        bump()
    }

    init {
        Speaker.init(app)
        store.dropAutomationLeftovers()
    }

    // ----- saving and backup -----

    fun saveNow() {
        tapo.resetSessions()
        bump()
        snack = "Ustawienia zapisane"
        refresh()
    }

    fun exportSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Nie można zapisać pliku")
                    out.use { it.write(store.exportJson().toByteArray()) }
                }
                snack = "Zapisano kopię ustawień"
            } catch (e: Exception) {
                snack = "Błąd zapisu: ${e.message}"
            }
        }
    }

    fun importSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val inp = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IOException("Nie można otworzyć pliku")
                    store.importJson(inp.use { String(it.readBytes()) })
                }
                tapo.resetSessions()
                store.dropAutomationLeftovers()
                bump()
                snack = "Przywrócono ustawienia: " + store.backupSummary()
                refresh()
            } catch (e: Exception) {
                snack = "Błąd wczytywania: ${e.message}"
            }
        }
    }

    // ----- voice -----

    /** Set by the "Komenda głosowa" shortcut; the home screen opens the microphone. */
    var voiceRequested by mutableStateOf(false)
    var voiceBusy by mutableStateOf(false)
        private set

    fun voice(text: String) {
        viewModelScope.launch {
            voiceBusy = true
            val useHa = homeAssistant.isConfigured() && store.get(K_VOICE_HA) != "0"
            val reply = try {
                if (useHa) {
                    val (answer, targets) = homeAssistant.converse(text)
                    rememberHaTargets(text, targets)
                    answer
                } else {
                    localVoice(text)
                }
            } catch (e: Exception) {
                // HA not reachable (e.g. away from home): understand the command in the app instead
                try { localVoice(text) } catch (e2: Exception) { "Błąd: ${e2.message}" }
            }
            snack = "„$text” – $reply"
            if (store.get(K_VOICE_SPEAK) != "0") Speaker.say(reply)
            voiceBusy = false
            kotlinx.coroutines.delay(1500)
            refresh()
        }
    }

    /** HA's Assist switched these by name; IR lamps the app shows can't report it, so remember it here. */
    private fun rememberHaTargets(text: String, names: List<String>) {
        val t = VoiceParser.norm(text)
        val on = when {
            t.contains("wylacz") || t.contains("zgas") -> false
            t.contains("wlacz") || t.contains("zapal") -> true
            else -> return
        }
        val wanted = names.map { VoiceParser.norm(it) }.toSet()
        val hit = devices.filter { isAssumed(it) && (VoiceParser.norm(name(it)) in wanted || VoiceParser.norm(it.name) in wanted) }
        if (hit.isEmpty()) return
        hit.forEach { remember(it, on) }
        val keys = hit.map { it.key }.toSet()
        devices = devices.map { if (it.key in keys) it.copy(on = on) else it }
    }

    private fun localVoice(text: String): String {
        val r = VoiceParser.parse(
            text, devices,
            nameOf = { name(it) }, roomOf = { room(it) }, isLight = { isLight(it) },
        )
        r.actions.forEach { (d, c) -> send(d, c) }
        return r.reply
    }

    // ----- Home Assistant -----

    var haStatus by mutableStateOf<String?>(null)
        private set

    fun testHomeAssistant() {
        viewModelScope.launch {
            haStatus = "Łączę…"
            haStatus = try {
                homeAssistant.test()
            } catch (e: Exception) {
                "Błąd: ${e.message}"
            }
        }
    }

    // ----- eWeLink -----

    fun eweLinkCallback(code: String, region: String) {
        viewModelScope.launch {
            try {
                eweLink.exchange(code, region)
                snack = "Połączono z eWeLink"
                bump()
                refresh()
            } catch (e: Exception) {
                snack = e.message
            }
        }
    }

    // ----- Tapo -----

    var scanning by mutableStateOf(false)
        private set

    fun scanTapo() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            try {
                val found = tapo.scan()
                snack = if (found.isEmpty()) "Nie znaleziono urządzeń Tapo w tej sieci" else "Znaleziono ${found.size} urządzeń Tapo"
            } catch (e: Exception) {
                snack = e.message
            }
            scanning = false
            bump()
        }
    }

    // ----- Android TV -----

    var tvFound by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var tvSearching by mutableStateOf(false)
        private set
    var pairing by mutableStateOf<TvPairing?>(null)
        private set
    var pairingBusy by mutableStateOf(false)
        private set
    private var pairingName = ""

    fun discoverTv() {
        if (tvSearching) return
        viewModelScope.launch {
            tvSearching = true
            tvFound = try { tv.discover() } catch (e: Exception) { emptyList() }
            if (tvFound.isEmpty()) snack = "Nie znaleziono telewizorów – wpisz adres IP ręcznie"
            tvSearching = false
        }
    }

    fun startPairing(ip: String, name: String) {
        if (pairingBusy) return
        viewModelScope.launch {
            pairingBusy = true
            val p = tv.pairing(ip.trim())
            try {
                withContext(Dispatchers.IO) { p.start() }
                pairingName = name.ifBlank { "Telewizor" }
                pairing = p
            } catch (e: Exception) {
                p.close()
                snack = "Nie można połączyć z $ip: ${e.message}"
            }
            pairingBusy = false
        }
    }

    fun finishPairing(code: String) {
        val p = pairing ?: return
        viewModelScope.launch {
            pairingBusy = true
            try {
                withContext(Dispatchers.IO) { p.finish(code) }
                tv.addTv(p.ip, pairingName)
                snack = "Sparowano telewizor"
                pairing = null
                bump()
            } catch (e: Exception) {
                snack = e.message
                pairing = null
            }
            pairingBusy = false
        }
    }

    fun cancelPairing() {
        pairing?.close()
        pairing = null
    }
}
