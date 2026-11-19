package com.bartek.smarthome

import android.content.Context
import android.speech.tts.TextToSpeech
import java.text.Normalizer
import java.util.Locale

/** Reads replies aloud in Polish. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("pl-PL")
                ready = true
                pending?.let { say(it) }
                pending = null
            }
        }
    }

    fun say(text: String) {
        val t = tts ?: return
        if (!ready) {
            pending = text
            return
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }
}

/** What a spoken command turned into. */
data class VoiceResult(
    val reply: String,
    val actions: List<Pair<Device, Command>> = emptyList(),
)

/**
 * Simple Polish command understanding, used when Home Assistant is not connected.
 * Matches device and room names by word stems, so "kuchni" finds "Kuchnia" and "lampę" finds "Lampa".
 */
object VoiceParser {
    private val PL = Locale.forLanguageTag("pl")
    private val STOP = setOf("w", "we", "na", "do", "i", "oraz", "mi", "prosze", "ten", "ta", "to", "z", "ze", "o", "po")

    fun norm(s: String): String {
        val lower = s.lowercase(PL).replace('ł', 'l')
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    fun stems(s: String): List<String> = norm(s).split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() && it !in STOP }
        .map { if (it.length >= 4) it.take(4) else it }

    private fun names(list: List<Device>, nameOf: (Device) -> String): String = when {
        list.size <= 3 -> list.joinToString(", ") { nameOf(it) }
        else -> "${list.size} urządzeń"
    }

    fun parse(
        text: String,
        devices: List<Device>,
        nameOf: (Device) -> String,
        roomOf: (Device) -> String,
        isLight: (Device) -> Boolean,
    ): VoiceResult {
        val t = norm(text)
        val ts = stems(text).toSet()
        fun has(vararg w: String) = w.any { t.contains(it) }

        val open = has("otworz", "odslon", "podnies")
        val close = !open && (has("zamknij", "opusc") || t.startsWith("zaslon"))
        val on = has("wlacz", "zapal", "odpal", "uruchom", "aktywuj")
        val off = has("wylacz", "zgas")
        val dim = has("przyciem")
        val bright = has("rozjasn")
        val pct = Regex("(\\d{1,3})\\s*(%|proc)").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\bna (\\d{1,3})\\b").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val askTemp = has("temperatur", "stopni", "jak cieplo", "jak zimno", "ile jest")
        val askHum = has("wilgotn")
        val allWord = has("wszystk", "caly dom", "calym domu", "w domu", "wszedzie")
        val lightWord = has("swiat", "lamp", "oswietl", "zarowk")
        val coverWord = has("rolet", "zaslon", "zaluz", "story")

        val rooms = devices.map(roomOf).distinct().filter { it != DEFAULT_ROOM }
        val room = rooms.filter { r -> stems(r).let { s -> s.isNotEmpty() && s.all { it in ts } } }.maxByOrNull { it.length }
        val inRoom: (Device) -> Boolean = { room == null || roomOf(it) == room }
        val where = room?.let { " ($it)" } ?: ""

        // Questions about temperature / humidity
        if (askTemp || askHum) {
            val sensors = devices.filter { (if (askHum) it.humidity else it.temperature) != null && it.online && inRoom(it) }
            if (sensors.isEmpty()) return VoiceResult("Nie mam czujnika" + (room?.let { " w pokoju $it" } ?: ""))
            val parts = sensors.take(3).map { d ->
                val v = if (askHum) "${d.humidity!!.toInt()}%" else String.format(PL, "%.1f stopnia", d.temperature)
                "${nameOf(d)}: $v"
            }
            return VoiceResult(parts.joinToString(", "))
        }

        // Devices named in the sentence (keep the most specific names: "lampa biurko" beats "lampa")
        val named = devices.filter { d -> stems(nameOf(d)).let { s -> s.isNotEmpty() && s.all { it in ts } } }
        val best = named.filter { d ->
            val ds = stems(nameOf(d))
            named.none { o -> o.key != d.key && stems(nameOf(o)).let { os -> os.size > ds.size && ds.all { it in os } } }
        }

        val targets: List<Device> = when {
            best.isNotEmpty() -> best
            coverWord -> devices.filter { it.canPosition && (allWord || inRoom(it)) }
            lightWord && (allWord || room != null) -> devices.filter { isLight(it) && (allWord || inRoom(it)) }
            lightWord -> emptyList()
            room != null && (on || off) -> devices.filter { inRoom(it) && isLight(it) }
            allWord && off -> devices.filter { isLight(it) }
            else -> emptyList()
        }.filter { it.online }

        if (targets.isEmpty()) {
            return VoiceResult(if (lightWord && room == null && !allWord) "W którym pokoju?" else "Nie zrozumiałem: „$text”")
        }

        val covers = targets.all { it.canPosition }
        val cmd = when {
            pct != null && covers -> Command.Position(pct.coerceIn(0, 100))
            pct != null -> Command.Brightness(pct.coerceIn(1, 100))
            dim -> Command.Brightness(20)
            bright -> Command.Brightness(100)
            open -> if (covers) Command.Position(100) else Command.Power(true)
            close -> if (covers) Command.Position(0) else Command.Power(false)
            off -> Command.Power(false)
            on -> Command.Power(true)
            else -> null
        } ?: return VoiceResult("Co mam zrobić z: ${names(targets, nameOf)}?")

        val actions = targets.mapNotNull { d ->
            when (cmd) {
                is Command.Power -> when {
                    d.kind == Kind.BOT && cmd.on -> d to Command.Press
                    d.canPower -> d to cmd
                    d.canPosition -> d to Command.Position(if (cmd.on) 100 else 0)
                    else -> null
                }
                is Command.Brightness -> if (d.canBrightness) d to cmd else null
                is Command.Position -> if (d.canPosition) d to cmd else null
                else -> null
            }
        }
        if (actions.isEmpty()) return VoiceResult("Tego nie da się zrobić z: ${names(targets, nameOf)}")

        val list = names(actions.map { it.first }, nameOf)
        val reply = when (cmd) {
            is Command.Power -> (if (cmd.on) "Włączam" else "Wyłączam") + ": $list"
            is Command.Brightness -> "Ustawiam jasność ${cmd.value}%: $list"
            is Command.Position -> when (cmd.openPercent) {
                100 -> "Otwieram: $list"
                0 -> "Zamykam: $list"
                else -> "Ustawiam ${cmd.openPercent}%: $list"
            }
            else -> "Gotowe"
        }
        return VoiceResult(if (actions.size > 3 && room != null) reply + where else reply, actions = actions)
    }
}
