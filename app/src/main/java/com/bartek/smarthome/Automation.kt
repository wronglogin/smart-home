package com.bartek.smarthome

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

fun createSources(store: Store, ctx: Context): List<DeviceSource> = listOf(
    SwitchBotSource(store), GoveeSource(store), EweLinkSource(store),
    TapoSource(store, ctx.applicationContext), TvSource(store, ctx.applicationContext),
)

/** A device counts as a light if it is one, or if the user moved it to "Oświetlenie". */
fun isLightDevice(store: Store, d: Device): Boolean {
    val cat = store.category(d.key)
    return if (cat.isNotBlank()) cat == "LIGHTS" else d.kind == Kind.LIGHT
}

// =====================================================================
// Model: Gdy (triggers) → Tylko jeśli (conditions) → Wykonaj (steps)
// =====================================================================

enum class ActionType(val label: String) {
    ON("Włącz"), OFF("Wyłącz"), TOGGLE("Przełącz"), BRIGHTNESS("Jasność"), COLOR("Kolor"),
    POSITION("Otwarcie"), PRESS("Naciśnij");

    companion object {
        fun availableFor(d: Device): List<ActionType> = buildList {
            if (d.canPower) {
                add(ON); add(OFF)
                if (d.kind != Kind.IR) add(TOGGLE)
            }
            if (d.canBrightness) add(BRIGHTNESS)
            if (d.canColor) add(COLOR)
            if (d.canPosition) add(POSITION)
            if (d.kind == Kind.BOT) add(PRESS)
        }
    }
}

/** Device states that triggers and conditions can test. */
enum class StateKind(val label: String, val needsValue: Boolean = false) {
    ON("jest włączone"), OFF("jest wyłączone"),
    MOTION("wykrywa ruch"), NO_MOTION("nie wykrywa ruchu"),
    OPEN("jest otwarte"), CLOSED("jest zamknięte"),
    TEMP_ABOVE("temperatura powyżej", true), TEMP_BELOW("temperatura poniżej", true),
    HUM_ABOVE("wilgotność powyżej", true), HUM_BELOW("wilgotność poniżej", true),
    ONLINE("jest dostępne"), OFFLINE("jest niedostępne");

    fun test(d: Device, v: Double): Boolean = when (this) {
        ON -> d.online && d.on == true
        OFF -> d.online && d.on == false
        MOTION -> d.extra == "Wykryto ruch"
        NO_MOTION -> d.extra == "Brak ruchu"
        OPEN -> d.extra == "Otwarte" || (d.position != null && d.position > 0 && d.extra == null)
        CLOSED -> d.extra == "Zamknięte" || (d.position != null && d.position <= 0 && d.extra == null)
        TEMP_ABOVE -> (d.temperature ?: Double.NaN) > v
        TEMP_BELOW -> (d.temperature ?: Double.NaN) < v
        HUM_ABOVE -> (d.humidity ?: Double.NaN) > v
        HUM_BELOW -> (d.humidity ?: Double.NaN) < v
        ONLINE -> d.online
        OFFLINE -> !d.online
    }

    fun describe(v: Double): String = if (needsValue) {
        val unit = if (this == TEMP_ABOVE || this == TEMP_BELOW) "°C" else "%"
        "$label ${fmtNum(v)}$unit"
    } else label

    companion object {
        fun availableFor(d: Device): List<StateKind> = buildList {
            if (d.canPower) { add(ON); add(OFF) }
            if (d.extra == "Wykryto ruch" || d.extra == "Brak ruchu") { add(MOTION); add(NO_MOTION) }
            if (d.extra == "Otwarte" || d.extra == "Zamknięte" || d.canPosition) { add(OPEN); add(CLOSED) }
            if (d.temperature != null) { add(TEMP_ABOVE); add(TEMP_BELOW) }
            if (d.humidity != null) { add(HUM_ABOVE); add(HUM_BELOW) }
            add(ONLINE); add(OFFLINE)
        }
    }
}

