package com.airdrum.logger

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.airdrum.logger.databinding.ActivityMainBinding
import java.io.File
import java.io.FileWriter
import kotlin.math.sqrt

/**
 * Instrumentation-only app: logs raw accelerometer (linear accel if available,
 * else raw accelerometer), rotation-vector-derived orientation, and raw
 * magnetometer to a CSV, tagged per recorded session by a free-text label.
 * No audio, no calibration step (deferred to after log analysis).
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager

    private var accelSensor: Sensor? = null
    private var usingLinearAccel = true
    private var rotationSensor: Sensor? = null
    private var magSensor: Sensor? = null

    // Latest values from each sensor; we write a row anchored on accel events,
    // stamping in the most recent orientation/magnetometer reading available.
    private var lastPitch = 0f
    private var lastRoll = 0f
    private var lastAzimuth = 0f
    private var lastMagX = 0f
    private var lastMagY = 0f
    private var lastMagZ = 0f
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    private var isRecording = false
    private var currentLabel = ""
    private var sessionRows = 0

    // All rows across all sessions this app-run, kept in memory until shared.
    private val allRows = StringBuilder()
    private var totalRows = 0
    private var totalSessions = 0

    private val csvHeader =
        "timestamp_ms,etiqueta,accel_x,accel_y,accel_z,accel_mag,pitch,roll,azimuth,mag_x,mag_y,mag_z\n"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (accelSensor == null) {
            usingLinearAccel = false
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val statusParts = mutableListOf<String>()
        statusParts.add(if (accelSensor != null) "Accel(${if (usingLinearAccel) "lineal" else "crudo"}): OK" else "Accel: AUSENTE")
        statusParts.add(if (rotationSensor != null) "RotationVector: OK" else "RotationVector: AUSENTE")
        statusParts.add(if (magSensor != null) "Magnetometro: OK" else "Magnetometro: AUSENTE")
        binding.txtStatus.text = statusParts.joinToString("  |  ")

        binding.btnRecord.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        binding.btnShare.setOnClickListener { shareCsv() }

        binding.btnClear.setOnClickListener {
            allRows.clear()
            totalRows = 0
            totalSessions = 0
            updateTotalsText()
        }

        updateTotalsText()
    }

    private fun startRecording() {
        val label = binding.editLabel.text.toString().trim()
        if (label.isEmpty()) {
            binding.editLabel.error = "Poné una etiqueta antes de grabar"
            return
        }
        currentLabel = label.replace(",", ";") // keep CSV safe
        sessionRows = 0
        isRecording = true
        binding.editLabel.isEnabled = false
        binding.btnRecord.text = "STOP"

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun stopRecording() {
        isRecording = false
        sensorManager.unregisterListener(this)
        binding.editLabel.isEnabled = true
        binding.btnRecord.text = "RECORD"
        totalSessions += 1
        updateTotalsText()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                // orientationValues: [azimuth, pitch, roll] in radians
                lastAzimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                lastPitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                lastRoll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lastMagX = event.values[0]
                lastMagY = event.values[1]
                lastMagZ = event.values[2]
            }
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                val ts = SystemClock.elapsedRealtimeNanos() / 1_000_000L // ms, monotonic

                allRows.append(ts).append(',')
                    .append(currentLabel).append(',')
                    .append(x).append(',')
                    .append(y).append(',')
                    .append(z).append(',')
                    .append(mag).append(',')
                    .append(lastPitch).append(',')
                    .append(lastRoll).append(',')
                    .append(lastAzimuth).append(',')
                    .append(lastMagX).append(',')
                    .append(lastMagY).append(',')
                    .append(lastMagZ).append('\n')

                sessionRows++
                totalRows++
                binding.txtCount.text = "Muestras en sesión actual: $sessionRows"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // not used
    }

    private fun updateTotalsText() {
        binding.txtTotal.text = "Total acumulado: $totalRows filas en $totalSessions sesiones"
    }

    private fun shareCsv() {
        if (totalRows == 0) return
        val logsDir = File(cacheDir, "logs").apply { mkdirs() }
        val file = File(logsDir, "airdrum_sensor_log.csv")
        FileWriter(file, false).use { writer ->
            writer.write(csvHeader)
            writer.write(allRows.toString())
        }

        val uri = FileProvider.getUriForFile(this, "com.airdrum.logger.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir CSV"))
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) stopRecording()
    }
}
