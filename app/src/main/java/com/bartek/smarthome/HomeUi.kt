package com.bartek.smarthome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

object Palette {
    val Bg = Color(0xFF1E1F22)
    val Tile = Color(0xFF2E3033)
    val TileOnLight = Color(0xFFFFE3A3)
    val TileOnOther = Color(0xFFC7D8FF)
    val OnLightText = Color(0xFF3A2E00)
    val OnOtherText = Color(0xFF0B1D44)
    val ChipSel = Color(0xFF3D4A66)
    val Primary = Color(0xFFA8C7FA)
    val Bar = Color(0xFF26272A)
}

@Composable
fun SmartHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Primary,
            onPrimary = Color(0xFF062E6F),
            background = Palette.Bg,
            surface = Palette.Bg,
            onSurface = Color(0xFFE3E3E3),
            onSurfaceVariant = Color(0xFFC4C7C5),
            secondaryContainer = Palette.ChipSel,
            onSecondaryContainer = Color.White,
            surfaceContainer = Palette.Bar,
            surfaceContainerHigh = Palette.Tile,
            surfaceContainerHighest = Color(0xFF37393C),
        ),
        content = content,
    )
}

enum class Filter(val label: String) {
    FAV("Ulubione"), ALL("Wszystkie urządzenia"), LIGHTS("Oświetlenie"), PLUGS("Gniazdka"),
    COVERS("Rolety"), SENSORS("Czujniki"), MEDIA("Telewizory");

    fun match(d: Device, fav: Boolean): Boolean = when (this) {
        FAV -> fav
        ALL -> true
        LIGHTS -> d.kind == Kind.LIGHT
        PLUGS -> d.kind == Kind.PLUG || d.kind == Kind.BOT
        COVERS -> d.kind == Kind.CURTAIN || d.kind == Kind.BLIND
        SENSORS -> d.kind == Kind.SENSOR || d.temperature != null
        MEDIA -> d.kind == Kind.TV || d.kind == Kind.IR
    }
}

fun kindIcon(k: Kind, on: Boolean?): ImageVector = when (k) {
    Kind.LIGHT -> if (on == true) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb
    Kind.PLUG -> Icons.Outlined.Power
    Kind.CURTAIN, Kind.BLIND -> Icons.Outlined.ViewWeek
    Kind.BOT -> Icons.Outlined.TouchApp
    Kind.SENSOR -> Icons.Outlined.Thermostat
    Kind.TV -> Icons.Outlined.Tv
    Kind.IR -> Icons.Outlined.SettingsRemote
    Kind.OTHER -> Icons.Outlined.Devices
}

private val PL = Locale.forLanguageTag("pl")

fun statusText(d: Device): String {
    if (!d.online) return "Offline"
    val parts = mutableListOf<String>()
    when (d.kind) {
        Kind.SENSOR -> {}
        Kind.CURTAIN, Kind.BLIND -> d.position?.let {
            parts.add(if (it <= 0) "Zamknięte" else if (it >= 100) "Otwarte" else "Otwarte w $it%")
        }
        Kind.BOT -> parts.add("Naciśnij")
        Kind.IR -> parts.add("Pilot IR")
        Kind.TV -> parts.add(
            when (d.on) {
                true -> "Włączony"
                false -> "Wyłączony"
                null -> "Pilot"
            },
        )
        else -> d.on?.let {
            parts.add(if (it) "Włączono" else "Wyłączono")
            if (it && d.brightness != null) parts.add("${d.brightness}%")
        }
    }
    d.temperature?.let { parts.add(String.format(PL, "%.1f°C", it)) }
    d.humidity?.let { parts.add("${it.roundToInt()}%") }
    if (d.kind == Kind.SENSOR && d.temperature == null && d.extra != null) parts.add(d.extra)
    return parts.joinToString(" · ").ifEmpty { d.provider.label }
}