fun fmtNum(v: Double): String = if (v == floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

enum class TriggerType(val label: String) { TIME("O godzinie"), SUN("Wschód / zachód"), DEVICE("Stan urządzenia"), INTERVAL("Co X minut") }

data class Trigger(
    val type: TriggerType,
    val time: Int = 7 * 60,
    val days: Set<Int> = (1..7).toSet(),
    val sunrise: Boolean = false,
    val offset: Int = 0,
    val device: Device? = null,
    val state: StateKind = StateKind.ON,
    val value: Double = 0.0,
    val minutes: Int = 15,
) {
    fun describe(): String = when (type) {
        TriggerType.TIME -> "O ${hhmm(time)} · ${daysText(days)}"
        TriggerType.SUN -> (if (sunrise) "Wschód słońca" else "Zachód słońca") + offsetText(offset) + " · ${daysText(days)}"
        TriggerType.DEVICE -> "Gdy ${device?.name ?: "?"} ${state.describe(value)}"
        TriggerType.INTERVAL -> "Co $minutes min"
    }
}

enum class ConditionType(val label: String) { TIME_RANGE("Między godzinami"), DAYS("Dni tygodnia"), SUN("Dzień / noc"), DEVICE("Stan urządzenia") }

data class Condition(
    val type: ConditionType,
    val from: Int = 22 * 60,
    val to: Int = 6 * 60,
    val days: Set<Int> = (1..5).toSet(),
    val night: Boolean = true,
    val device: Device? = null,
    val state: StateKind = StateKind.ON,
    val value: Double = 0.0,
) {
    fun describe(): String = when (type) {
        ConditionType.TIME_RANGE -> "Między ${hhmm(from)} a ${hhmm(to)}"
        ConditionType.DAYS -> daysText(days)
        ConditionType.SUN -> if (night) "Po zachodzie słońca (noc)" else "Za dnia"
        ConditionType.DEVICE -> "${device?.name ?: "?"} ${state.describe(value)}"
    }
}

enum class StepType(val label: String) {
    DEVICE("Urządzenie"), ALL_LIGHTS("Wszystkie światła"), ROOM_LIGHTS("Światła w pokoju"), DELAY("Czekaj"),
    NOTIFY("Powiadomienie"), AUTOMATION("Inna automatyzacja"),
}

enum class AutoMode(val label: String) { RUN("Uruchom"), ENABLE("Włącz"), DISABLE("Wyłącz") }

data class Step(
    val type: StepType,
    val device: Device? = null,
    val action: ActionType = ActionType.ON,
    val value: Int = 0,
    val on: Boolean = false,
    val room: String = "",
    val seconds: Int = 60,
    val text: String = "",
    val target: String = "",
    val mode: AutoMode = AutoMode.RUN,
) {
    fun describe(targetName: (String) -> String = { "?" }): String = when (type) {
        StepType.DEVICE -> "${device?.name ?: "?"}: " + when (action) {
            ActionType.BRIGHTNESS -> "jasność $value%"
            ActionType.POSITION -> "otwarcie $value%"
            else -> action.label.lowercase()
        }
        StepType.ALL_LIGHTS -> "Wszystkie światła: " + if (on) "włącz" else "wyłącz"
        StepType.ROOM_LIGHTS -> "Światła – $room: " + if (on) "włącz" else "wyłącz"
        StepType.DELAY -> "Czekaj " + if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"
        StepType.NOTIFY -> "Powiadomienie: „$text”"
        StepType.AUTOMATION -> "${mode.label}: ${targetName(target)}"
    }
}

data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val triggers: List<Trigger> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val steps: List<Step> = emptyList(),
) {
    fun summary(): String {
        val t = triggers.firstOrNull()?.describe() ?: "Uruchamiana ręcznie"
        val more = if (triggers.size > 1) " (+${triggers.size - 1})" else ""
        val c = if (conditions.isNotEmpty()) " · ${conditions.size} war." else ""
        return "$t$more$c · ${steps.size} dział."
    }
}

fun offsetText(m: Int) = when {
    m > 0 -> " +$m min"
    m < 0 -> " −${-m} min"
    else -> ""
}

private val DAY_SHORT = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")
fun dayShort(d: Int) = DAY_SHORT[d - 1]

