
package dk.example.biltur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WalkTrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient

    // Seneste accepterede GPS-position
    private var lastLocation: Location? = null

    // GPS-position ved turens start
    private var startLocation: Location? = null

    private var distanceMeters = 0.0
    private var startTimeMs = 0L
    private var currentSpeedKmh = 0.0
    private var isTracking = false
    private var isSaving = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (location in result.locations) {

                // Ignorer positioner med dårlig GPS-nøjagtighed
                if (location.hasAccuracy() && location.accuracy > 50f) {
                    continue
                }

                val previous = lastLocation
                var validDistanceDelta: Double? = null

                if (previous == null) {
                    lastLocation = location
                    startLocation = location
                } else {
                    val elapsedSeconds =
                        (location.time - previous.time) / 1000.0

                    if (elapsedSeconds <= 0.0) {
                        continue
                    }

                    val delta = previous.distanceTo(location).toDouble()

                    // Maksimal realistisk hastighed: 220 km/t
                    val maxSpeedMetersPerSecond = 220.0 / 3.6

                    val previousAccuracy =
                        if (previous.hasAccuracy()) previous.accuracy.toDouble()
                        else 0.0

                    val currentAccuracy =
                        if (location.hasAccuracy()) location.accuracy.toDouble()
                        else 0.0

                    val accuracyAllowance = maxOf(
                        20.0,
                        (previousAccuracy + currentAccuracy) * 1.5
                    )

                    val maxAllowedDistance =
                        elapsedSeconds * maxSpeedMetersPerSecond +
                                accuracyAllowance

                    if (delta <= maxAllowedDistance) {

                        // Ignorer små GPS-bevægelser under 2 meter
                        if (delta >= 2.0) {
                            distanceMeters += delta
                            validDistanceDelta = delta
                        }

                        lastLocation = location
                    } else {
                        // Sandsynligt GPS-hop
                        continue
                    }
                }

                // Brug GPS-hastigheden, hvis den er gyldig
                currentSpeedKmh = when {
                    location.hasSpeed() &&
                            location.speed.isFinite() &&
                            location.speed >= 0f &&
                            location.speed <= 70f -> {
                        location.speed.toDouble() * 3.6
                    }

                    previous != null && validDistanceDelta != null -> {
                        val elapsedSeconds =
                            (location.time - previous.time) / 1000.0

                        if (elapsedSeconds > 0.0) {
                            validDistanceDelta / elapsedSeconds * 3.6
                        } else {
                            0.0
                        }
                    }

                    else -> 0.0
                }

                sendUpdate()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        fused = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTrackingAndSave()
        }

        return START_NOT_STICKY
    }

    private fun startTracking() {
        // Undgå at starte samme tur flere gange
        if (isTracking || isSaving) return

        distanceMeters = 0.0
        lastLocation = null
        startLocation = null
        currentSpeedKmh = 0.0
        startTimeMs = System.currentTimeMillis()
        isTracking = true

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Biltur er i gang")
        )

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L
        )
            .setMinUpdateIntervalMillis(1500L)
            .setMinUpdateDistanceMeters(3f)
            .build()

        try {
            fused.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            isTracking = false
            startTimeMs = 0L
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        sendUpdate()
    }

    private fun stopTrackingAndSave() {
        // Undgå at gemme en tom tur eller gemme to gange
        if (!isTracking || startTimeMs == 0L || isSaving) {
            stopSelf()
            return
        }

        isTracking = false
        isSaving = true

        fused.removeLocationUpdates(callback)

        val endTimeMs = System.currentTimeMillis()

        val duration = ((endTimeMs - startTimeMs) / 1000L)
            .coerceAtLeast(0L)

        // Gem positionerne, inden adresseopslaget begynder
        val savedStartLocation = startLocation
        val savedEndLocation = lastLocation

        // Vis at appen stadig arbejder
        val notification = buildNotification("Gemmer biltur...")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)

        // Adresseopslag udføres i baggrunden
        Thread {
            val startAddress = getAddress(savedStartLocation)
            val endAddress = getAddress(savedEndLocation)

            mainHandler.post {
                val walk = Walk(
                    date = SimpleDateFormat(
                        "dd-MM-yyyy HH:mm",
                        Locale.getDefault()
                    ).format(Date(startTimeMs)),
                    distanceKm = distanceMeters / 1000.0,
                    durationSeconds = duration,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    startAddress = startAddress,
                    endAddress = endAddress,
                    startLatitude = savedStartLocation?.latitude,
                    startLongitude = savedStartLocation?.longitude,
                    endLatitude = savedEndLocation?.latitude,
                    endLongitude = savedEndLocation?.longitude
                )

                WalkStorage(this).add(walk)

                sendBroadcast(
                    Intent(ACTION_WALK_FINISHED).apply {
                        setPackage(packageName)
                        putExtra("distanceKm", walk.distanceKm)
                        putExtra("durationSeconds", walk.durationSeconds)
                    }
                )

                startTimeMs = 0L
                currentSpeedKmh = 0.0
                lastLocation = null
                startLocation = null
                isSaving = false

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    // Slår GPS-koordinater op til en læsbar adresse
    @Suppress("DEPRECATION")
    private fun getAddress(location: Location?): String {
        if (location == null) return ""

        return try {
            val geocoder = Geocoder(this, Locale.getDefault())

            if (!Geocoder.isPresent()) {
                return ""
            }

            val addresses = geocoder.getFromLocation(
                location.latitude,
                location.longitude,
                1
            )

            addresses
                ?.firstOrNull()
                ?.getAddressLine(0)
                ?: ""

        } catch (e: IOException) {
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun sendUpdate() {
        sendBroadcast(
            Intent(ACTION_UPDATE).apply {
                setPackage(packageName)

                putExtra("distanceKm", distanceMeters / 1000.0)

                putExtra(
                    "durationSeconds",
                    if (startTimeMs == 0L) 0L
                    else (System.currentTimeMillis() - startTimeMs) / 1000L
                )

                putExtra("speedKmh", currentSpeedKmh)
            }
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bilture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bilture GPS",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (::fused.isInitialized) {
            fused.removeLocationUpdates(callback)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dk.example.biltur.START"
        const val ACTION_STOP = "dk.example.biltur.STOP"
        const val ACTION_UPDATE = "dk.example.biltur.UPDATE"
        const val ACTION_WALK_FINISHED =
            "dk.example.biltur.WALK_FINISHED"

        private const val CHANNEL_ID = "walk_tracking"
        private const val NOTIFICATION_ID = 1001
    }
}
