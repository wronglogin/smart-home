package com.bartek.smarthome

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

fun createSources(store: Store, ctx: Context): List<DeviceSource> = listOf(
    SwitchBotSource(store), GoveeSource(store), EweLinkSource(store),
    TapoSource(store, ctx.applicationContext), TvSource(store, ctx.applicationContext),
)

// ---------------- Model ----------------

enum class ActionType(val label: String) {
    ON("Włącz"), OFF("Wyłącz"), BRIGHTNESS("Jasność"), COLOR("Kolor"), POSITION("Otwarcie"), PRESS("Naciśnij");

    companion object {
        fun availableFor(d: Device): List<ActionType> = buildList {
            if (d.canPower) { add(ON); add(OFF) }
            if (d.canBrightness) add(BRIGHTNESS)
            if (d.canColor) add(COLOR)
            if (d.canPosition) add(POSITION)
            if (d.kind == Kind.BOT) add(PRESS)
        }
    }
}

/** One step of an automation. The device is stored as a snapshot so it can be controlled without loading the device list. */
data class AutoAction(val device: Device, val type: ActionType, val value: Int = 0) {
    fun command(): Command = when (type) {
        ActionType.ON -> Command.Power(true)
        ActionType.OFF -> Command.Power(false)
        ActionType.BRIGHTNESS -> Command.Brightness(value)
        ActionType.COLOR -> Command.Color(value)
        ActionType.POSITION -> Command.Position(value)
        ActionType.PRESS -> Command.Press
    }

    fun label(): String = when (type) {
        ActionType.BRIGHTNESS -> "Jasność $value%"
        ActionType.POSITION -> "Otwarcie $value%"
        else -> type.label
    }

    fun toJson(): JSONObject = JSONObject().put("device", deviceToJson(device)).put("type", type.name).put("value", value)

    companion object {
        fun fromJson(o: JSONObject) = AutoAction(
            deviceFromJson(o.getJSONObject("device")),
            ActionType.valueOf(o.getString("type")),
            o.optInt("value"),
        )
    }
}

data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val time: Int? = null,                 // minutes after midnight; null = only run by hand
    val days: Set<Int> = (1..7).toSet(),   // 1 = Monday … 7 = Sunday
    val actions: List<AutoAction> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled)
        .put("time", time ?: -1)
        .put("days", JSONArray(days.sorted()))
        .put("actions", JSONArray(actions.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): Automation {
            val d = o.optJSONArray("days") ?: JSONArray()
            val a = o.optJSONArray("actions") ?: JSONArray()
            return Automation(
                id = o.getString("id"),
                name = o.optString("name"),
                enabled = o.optBoolean("enabled", true),
                time = o.optInt("time", -1).takeIf { it >= 0 },
                days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                actions = (0 until a.length()).mapNotNull { runCatching { AutoAction.fromJson(a.getJSONObject(it)) }.getOrNull() },
            )
        }
    }
}

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

object AutomationStore {
    private const val KEY = "automations"

    fun load(store: Store): List<Automation> {
        val a = store.array(KEY)
        return (0 until a.length()).mapNotNull { runCatching { Automation.fromJson(a.getJSONObject(it)) }.getOrNull() }
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

// ---------------- Running ----------------

object AutomationRunner {
    /** Runs all steps: different devices in parallel, steps of one device in order. Returns a short result text. */
    suspend fun run(ctx: Context, a: Automation, sources: List<DeviceSource>? = null): String {
        val store = Store(ctx)
        val src = (sources ?: createSources(store, ctx)).associateBy { it.provider }
        val errors = coroutineScope {
            a.actions.groupBy { it.device.key }.values.map { steps ->
                async {
                    steps.mapNotNull { act ->
                        runCatching {
                            val s = src[act.device.provider] ?: error("brak konta")
                            s.send(act.device, act.command())
                        }.exceptionOrNull()?.let { "${store.alias(act.device.key).ifBlank { act.device.name }}: ${it.message}" }
                    }
                }
            }.awaitAll().flatten()
        }
        val msg = if (errors.isEmpty()) "wykonano" else "błędy – " + errors.joinToString("; ")
        store.set("auto_last:${a.id}", "${System.currentTimeMillis()}|$msg")
        return msg
    }
}

// ---------------- Scheduling ----------------

object AutomationScheduler {
    private const val ACTION_RUN = "com.bartek.smarthome.RUN_AUTOMATION"

    fun next(a: Automation, now: Calendar = Calendar.getInstance()): Long? {
        val t = a.time ?: return null
        if (!a.enabled || a.days.isEmpty() || a.actions.isEmpty()) return null
        for (add in 0..7) {
            val c = now.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, add)
            c.set(Calendar.HOUR_OF_DAY, t / 60)
            c.set(Calendar.MINUTE, t % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now.timeInMillis + 1000) continue
            val dow = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Monday = 1
            if (dow in a.days) return c.timeInMillis
        }
        return null
    }

    private fun pending(ctx: Context, id: String, flags: Int): PendingIntent? {
        val i = Intent(ctx, AutomationReceiver::class.java).setAction(ACTION_RUN).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        pending(ctx, id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    /** (Re)sets one alarm per timed automation. Called on app start, after changes, after each run and after reboot. */
    fun scheduleAll(ctx: Context) {
        val store = Store(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val list = AutomationStore.load(store)
        val old = store.get("auto_scheduled").split(',').filter { it.isNotBlank() }
        old.filter { id -> list.none { it.id == id } }.forEach { cancel(ctx, it) }
        val scheduled = mutableListOf<String>()
        for (a in list) {
            val at = next(a)
            if (at == null) {
                cancel(ctx, a.id)
                continue
            }
            val pi = pending(ctx, a.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            scheduled += a.id
        }
        store.set("auto_scheduled", scheduled.joinToString(","))
    }
}

/** Fired by the alarm at the automation's time. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id")
        if (id == null) {
            AutomationScheduler.scheduleAll(context)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = Store(context)
                val a = AutomationStore.get(store, id)
                if (a != null && a.enabled) {
                    val done = withTimeoutOrNull(9_000) { AutomationRunner.run(context, a) }
                    if (done == null) store.set("auto_last:$id", "${System.currentTimeMillis()}|przekroczono czas – część urządzeń nie odpowiedziała")
                }
            } catch (_: Exception) {
            } finally {
                AutomationScheduler.scheduleAll(context)
                pending.finish()
            }
        }
    }
}

/** Alarms are cleared on reboot and app update, so they are set again here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomationScheduler.scheduleAll(context)
    }
}