fun hhmm(minutes: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

fun daysText(days: Set<Int>): String = when {
    days.size == 7 -> "codziennie"
    days == setOf(1, 2, 3, 4, 5) -> "Pn–Pt"
    days == setOf(6, 7) -> "weekend"
    days.isEmpty() -> "żaden dzień"
    else -> days.sorted().joinToString(", ") { dayShort(it) }
}

// ---------------- JSON ----------------

fun deviceToJson(d: Device): JSONObject = JSONObject()
    .put("provider", d.provider.name).put("id", d.id).put("name", d.name).put("kind", d.kind.name).put("model", d.model)
    .put("canPower", d.canPower).put("canBrightness", d.canBrightness).put("canColor", d.canColor).put("canPosition", d.canPosition)
    .put("meta", JSONObject(d.meta))

fun deviceFromJson(o: JSONObject): Device {
    val m = o.optJSONObject("meta") ?: JSONObject()
    return Device(
        provider = Provider.valueOf(o.getString("provider")),
        id = o.getString("id"),
        name = o.optString("name"),
        kind = Kind.valueOf(o.optString("kind", "OTHER")),
        model = o.optString("model"),
        canPower = o.optBoolean("canPower"),
        canBrightness = o.optBoolean("canBrightness"),
        canColor = o.optBoolean("canColor"),
        canPosition = o.optBoolean("canPosition"),
        meta = m.keys().asSequence().associateWith { m.optString(it) },
    )
}

private fun daysJson(d: Set<Int>) = JSONArray(d.sorted())
private fun daysFrom(a: JSONArray?): Set<Int> = if (a == null) (1..7).toSet() else (0 until a.length()).map { a.getInt(it) }.toSet()
private fun JSONObject.dev(): Device? = optJSONObject("device")?.let { runCatching { deviceFromJson(it) }.getOrNull() }
private inline fun <reified E : Enum<E>> JSONObject.enumOf(key: String, def: E): E =
    runCatching { enumValueOf<E>(getString(key)) }.getOrDefault(def)

fun Trigger.toJson(): JSONObject = JSONObject().put("type", type.name).put("time", time).put("days", daysJson(days))
    .put("sunrise", sunrise).put("offset", offset).put("device", device?.let { deviceToJson(it) })
    .put("state", state.name).put("value", value).put("minutes", minutes)

fun triggerFrom(o: JSONObject) = Trigger(
    type = o.enumOf("type", TriggerType.TIME), time = o.optInt("time", 420), days = daysFrom(o.optJSONArray("days")),
    sunrise = o.optBoolean("sunrise"), offset = o.optInt("offset"), device = o.dev(),
    state = o.enumOf("state", StateKind.ON), value = o.optDouble("value", 0.0), minutes = o.optInt("minutes", 15),
)

fun Condition.toJson(): JSONObject = JSONObject().put("type", type.name).put("from", from).put("to", to)
    .put("days", daysJson(days)).put("night", night).put("device", device?.let { deviceToJson(it) })
    .put("state", state.name).put("value", value)

fun conditionFrom(o: JSONObject) = Condition(
    type = o.enumOf("type", ConditionType.TIME_RANGE), from = o.optInt("from"), to = o.optInt("to"),
    days = daysFrom(o.optJSONArray("days")), night = o.optBoolean("night", true), device = o.dev(),
    state = o.enumOf("state", StateKind.ON), value = o.optDouble("value", 0.0),
)

fun Step.toJson(): JSONObject = JSONObject().put("type", type.name).put("device", device?.let { deviceToJson(it) })
    .put("action", action.name).put("value", value).put("on", on).put("room", room).put("seconds", seconds)
    .put("text", text).put("target", target).put("mode", mode.name)

fun stepFrom(o: JSONObject) = Step(
    type = o.enumOf("type", StepType.DEVICE), device = o.dev(), action = o.enumOf("action", ActionType.ON),
    value = o.optInt("value"), on = o.optBoolean("on"), room = o.optString("room"), seconds = o.optInt("seconds", 60),
    text = o.optString("text"), target = o.optString("target"), mode = o.enumOf("mode", AutoMode.RUN),
)

fun Automation.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("enabled", enabled).put("v", 2)
    .put("triggers", JSONArray(triggers.map { it.toJson() }))
    .put("conditions", JSONArray(conditions.map { it.toJson() }))
    .put("steps", JSONArray(steps.map { it.toJson() }))

fun automationFrom(o: JSONObject): Automation {
    fun <T> list(key: String, f: (JSONObject) -> T): List<T> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() }
    }
    if (o.has("triggers")) {
        return Automation(
            id = o.getString("id"), name = o.optString("name"), enabled = o.optBoolean("enabled", true),
            triggers = list("triggers", ::triggerFrom), conditions = list("conditions", ::conditionFrom),
            steps = list("steps", ::stepFrom),
        )
    }
    // First version: { time, days, actions: [{device, type, value}] }
    val time = o.optInt("time", -1)
    return Automation(
        id = o.getString("id"), name = o.optString("name"), enabled = o.optBoolean("enabled", true),
        triggers = if (time >= 0) listOf(Trigger(TriggerType.TIME, time = time, days = daysFrom(o.optJSONArray("days")))) else emptyList(),
        steps = list("actions") { a ->
            Step(StepType.DEVICE, device = deviceFromJson(a.getJSONObject("device")),
                action = a.enumOf("type", ActionType.ON), value = a.optInt("value"))
        },
    )
}

