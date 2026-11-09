@file:OptIn(ExperimentalMaterial3Api::class)

package dk.example.biltur

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var storage: WalkStorage

    private var isTracking by mutableStateOf(value = false)
    private var currentDistance by mutableDoubleStateOf(0.0)
    private var currentDuration by mutableLongStateOf(0L)
    private var currentSpeedKmh by mutableDoubleStateOf(0.0)
    private var currentMaxSpeedKmh by mutableDoubleStateOf(0.0)
    private var walks by mutableStateOf(emptyList<Walk>())

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted =
                (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)

            if (granted) startTrackingService()
            else toast("GPS-tilladelse er nødvendig for at måle turen.")
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startTrackingService()
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WalkTrackingService.ACTION_UPDATE -> {
                    currentDistance = intent.getDoubleExtra("distanceKm", 0.0)
                    currentDuration = intent.getLongExtra("durationSeconds", 0L)
                    currentSpeedKmh = intent.getDoubleExtra("speedKmh", 0.0)
                    currentMaxSpeedKmh = intent.getDoubleExtra("maxSpeedKmh", 0.0)
                    isTracking = true
                }

                WalkTrackingService.ACTION_WALK_FINISHED -> {
                    // Keep the completed walk visible on screen.
                    isTracking = false
                    currentDistance = intent.getDoubleExtra("distanceKm", currentDistance)
                    currentDuration = intent.getLongExtra("durationSeconds", currentDuration)
                    currentSpeedKmh = 0.0
                    currentMaxSpeedKmh = intent.getDoubleExtra("maxSpeedKmh", currentMaxSpeedKmh)
                    walks = storage.load()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = WalkStorage(this)
        walks = storage.load()

        setContent {
            MaterialTheme {
                WalkApp(
                    isTracking = isTracking,
                    distanceKm = currentDistance,
                    durationSeconds = currentDuration,
                    speedKmh = currentSpeedKmh,
                    maxSpeedKmh = currentMaxSpeedKmh,
                    walks = walks,
                    onStart = ::requestPermissionsAndStart,
                    onStop = ::stopTracking,
                    onDeleteWalk = ::deleteWalk,
                    onExport = ::exportCsv,
                    onClear = {
                        storage.clear()
                        walks = emptyList()
                        toast("Historikken er tømt.")
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WalkTrackingService.ACTION_UPDATE)
            addAction(WalkTrackingService.ACTION_WALK_FINISHED)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }

    private fun requestPermissionsAndStart() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        startTrackingService()
    }

    private fun startTrackingService() {
        if ((Build.VERSION.SDK_INT >= 33) &&
            (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val intent = Intent(this, WalkTrackingService::class.java)
            .setAction(WalkTrackingService.ACTION_START)

        ContextCompat.startForegroundService(this, intent)
        isTracking = true
    }

    private fun stopTracking() {
        startService(
            Intent(this, WalkTrackingService::class.java)
                .setAction(WalkTrackingService.ACTION_STOP),
        )
    }
	
	private fun deleteWalk(index: Int) {
		storage.deleteAt(index)
		walks = storage.load()
		toast("Bilturen er slettet.")
	}


    private fun exportCsv() {
        if (walks.isEmpty()) {
            toast("Der er ingen bilture at eksportere.")
            return
        }

        val filename =
            "bilture_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            }
        }

        val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = contentResolver.insert(contentUri, values)

        if (uri == null) {
            toast("Kunne ikke oprette CSV-filen.")
            return
        }

        fun csvCell(value: String): String {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }

        val csv = buildString {
            appendLine(
                "Dato;Startadresse;Slutadresse;Distance (km);" +
                        "Tid;Gennemsnit (km/t);Starttid;Sluttid"
            )

            walks.reversed().forEach { walk ->
                val distance = "%.3f".format(Locale.US, walk.distanceKm)
                val speed = "%.2f".format(Locale.US, walk.averageKmh)

                val startTime = if (walk.startTimeMs > 0L) {
                    SimpleDateFormat(
                        "dd-MM-yyyy HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date(walk.startTimeMs))
                } else ""

                val endTime = if (walk.endTimeMs > 0L) {
                    SimpleDateFormat(
                        "dd-MM-yyyy HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date(walk.endTimeMs))
                } else ""

                appendLine(
                    listOf(
                        csvCell(walk.date),
                        csvCell(walk.startAddress),
                        csvCell(walk.endAddress),
                        distance,
                        formatDuration(walk.durationSeconds),
                        speed,
                        csvCell(startTime),
                        csvCell(endTime)
                    ).joinToString(";")
                )
            }
        }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(
                    byteArrayOf(
                        0xEF.toByte(),
                        0xBB.toByte(),
                        0xBF.toByte()
                    )
                )
                out.write(csv.toByteArray(Charsets.UTF_8))
            }

            toast("CSV gemt i Download: $filename")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            toast("Fejl ved eksport: ${e.message}")
        }
    }


    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

@Composable
private fun WalkApp(
    isTracking: Boolean,
    distanceKm: Double,
    durationSeconds: Long,
    speedKmh: Double,
    maxSpeedKmh: Double,
    walks: List<Walk>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDeleteWalk: (Int) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    val averageSpeedKmh = if (durationSeconds > 0L) {
        distanceKm / (durationSeconds / 3600.0)
    } else {
        0.0
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mine bilture") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (isTracking) "Tur i gang" else "Klar til en tur",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "%.2f km".format(locale, distanceKm),
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        formatDuration(durationSeconds),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Aktuel hastighed",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "%.1f km/t".format(locale, speedKmh),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Gennemsnit",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "%.1f km/t".format(locale, averageSpeedKmh),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Maksimal hastighed",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        "%.1f km/t".format(locale, maxSpeedKmh),
                        style = MaterialTheme.typography.titleLarge
                    )

                    Spacer(Modifier.height(18.dp))

                    if (isTracking) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("⏹  Stop tur")
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("▶  Start tur")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExport,
                    enabled = walks.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Eksportér CSV")
                }
                OutlinedButton(
                    onClick = onClear,
                    enabled = walks.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tøm liste")
                }
            }

            Spacer(Modifier.height(8.dp))

            Text("Historik", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            if (walks.isEmpty()) {
                Text(
                    "Ingen ture endnu. Tryk Start tur for at komme i gang.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(walks.size) { index ->
                        WalkRow(
                            walk = walks[index],
                            onDelete = { onDeleteWalk(index) },
                        )

                    }
                }
            }
        }
    }
}


