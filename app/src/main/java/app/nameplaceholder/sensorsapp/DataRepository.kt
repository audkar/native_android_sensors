package app.nameplaceholder.sensorsapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.broadcastIn
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample

@UseExperimental(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DataRepository constructor(
    private val context: Context
) {

    private var trackingChannel: BroadcastChannel<List<SensorData>>? = null
        get() {
            if (field == null) {
                field = createTrackingChannel()
            }
            return field
        }

    private fun createTrackingChannel(): BroadcastChannel<List<SensorData>> {
        val valuesInMemoryCache = mutableListOf<SensorData>()

        return flow {
            emit(provideBluetoothData().sample(1000))
            emit(provideLightSensor().sample(1000))
            emit(provideLocation())
        }
            .flattenMerge()
            .map { valuesInMemoryCache.add(it); valuesInMemoryCache.toList() }
            .broadcastIn(scope = GlobalScope)
    }

    fun track(): Flow<List<SensorData>> = trackingChannel!!.asFlow()

    fun stop() {
        trackingChannel?.close()
        trackingChannel?.cancel()
        trackingChannel = null
    }

    private fun provideBluetoothData(): Flow<BluetoothData> =
        if (context.getSystemService<BluetoothManager>()!!.adapter == null) {
            provideBluetoothFakeData()
        } else {
            provideBluetoothRealData()
        }

    private fun provideBluetoothRealData(): Flow<BluetoothData> = channelFlow {
        val scanner = context.getSystemService<BluetoothManager>()!!.adapter.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                offer(BluetoothData(result.device.name ?: result.device.address))
            }
        }
        scanner.startScan(callback)
        awaitClose {
            scanner.stopScan(callback)
        }
    }

    private fun provideBluetoothFakeData(): Flow<BluetoothData> = flow {
        var i = 0
        while (true) {
            emit(BluetoothData(i++.toString()))
            delay(1000)
        }
    }

    private fun provideLightSensor() = channelFlow {
        val sensorManager = context.getSystemService<SensorManager>()!!
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            }

            override fun onSensorChanged(event: SensorEvent) {
                if (!isClosedForSend) {
                    offer(LightData(event.values.first()))
                }
            }
        }
        val registerResult = sensorManager.registerListener(
            listener,
            lightSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        Log.i(TAG, "Light sensor register success: $registerResult")
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun provideLocation() = channelFlow {
        val locationManager = context.getSystemService<LocationManager>()!!
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                offer(LocationData(location.latitude))
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String?) {
            }

            override fun onProviderDisabled(provider: String?) {
            }
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0F,
            listener,
            Looper.getMainLooper()
        )
        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    companion object {
        private const val TAG = "DataRepository"
    }
}