object AutomationStore {
    private const val KEY = "automations"

    fun load(store: Store): List<Automation> {
        val a = store.array(KEY)
        return (0 until a.length()).mapNotNull { runCatching { automationFrom(a.getJSONObject(it)) }.getOrNull() }
    }

    private fun save(store: Store, list: List<Automation>) = store.setArray(KEY, JSONArray(list.map { it.toJson() }))

    fun get(store: Store, id: String) = load(store).firstOrNull { it.id == id }

    fun upsert(store: Store, a: Automation): List<Automation> {
        val list = load(store).toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list += a
        save(store, list)
        return list
    }

    fun remove(store: Store, id: String): List<Automation> {
        val list = load(store).filter { it.id != id }
        save(store, list)
        return list
    }
}

// =====================================================================
// Sun times (NOAA "Almanac for Computers" algorithm, ±1–2 min)
// =====================================================================

object Sun {
    const val K_LAT = "home_lat"
    const val K_LON = "home_lon"

    fun location(store: Store): Pair<Double, Double> {
        val lat = store.get(K_LAT).replace(',', '.').toDoubleOrNull() ?: 52.23
        val lon = store.get(K_LON).replace(',', '.').toDoubleOrNull() ?: 21.01
        return lat to lon
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double) = ((v % m) + m) % m

    /** Time of sunrise or sunset on the given local day, in epoch millis; null in polar day/night. */
    fun event(day: Calendar, lat: Double, lon: Double, rising: Boolean): Long? {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15
        val localT = h + ra - 0.06571 * t - 6.622
        val ut = norm(localT - lngHour, 24.0)
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
        return c.timeInMillis + (ut * 3_600_000).toLong()
    }

    fun isNight(store: Store, now: Long = System.currentTimeMillis()): Boolean {
        val (lat, lon) = location(store)
        val today = Calendar.getInstance()
        val rise = event(today, lat, lon, true) ?: return false
        val set = event(today, lat, lon, false) ?: return false
        return now < rise || now > set
    }

    fun todayText(store: Store): String {
        val (lat, lon) = location(store)
        val today = Calendar.getInstance()
        fun f(ms: Long?) = ms?.let { val c = Calendar.getInstance(); c.timeInMillis = it; hhmm(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)) } ?: "—"
        return "Dziś: wschód ${f(event(today, lat, lon, true))}, zachód ${f(event(today, lat, lon, false))}"
    }
}

fun dayOfWeek(c: Calendar) = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Monday = 1

// =====================================================================
// Engine: checks conditions and runs the steps
// =====================================================================

object Engine {
    /** Last result of every device trigger, so it fires only when the state starts being true. */
    private val lastTrigger = ConcurrentHashMap<String, Boolean>()
    private val running = ConcurrentHashMap<String, Job>()

    fun isRunning(id: String) = running[id]?.isActive == true

    private fun log(store: Store, id: String, msg: String) = store.set("auto_last:$id", "${System.currentTimeMillis()}|$msg")