@Composable
private fun WalkRow(
    walk: Walk,
    onDelete: () -> Unit
) {
    val locale = LocalConfiguration.current.locales[0]
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "${getWeekday(walk.date)} ${walk.date}",
                    style = MaterialTheme.typography.titleMedium
                )

                Text("Distance: %.3f km".format(locale, walk.distanceKm))

                if (walk.startAddress.isNotBlank()) {
                    Text(
                        "Fra: ${walk.startAddress}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (walk.endAddress.isNotBlank()) {
                    Text(
                        "Til: ${walk.endAddress}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(formatDuration(walk.durationSeconds))

                Text(
                    "Gns. %.2f km/t".format(locale, walk.averageKmh)
                )

                Text(
                    "Maks. %.1f km/t".format(locale, walk.maxSpeedKmh)
                )
            }

            IconButton(
                onClick = { showDeleteDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Slet biltur",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
            },
            title = {
                Text("Slet biltur?")
            },
            text = {
                Text(
                    "Vil du slette bilturen fra ${walk.date}? " +
                            "Denne handling kan ikke fortrydes."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Slet")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                    }
                ) {
                    Text("Annuller")
                }
            }
        )
    }
}

private fun getWeekday(dateString: String): String {
    val inputFormats = listOf(
        "dd-MM-yyyy HH:mm",
        "dd-MM-yyyy",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )

    for (pattern in inputFormats) {
        try {
            val inputFormat = SimpleDateFormat(pattern, Locale.ROOT).apply {
                isLenient = false
            }
            val date = inputFormat.parse(dateString) ?: continue

            return SimpleDateFormat("EEE", Locale.forLanguageTag("da-DK"))
                .format(date)
                .lowercase(Locale.ROOT)
                .replace(".", "")
        } catch (_: Exception) {
            // Prøv næste datoformat.
        }
    }

    return ""
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}