@Composable
fun HomeScreen(vm: HomeViewModel, openSettings: () -> Unit) {
    vm.version // re-read names, rooms and favourites when they change
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selected by remember { mutableStateOf<String?>(null) }
    val devices = vm.devices

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Icon(Icons.Outlined.Home, null)
                    Spacer(Modifier.width(14.dp))
                    Text("Mój dom", style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalIconButton(onClick = { vm.refresh() }, modifier = Modifier.size(56.dp)) {
                if (vm.loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, "Odśwież")
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Filter.entries) { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label, modifier = Modifier.padding(vertical = 10.dp)) },
                    leadingIcon = if (f == Filter.FAV) {
                        { Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.ChipSel,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                    ),
                )
            }
        }

        vm.errors.forEach { (p, msg) ->
            Text(
                "${p.label}: $msg",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!vm.anyConfigured()) {
            EmptyState(openSettings)
        } else {
            val shown = devices.filter { filter.match(it, vm.isFav(it)) }
            val groups = shown.groupBy { vm.room(it) }
                .toSortedMap(compareBy { if (it == DEFAULT_ROOM) "￿" else it.lowercase() })
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shown.isEmpty() && !vm.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (filter == Filter.FAV) "Brak ulubionych. Przytrzymaj kafelek i dotknij serca." else "Brak urządzeń",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                groups.forEach { (room, list) ->
                    item(key = "room:$room", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            room,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { it.key }) { d -> DeviceTile(vm, d) { selected = d.key } }
                }
            }
        }
    }

    val sel = devices.firstOrNull { it.key == selected }
    if (sel != null) DeviceSheet(vm, sel) { selected = null }
}

@Composable
private fun EmptyState(openSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Home, null, Modifier.size(64.dp), tint = Palette.Primary)
        Spacer(Modifier.height(16.dp))
        Text("Dodaj swoje urządzenia", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Połącz konta SwitchBot, Govee, eWeLink i Tapo albo sparuj telewizor.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = openSettings) { Text("Przejdź do ustawień") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceTile(vm: HomeViewModel, d: Device, onOpen: () -> Unit) {
    val active = d.online && (d.on == true || ((d.kind == Kind.CURTAIN || d.kind == Kind.BLIND) && (d.position ?: 0) > 0))
    val bg = when {
        !active -> Palette.Tile
        d.kind == Kind.LIGHT -> Palette.TileOnLight
        else -> Palette.TileOnOther
    }
    val fg = when {
        !active -> Color.White
        d.kind == Kind.LIGHT -> Palette.OnLightText
        else -> Palette.OnOtherText
    }
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = bg,
        contentColor = fg,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .combinedClickable(
                onClick = {
                    when {
                        !d.online -> onOpen()
                        d.kind == Kind.BOT -> vm.send(d, Command.Press)
                        d.canPower && d.kind != Kind.IR && d.kind != Kind.TV -> vm.send(d, Command.Power(d.on != true))
                        else -> onOpen()
                    }
                },
                onLongClick = onOpen,
            ),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(d.kind, d.on), null, Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    vm.name(d),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusText(d),
                    style = MaterialTheme.typography.bodyMedium,
                    color = fg.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------- Device details ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(vm: HomeViewModel, d: Device, onDismiss: () -> Unit) {
    vm.version
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vm.name(d), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${d.provider.label} · ${vm.room(d)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.toggleFav(d) }) {
                    Icon(if (vm.isFav(d)) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Ulubione")
                }
            }

            if (!d.online) {
                Text("Urządzenie niedostępne" + (d.extra?.let { "\n$it" } ?: ""), color = MaterialTheme.colorScheme.error)
            }

            if (d.temperature != null || d.humidity != null || d.battery != null || (d.online && d.extra != null)) {
                SensorBlock(d)
            }

            when (d.kind) {
                Kind.TV -> if (d.meta["ir"] == "1") PowerButtons(vm, d) else TvRemote(vm, d)
                Kind.IR -> PowerButtons(vm, d)
                Kind.BOT -> Button(
                    onClick = { vm.send(d, Command.Press) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Naciśnij") }
                else -> if (d.canPower) PowerToggle(vm, d)
            }
            if (d.canBrightness) BrightnessSlider(vm, d)
            if (d.canColor) ColorRow(vm, d)
            if (d.canPosition) PositionControl(vm, d)

            HorizontalDivider()
            NameAndRoom(vm, d)
        }
    }
}

@Composable
private fun SensorBlock(d: Device) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        d.temperature?.let { Stat("Temperatura", String.format(PL, "%.1f°C", it), Modifier.weight(1f)) }
        d.humidity?.let { Stat("Wilgotność", "${it.roundToInt()}%", Modifier.weight(1f)) }
        d.battery?.let { Stat("Bateria", "$it%", Modifier.weight(1f)) }
    }
    if (d.online && d.extra != null) Text(d.extra, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun PowerToggle(vm: HomeViewModel, d: Device) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PowerSettingsNew, null)
            Spacer(Modifier.width(12.dp))
            Text(
                if (d.on == true) "Włączone" else "Wyłączone",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = d.on == true, onCheckedChange = { vm.send(d, Command.Power(it)) })
        }
    }
}