    /** Runs one automation. [force] skips conditions (manual run). */
    suspend fun execute(ctx: Context, a: Automation, force: Boolean = false, depth: Int = 0, sources: List<DeviceSource>? = null): String {
        val store = Store(ctx)
        val src = (sources ?: createSources(store, ctx)).associateBy { it.provider }
        if (!force) {
            val failed = firstFailedCondition(store, a, src)
            if (failed != null) {
                log(store, a.id, "pominięto – warunek: $failed")
                return "pominięto – warunek: $failed"
            }
        }
        val errors = mutableListOf<String>()
        var allDevices: List<Device>? = null
        suspend fun everything(): List<Device> {
            allDevices?.let { return it }
            val l = coroutineScope { src.values.filter { it.isConfigured() }.map { s -> async { runCatching { s.devices() }.getOrDefault(emptyList()) } }.awaitAll().flatten() }
            allDevices = l
            return l
        }
        suspend fun send(d: Device, cmd: Command) {
            try {
                (src[d.provider] ?: error("brak konta")).send(d, cmd)
            } catch (e: Exception) {
                errors += "${store.alias(d.key).ifBlank { d.name }}: ${e.message}"
            }
        }

        for (step in a.steps) {
            when (step.type) {
                StepType.DEVICE -> {
                    val d = step.device ?: continue
                    val cmd = when (step.action) {
                        ActionType.ON -> Command.Power(true)
                        ActionType.OFF -> Command.Power(false)
                        ActionType.TOGGLE -> {
                            val cur = runCatching { src[d.provider]?.refresh(listOf(d))?.firstOrNull() }.getOrNull()
                            Command.Power(cur?.on != true)
                        }
                        ActionType.BRIGHTNESS -> Command.Brightness(step.value)
                        ActionType.COLOR -> Command.Color(step.value)
                        ActionType.POSITION -> Command.Position(step.value)
                        ActionType.PRESS -> Command.Press
                    }
                    send(d, cmd)
                }
                StepType.ALL_LIGHTS, StepType.ROOM_LIGHTS -> {
                    val lights = everything().filter { d ->
                        isLightDevice(store, d) && d.canPower && d.online &&
                            (step.type == StepType.ALL_LIGHTS || store.room(d.key).ifBlank { DEFAULT_ROOM } == step.room)
                    }
                    coroutineScope { lights.map { d -> async { send(d, Command.Power(step.on)) } }.awaitAll() }
                }
                StepType.DELAY -> delay(step.seconds.coerceAtLeast(1) * 1000L)
                StepType.NOTIFY -> Notifier.show(ctx, a.name, step.text)
                StepType.AUTOMATION -> {
                    val other = AutomationStore.get(store, step.target) ?: continue
                    when (step.mode) {
                        AutoMode.RUN -> if (depth < 3 && other.id != a.id) execute(ctx, other, force = false, depth = depth + 1, sources = sources)
                        AutoMode.ENABLE, AutoMode.DISABLE -> {
                            AutomationStore.upsert(store, other.copy(enabled = step.mode == AutoMode.ENABLE))
                            AutomationScheduler.scheduleAll(ctx)
                        }
                    }
                }
            }
        }
        val msg = if (errors.isEmpty()) "wykonano" else "błędy – " + errors.joinToString("; ")
        log(store, a.id, msg)
        return msg
    }

    private suspend fun firstFailedCondition(store: Store, a: Automation, src: Map<Provider, DeviceSource>): String? {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val needed = a.conditions.mapNotNull { it.device }
        val fresh = if (needed.isEmpty()) emptyMap() else coroutineScope {
            needed.groupBy { it.provider }.map { (p, l) -> async { runCatching { src[p]?.refresh(l) }.getOrNull() ?: emptyList() } }
                .awaitAll().flatten().associateBy { it.key }
        }
        for (c in a.conditions) {
            val ok = when (c.type) {
                ConditionType.TIME_RANGE -> if (c.from <= c.to) minute in c.from until c.to else (minute >= c.from || minute < c.to)
                ConditionType.DAYS -> dayOfWeek(now) in c.days
                ConditionType.SUN -> Sun.isNight(store) == c.night
                ConditionType.DEVICE -> c.device?.let { d -> fresh[d.key]?.let { c.state.test(it, c.value) } } ?: false
            }
            if (!ok) return c.describe()
        }
        return null
    }

