
package dk.example.biltur

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.validation.HostValidator
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale

class BiltureCarAppService : CarAppService() {

    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator {
        return if (
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this)
                .addAllowedHosts(
                    androidx.car.app.R.array.hosts_allowlist_sample
                )
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return BiltureSession()
    }
}

class BiltureSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return BiltureCarScreen(carContext)
    }
}

class BiltureCarScreen(
    carContext: CarContext
) : Screen(carContext) {

    private var distanceKm = 0.0
    private var durationSeconds = 0L
    private var speedKmh = 0.0
    private var isTracking = false
    private var statusMessage = "Klar til biltur"

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: android.content.Context?,
            intent: Intent?
        ) {
            when (intent?.action) {

                WalkTrackingService.ACTION_UPDATE -> {
                    distanceKm =
                        intent.getDoubleExtra("distanceKm", 0.0)

                    durationSeconds =
                        intent.getLongExtra("durationSeconds", 0L)

                    speedKmh =
                        intent.getDoubleExtra("speedKmh", 0.0)

                    isTracking = true
                    statusMessage = "Biltur er i gang"

                    invalidate()
                }

                WalkTrackingService.ACTION_WALK_FINISHED -> {
                    distanceKm =
                        intent.getDoubleExtra("distanceKm", 0.0)

                    durationSeconds =
                        intent.getLongExtra("durationSeconds", 0L)

                    speedKmh = 0.0
                    isTracking = false
                    statusMessage = "Biltur gemt"

                    invalidate()
                }
            }
        }
    }

    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->

                when (event) {

                    Lifecycle.Event.ON_START -> {
                        registerUpdates()
                    }

                    Lifecycle.Event.ON_STOP -> {
                        unregisterUpdates()
                    }

                    else -> Unit
                }
            }
        )
    }

    private fun registerUpdates() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(WalkTrackingService.ACTION_UPDATE)
            addAction(WalkTrackingService.ACTION_WALK_FINISHED)
        }

        ContextCompat.registerReceiver(
            carContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered = true
    }

    private fun unregisterUpdates() {
        if (!receiverRegistered) return

        try {
            carContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }

        receiverRegistered = false
    }


    override fun onGetTemplate(): PaneTemplate {

        val averageSpeed = if (durationSeconds > 0) {
            distanceKm / (durationSeconds / 3600.0)
        } else {
            0.0
        }

        val firstRow = Row.Builder()
            .setTitle("Distance / hastighed")
            .addText(
                String.format(
                    Locale.getDefault(),
                    "%.1f km     %.0f km/t",
                    distanceKm,
                    speedKmh
                )
            )
            .build()

        val secondRow = Row.Builder()
            .setTitle("Køretid / gennemsnit")
            .addText(
                "${formatDuration(durationSeconds)}     ${
                    String.format(
                        Locale.getDefault(),
                        "%.0f km/t",
                        averageSpeed
                    )
                }"
            )
            .build()

        val controlAction = if (isTracking) {
            Action.Builder()
                .setTitle("Stop biltur")
                .setOnClickListener {
                    stopTrip()
                }
                .build()
        } else {
            Action.Builder()
                .setTitle("Start biltur")
                .setOnClickListener {
                    startTrip()
                }
                .build()
        }

        val pane = Pane.Builder()
            .addRow(firstRow)
            .addRow(secondRow)
            .addAction(controlAction)
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(
                Header.Builder()
                    .setTitle("Bilture")
                    .build()
            )
            .build()
    }



    private fun startTrip() {

        val finePermission = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val coarsePermission = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (
            finePermission != PackageManager.PERMISSION_GRANTED &&
            coarsePermission != PackageManager.PERMISSION_GRANTED
        ) {
            statusMessage = "Giv lokationstilladelse på telefonen"
            invalidate()
            return
        }

        val intent = Intent(
            carContext,
            WalkTrackingService::class.java
        ).apply {
            action = WalkTrackingService.ACTION_START
        }

        try {
            ContextCompat.startForegroundService(carContext, intent)

            isTracking = true
            statusMessage = "Starter biltur"

        } catch (_: Exception) {
            statusMessage = "Kunne ikke starte bilturen"
        }

        invalidate()
    }

    private fun stopTrip() {

        val intent = Intent(
            carContext,
            WalkTrackingService::class.java
        ).apply {
            action = WalkTrackingService.ACTION_STOP
        }

        try {
            carContext.startService(intent)
            statusMessage = "Gemmer biltur"

        } catch (_: Exception) {
            statusMessage = "Kunne ikke stoppe bilturen"
        }

        invalidate()
    }

    private fun formatDuration(seconds: Long): String {

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60

        return String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            hours,
            minutes,
            remainingSeconds
        )
    }
}
