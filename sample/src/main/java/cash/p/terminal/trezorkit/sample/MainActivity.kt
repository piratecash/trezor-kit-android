package cash.p.terminal.trezorkit.sample

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cash.p.terminal.trezorkit.TrezorException
import cash.p.terminal.trezorkit.client.TrezorFeatures
import cash.p.terminal.trezorkit.client.UsbTrezorClient
import cash.p.terminal.trezorkit.protocol.MessageTypeRegistry
import cash.p.terminal.trezorkit.protocol.TrezorSession
import cash.p.terminal.trezorkit.transport.UsbTrezorTransport
import kotlinx.coroutines.launch

/**
 * Manual on-device harness for exercising [UsbTrezorClient.connect] `getFeatures()` against a
 * real Trezor over USB. Not covered by unit tests - USB Host behavior can only be verified on
 * a physical device.
 */
class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private var permissionReceiver: BroadcastReceiver? = null
    private var status by mutableStateOf("")
    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setContent {
            MaterialTheme {
                SampleScreen(status = status, isLoading = isLoading, onConnect = ::onConnectClicked)
            }
        }
    }

    override fun onDestroy() {
        unregisterPermissionReceiver()
        super.onDestroy()
    }

    private fun onConnectClicked() {
        if (isLoading) return
        val device = findTrezorDevice()
        when {
            device == null -> status = "Connect Trezor via cable"
            usbManager.hasPermission(device) -> connectAndGetFeatures(device)
            else -> requestUsbPermission(device)
        }
    }

    private fun findTrezorDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        it.vendorId == UsbTrezorTransport.VENDOR_ID && it.productId == UsbTrezorTransport.PRODUCT_ID_FIRMWARE
    }

    private fun requestUsbPermission(device: UsbDevice) {
        isLoading = true
        status = "Requesting USB permission…"
        unregisterPermissionReceiver()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                unregisterPermissionReceiver()
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectAndGetFeatures(device)
                } else {
                    isLoading = false
                    status = "USB permission was denied"
                }
            }
        }
        permissionReceiver = receiver

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Must be explicit (package set) - Android U+ rejects a mutable PendingIntent
        // wrapping an implicit intent.
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun unregisterPermissionReceiver() {
        permissionReceiver?.let(::unregisterReceiver)
        permissionReceiver = null
    }

    private fun connectAndGetFeatures(device: UsbDevice) {
        isLoading = true
        status = "Connecting…"
        val client = UsbTrezorClient(TrezorSession(UsbTrezorTransport(usbManager, device), MessageTypeRegistry))

        lifecycleScope.launch {
            status = try {
                client.connect { getFeatures() }.toDisplayString()
            } catch (e: TrezorException) {
                "Error: ${e.message}"
            }
            isLoading = false
        }
    }

    private fun TrezorFeatures.toDisplayString(): String = """
        deviceId: $deviceId
        model: $model
        internalModel: $internalModel
        firmwareVersion: $firmwareVersion
        passphraseProtection: $passphraseProtection
    """.trimIndent()

    companion object {
        private const val ACTION_USB_PERMISSION = "cash.p.terminal.trezorkit.sample.USB_PERMISSION"
    }
}