    /** Devices that some enabled automation watches (device triggers). */
    fun watched(store: Store): List<Device> = AutomationStore.load(store)
        .filter { it.enabled }
        .flatMap { a -> a.triggers.filter { it.type == TriggerType.DEVICE }.mapNotNull { it.device } }
        .distinctBy { it.key }

    /** Checks device triggers against fresh states; starts automations whose trigger just became true. */
    fun onDevices(ctx: Context, devices: Collection<Device>) {
        val store = Store(ctx)
        val byKey = devices.associateBy { it.key }
        for (a in AutomationStore.load(store)) {
            a.triggers.forEachIndexed { i, t ->
                if (t.type != TriggerType.DEVICE || t.device == null) return@forEachIndexed
                val d = byKey[t.device.key] ?: return@forEachIndexed
                val k = "${a.id}#$i"
                val now = t.state.test(d, t.value)
                val before = lastTrigger.put(k, now)
                if (a.enabled && before == false && now) AutomationService.run(ctx, a.id)
            }
        }
    }

    /** One background check: refresh only the watched devices. */
    suspend fun poll(ctx: Context, sources: List<DeviceSource>) {
        val store = Store(ctx)
        val watched = watched(store)
        if (watched.isEmpty()) return
        val src = sources.associateBy { it.provider }
        val fresh = coroutineScope {
            watched.groupBy { it.provider }.map { (p, l) -> async { runCatching { src[p]?.refresh(l) }.getOrNull() ?: emptyList() } }
                .awaitAll().flatten()
        }
        onDevices(ctx, fresh)
    }

    fun launch(scope: CoroutineScope, ctx: Context, a: Automation, force: Boolean, sources: List<DeviceSource>?, done: () -> Unit) {
        running[a.id]?.cancel()
        running[a.id] = scope.launch {
            try {
                execute(ctx, a, force = force, sources = sources)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) log(Store(ctx), a.id, "błąd – ${e.message}")
            } finally {
                running.remove(a.id)
                done()
            }
        }
    }
}

// =====================================================================
// Notifications
// =====================================================================

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_ALERTS = "alerts"

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Automatyzacje w tle", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Powiadomienia automatyzacji", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(ctx: Context, text: String): Notification {
        channels(ctx)
        return Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Różana")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    fun show(ctx: Context, title: String, text: String) {
        channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(title.ifBlank { "Różana" })
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
        } catch (_: SecurityException) {
        }
    }
}

// =====================================================================
// Background service: runs automations (with delays) and watches devices
// =====================================================================

class AutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    private val active = AtomicInteger(0)
    private val sources by lazy { createSources(Store(this), this) }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foreground(text: String) {
        val n = Notifier.service(this, text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        val monitoring = Monitor.enabled(store) && Engine.watched(store).isNotEmpty()
        foreground(if (monitoring) "Obserwuję urządzenia (${Engine.watched(store).size})" else "Wykonuję automatyzację…")

        val id = intent?.getStringExtra("id")
        if (id != null) {
            val a = AutomationStore.get(store, id)
            if (a != null) {
                active.incrementAndGet()
                Engine.launch(scope, this, a, intent?.getBooleanExtra("force", false) == true, sources) {
                    active.decrementAndGet()
                    stopIfIdle()
                }
            }
        }

        if (monitoring) {
            if (monitor?.isActive != true) {
                monitor = scope.launch {
                    while (isActive) {
                        runCatching { Engine.poll(this@AutomationService, sources) }
                        delay(Monitor.interval(Store(this@AutomationService)) * 1000L)
                    }
                }
            }
        } else {
            monitor?.cancel()
            monitor = null
            stopIfIdle()
        }
        return if (monitoring) START_STICKY else START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (monitor?.isActive != true && active.get() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts an automation in the service, so delays keep running even after the app is closed. */
        fun run(ctx: Context, id: String, force: Boolean = false) {
            val i = Intent(ctx, AutomationService::class.java).putExtra("id", id).putExtra("force", force)
            start(ctx, i)
        }

        /** Starts or stops background watching to match the settings. */
        fun sync(ctx: Context) = start(ctx, Intent(ctx, AutomationService::class.java))

        private fun start(ctx: Context, i: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
                // Android may refuse to start from the background; the next alarm or app start retries.
            }
        }
    }
}