@Composable
private fun PowerButtons(vm: HomeViewModel, d: Device) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.send(d, Command.Power(true)) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Włącz") }
        OutlinedButton(onClick = { vm.send(d, Command.Power(false)) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Wyłącz") }
    }
}

@Composable
private fun BrightnessSlider(vm: HomeViewModel, d: Device) {
    var v by remember(d.key, d.brightness) { mutableFloatStateOf((d.brightness ?: 100).toFloat()) }
    Column {
        Text("Jasność: ${v.roundToInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = 1f..100f,
            onValueChangeFinished = { vm.send(d, Command.Brightness(v.roundToInt())) },
        )
    }
}

private val COLORS = listOf(
    0xFFFFFF, 0xFFD59A, 0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x32ADE6, 0x007AFF, 0xAF52DE, 0xFF2D92,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(vm: HomeViewModel, d: Device) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kolor", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            COLORS.forEach { c ->
                val sel = d.color == c
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF000000L or c.toLong()))
                        .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color(0x55FFFFFF), CircleShape)
                        .clickable { vm.send(d, Command.Color(c)) },
                )
            }
        }
    }
}

@Composable
private fun PositionControl(vm: HomeViewModel, d: Device) {
    var v by remember(d.key, d.position) { mutableFloatStateOf((d.position ?: 0).toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.kind != Kind.BLIND) {
            Text("Otwarcie: ${v.roundToInt()}%", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = v,
                onValueChange = { v = it },
                valueRange = 0f..100f,
                onValueChangeFinished = { vm.send(d, Command.Position(v.roundToInt())) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.send(d, Command.Position(100)) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Otwórz") }
            OutlinedButton(onClick = { vm.send(d, Command.Position(0)) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Zamknij") }
        }
    }
}

private val TV_APPS = listOf(
    "YouTube" to "https://www.youtube.com",
    "Netflix" to "https://www.netflix.com/title",
    "Prime Video" to "https://app.primevideo.com",
    "Disney+" to "https://www.disneyplus.com",
    "Max" to "https://play.max.com",
    "Spotify" to "spotify://",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvRemote(vm: HomeViewModel, d: Device) {
    fun key(code: Int) = vm.send(d, Command.Key(code))
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PowerButtons(vm, d)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteButton(Icons.Outlined.PowerSettingsNew, "Zasilanie") { key(TvSource.KEY_POWER) }
            RemoteButton(Icons.Outlined.Home, "Ekran główny") { key(3) }
            RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") { key(4) }
        }
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteButton(Icons.Filled.KeyboardArrowUp, "Góra") { key(19) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Lewo") { key(21) }
                    FilledIconButton(onClick = { key(23) }, modifier = Modifier.size(72.dp).padding(4.dp)) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                    RemoteButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Prawo") { key(22) }
                }
                RemoteButton(Icons.Filled.KeyboardArrowDown, "Dół") { key(20) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "Ciszej") { key(25) }
            RemoteButton(Icons.AutoMirrored.Filled.VolumeOff, "Wycisz") { key(164) }
            RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "Głośniej") { key(24) }
            RemoteButton(Icons.Filled.PlayArrow, "Odtwórz/Pauza") { key(85) }
        }
        Text("Aplikacje", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TV_APPS.forEach { (name, link) ->
                AssistChip(onClick = { vm.send(d, Command.App(link)) }, label = { Text(name) })
            }
        }
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(60.dp)) { Icon(icon, desc) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameAndRoom(vm: HomeViewModel, d: Device) {
    var name by remember(d.key) { mutableStateOf(vm.name(d)) }
    var room by remember(d.key) { mutableStateOf(vm.store.room(d.key)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Nazwa") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = room, onValueChange = { room = it }, label = { Text("Pokój") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        val rooms = vm.rooms().filter { it != DEFAULT_ROOM }
        if (rooms.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.forEach { r -> SuggestionChip(onClick = { room = r }, label = { Text(r) }) }
            }
        }
        Button(onClick = { vm.rename(d, name, room) }, modifier = Modifier.fillMaxWidth()) { Text("Zapisz") }
        Text(
            listOf(d.provider.label, d.model).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