object Monitor {
    const val K_ENABLED = "monitor_enabled"
    const val K_INTERVAL = "monitor_interval"
    fun enabled(store: Store) = store.get(K_ENABLED) == "1"
    fun interval(store: Store) = store.get(K_INTERVAL).toIntOrNull()?.coerceAtLeast(15) ?: 60
}

// =====================================================================
// Alarms for time, sun and interval triggers
// =====================================================================

object AutomationScheduler {
    private const val ACTION_RUN = "com.bartek.smarthome.RUN_AUTOMATION"

    fun next(store: Store, a: Automation, now: Calendar = Calendar.getInstance()): Long? {
        if (!a.enabled || a.steps.isEmpty()) return null
        val (lat, lon) = Sun.location(store)
        var best: Long? = null
        fun consider(t: Long?) {
            if (t != null && t > now.timeInMillis + 1000 && (best == null || t < best!!)) best = t
        }
        for (t in a.triggers) {
            when (t.type) {
                TriggerType.TIME, TriggerType.SUN -> for (add in 0..8) {
                    val c = now.clone() as Calendar
                    c.add(Calendar.DAY_OF_YEAR, add)
                    if (dayOfWeek(c) !in t.days) continue
                    val at = if (t.type == TriggerType.TIME) {
                        c.set(Calendar.HOUR_OF_DAY, t.time / 60)
                        c.set(Calendar.MINUTE, t.time % 60)
                        c.set(Calendar.SECOND, 0)
                        c.set(Calendar.MILLISECOND, 0)
                        c.timeInMillis
                    } else {
                        Sun.event(c, lat, lon, t.sunrise)?.plus(t.offset * 60_000L)
                    }
                    if (at != null && at > now.timeInMillis + 1000) {
                        consider(at)
                        break
                    }
                }
                TriggerType.INTERVAL -> {
                    val step = t.minutes.coerceAtLeast(1) * 60_000L
                    val last = store.getLong("auto_int:${a.id}").takeIf { it > 0 } ?: now.timeInMillis
                    var at = last + step
                    while (at <= now.timeInMillis + 1000) at += step
                    consider(at)
                }
                TriggerType.DEVICE -> {}
            }
        }
        return best
    }

    private fun pending(ctx: Context, id: String, flags: Int): PendingIntent? {
        val i = Intent(ctx, AutomationReceiver::class.java).setAction(ACTION_RUN).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        pending(ctx, id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    fun scheduleAll(ctx: Context) {
        val store = Store(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val list = AutomationStore.load(store)
        store.get("auto_scheduled").split(',').filter { it.isNotBlank() && list.none { a -> a.id == it } }.forEach { cancel(ctx, it) }
        val scheduled = mutableListOf<String>()
        for (a in list) {
            val at = next(store, a)
            if (at == null) {
                cancel(ctx, a.id)
                continue
            }
            val pi = pending(ctx, a.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            scheduled += a.id
        }
        store.set("auto_scheduled", scheduled.joinToString(","))
    }
}

/** Fired by the alarm. Hands the work to the service; if Android refuses, runs it here (without long delays). */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id")
        if (id == null) {
            AutomationScheduler.scheduleAll(context)
            return
        }
        val store = Store(context)
        val a = AutomationStore.get(store, id)
        if (a?.triggers?.any { it.type == TriggerType.INTERVAL } == true) store.setLong("auto_int:$id", System.currentTimeMillis())
        if (a == null || !a.enabled) {
            AutomationScheduler.scheduleAll(context)
            return
        }
        val i = Intent(context, AutomationService::class.java).putExtra("id", id)
        val started = try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            true
        } catch (_: Exception) {
            false
        }
        if (started) {
            AutomationScheduler.scheduleAll(context)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) { Engine.execute(context, a) }
            } catch (_: Exception) {
            } finally {
                AutomationScheduler.scheduleAll(context)
                pending.finish()
            }
        }
    }
}

/** Alarms are cleared on reboot and app update, so they are set again here (and watching restarts). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomationScheduler.scheduleAll(context)
        val store = Store(context)
        if (Monitor.enabled(store) && Engine.watched(store).isNotEmpty()) AutomationService.sync(context)
    }
}
