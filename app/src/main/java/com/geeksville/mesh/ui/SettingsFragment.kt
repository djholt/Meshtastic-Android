package com.geeksville.mesh.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.*
import android.os.Bundle
import android.os.ParcelUuid
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.RadioButton
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.geeksville.android.GeeksvilleApplication
import com.geeksville.android.Logging
import com.geeksville.android.hideKeyboard
import com.geeksville.mesh.MainActivity
import com.geeksville.mesh.MainActivity.Companion.RC_SELECT_DEVICE
import com.geeksville.mesh.R
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.service.RadioInterfaceService
import com.geeksville.util.exceptionReporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.settings_fragment.*
import java.util.regex.Pattern

object SLogging : Logging {}

/// Change to a new macaddr selection, updating GUI and radio
fun changeDeviceSelection(context: MainActivity, newAddr: String?) {
    RadioInterfaceService.setBondedDeviceAddress(context, newAddr)

    // Super ugly hack.  we force the activity to reconnect FIXME, find a cleaner way
    context.unbindMeshService()
    context.bindMeshService()
}

/// Show the UI asking the user to bond with a device, call changeSelection() if/when bonding completes
private fun requestBonding(activity: MainActivity, device: BluetoothDevice, onSuccess: () -> Unit) {
    SLogging.info("Starting bonding for $device")

    // We need this receiver to get informed when the bond attempt finished
    val bondChangedReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context,
            intent: Intent
        ) = exceptionReporter {
            val state =
                intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    -1
                )
            SLogging.debug("Received bond state changed $state")
            context.unregisterReceiver(this)
            if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_BONDING) {
                SLogging.debug("Bonding completed, connecting service")
                onSuccess()
            }
        }
    }

    val filter = IntentFilter()
    filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    activity.registerReceiver(bondChangedReceiver, filter)

    // We ignore missing BT adapters, because it lets us run on the emulator
    device.createBond()
}


class BTScanModel(app: Application) : AndroidViewModel(app), Logging {

    private val context = getApplication<Application>().applicationContext

    init {
        debug("BTScanModel created")
    }

    data class BTScanEntry(val name: String, val macAddress: String, val bonded: Boolean) {
        // val isSelected get() = macAddress == selectedMacAddr
    }

    override fun onCleared() {
        super.onCleared()
        debug("BTScanModel cleared")
    }

    /// Note: may be null on platforms without a bluetooth driver (ie. the emulator)
    val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    var selectedMacAddr: String? = null
    val errorText = object : MutableLiveData<String?>(null) {}


    private var scanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            val msg = "Unexpected bluetooth scan failure: $errorCode"
            // error code2 seeems to be indicate hung bluetooth stack
            errorText.value = msg
        }

        // For each device that appears in our scan, ask for its GATT, when the gatt arrives,
        // check if it is an eligable device and store it in our list of candidates
        // if that device later disconnects remove it as a candidate
        override fun onScanResult(callbackType: Int, result: ScanResult) {

            val addr = result.device.address
            // prevent logspam because weill get get lots of redundant scan results
            val isBonded = result.device.bondState == BluetoothDevice.BOND_BONDED
            val oldDevs = devices.value!!
            val oldEntry = oldDevs[addr]
            if (oldEntry == null || oldEntry.bonded != isBonded) {
                val entry = BTScanEntry(
                    result.device.name
                        ?: "unnamed-$addr", // autobug: some devices might not have a name, if someone is running really old device code?
                    addr,
                    isBonded
                )
                debug("onScanResult ${entry}")

                // If nothing was selected, by default select the first thing we see
                if (selectedMacAddr == null && entry.bonded)
                    changeScanSelection(GeeksvilleApplication.currentActivity as MainActivity, addr)

                devices.value = oldDevs + Pair(addr, entry) // trigger gui updates
            }
        }
    }

    fun stopScan() {
        if (scanner != null) {
            debug("stopping scan")
            try {
                scanner?.stopScan(scanCallback)
            } catch (ex: Throwable) {
                warn("Ignoring error stopping scan, probably BT adapter was disabled suddenly: ${ex.message}")
            }
            scanner = null
        }
    }

    fun startScan() {
        debug("BTScan component active")
        selectedMacAddr = RadioInterfaceService.getBondedDeviceAddress(context)

        if (bluetoothAdapter == null) {
            warn("No bluetooth adapter.  Running under emulation?")

            val testnodes = listOf(
                BTScanEntry("Meshtastic_ab12", "xx", false),
                BTScanEntry("Meshtastic_32ac", "xb", true)
            )

            devices.value = (testnodes.map { it.macAddress to it }).toMap()

            // If nothing was selected, by default select the first thing we see
            if (selectedMacAddr == null)
                changeScanSelection(
                    GeeksvilleApplication.currentActivity as MainActivity,
                    testnodes.first().macAddress
                )
        } else {
            /// The following call might return null if the user doesn't have bluetooth access permissions
            val s: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

            if (s == null) {
                errorText.value =
                    context.getString(R.string.requires_bluetooth)
            } else {
                debug("starting scan")

                // filter and only accept devices that have a sw update service
                val filter =
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(RadioInterfaceService.BTM_SERVICE_UUID))
                        .build()

                val settings =
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                s.startScan(listOf(filter), settings, scanCallback)
                scanner = s
            }
        }
    }

    val devices = object : MutableLiveData<Map<String, BTScanEntry>>(mapOf()) {


        /**
         * Called when the number of active observers change from 1 to 0.
         *
         *
         * This does not mean that there are no observers left, there may still be observers but their
         * lifecycle states aren't [Lifecycle.State.STARTED] or [Lifecycle.State.RESUMED]
         * (like an Activity in the back stack).
         *
         *
         * You can check if there are observers via [.hasObservers].
         */
        override fun onInactive() {
            super.onInactive()
            stopScan()
        }
    }

    /// Called by the GUI when a new device has been selected by the user
    /// Returns true if we were able to change to that item
    fun onSelected(activity: MainActivity, it: BTScanEntry): Boolean {
        // If the device is paired, let user select it, otherwise start the pairing flow
        if (it.bonded) {
            changeScanSelection(activity, it.macAddress)
            return true
        } else {
            // We ignore missing BT adapters, because it lets us run on the emulator
            bluetoothAdapter
                ?.getRemoteDevice(it.macAddress)?.let { device ->
                    requestBonding(activity, device) {
                        changeScanSelection(
                            activity,
                            device.address
                        )

                        // Force the GUI to redraw
                        devices.value = devices.value
                    }
                }

            return false
        }
    }

    /// Change to a new macaddr selection, updating GUI and radio
    fun changeScanSelection(context: MainActivity, newAddr: String) {
        info("Changing BT device to $newAddr")
        selectedMacAddr = newAddr
        changeDeviceSelection(context, newAddr)
    }
}


@SuppressLint("NewApi")
class SettingsFragment : ScreenFragment("Settings"), Logging {

    private val scanModel: BTScanModel by activityViewModels()
    private val model: UIViewModel by activityViewModels()

    private val hasCompanionDeviceApi: Boolean by lazy {
        RadioInterfaceService.hasCompanionDeviceApi(requireContext())
    }

    private val deviceManager: CompanionDeviceManager by lazy {
        requireContext().getSystemService(CompanionDeviceManager::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.settings_fragment, container, false)
    }

    /// Setup the ui widgets unrelated to BLE scanning
    private fun initCommonUI() {
        model.ownerName.observe(viewLifecycleOwner, Observer { name ->
            usernameEditText.setText(name)
        })

        usernameEditText.on(EditorInfo.IME_ACTION_DONE) {
            debug("did IME action")
            val n = usernameEditText.text.toString().trim()
            if (n.isNotEmpty())
                model.setOwner(n)

            requireActivity().hideKeyboard()
        }

        val app = (requireContext().applicationContext as GeeksvilleApplication)

        // Set analytics checkbox
        analyticsOkayCheckbox.isChecked = app.isAnalyticsAllowed

        analyticsOkayCheckbox.setOnCheckedChangeListener { _, isChecked ->
            debug("User changed analytics to $isChecked")
            app.isAnalyticsAllowed = isChecked
            reportBugButton.isEnabled = app.isAnalyticsAllowed
        }

        // report bug button only enabled if analytics is allowed
        reportBugButton.isEnabled = app.isAnalyticsAllowed
        reportBugButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.report_a_bug)
                .setMessage(getString(R.string.report_bug_text))
                .setNeutralButton(R.string.cancel) { _, _ ->
                    debug("Decided not to report a bug")
                }
                .setPositiveButton(getString(R.string.report)) { _, _ ->
                    reportError("Clicked Report A Bug")
                }
                .show()

            true
        }
    }

    /// Setup the GUI to do a classic (pre SDK 26 BLE scan)
    private fun initClassicScan() {
        // Turn off the widgets for the new API
        scanProgressBar.visibility = View.VISIBLE
        deviceRadioGroup.visibility = View.VISIBLE
        changeRadioButton.visibility = View.GONE

        scanModel.errorText.observe(viewLifecycleOwner, Observer { errMsg ->
            if (errMsg != null) {
                scanStatusText.text = errMsg
            }
        })

        scanModel.devices.observe(viewLifecycleOwner, Observer { devices ->
            // Remove the old radio buttons and repopulate
            deviceRadioGroup.removeAllViews()

            devices.values.forEach { device ->
                val b = RadioButton(requireActivity())
                b.text = device.name
                b.id = View.generateViewId()
                b.isEnabled =
                    true // Now we always want to enable, if the user clicks we'll try to bond device.bonded
                b.isSelected = device.macAddress == scanModel.selectedMacAddr
                deviceRadioGroup.addView(b)

                b.setOnClickListener {
                    scanProgressBar.visibility = View.INVISIBLE
                    if (!device.bonded)
                        scanStatusText.setText(R.string.starting_pairing)

                    b.isSelected = scanModel.onSelected(requireActivity() as MainActivity, device)

                    if (!b.isSelected)
                        scanStatusText.setText(R.string.pairing_failed)
                }
            }

            val hasBonded = RadioInterfaceService.getBondedDeviceAddress(requireContext()) != null

            // get rid of the warning text once at least one device is paired
            warningNotPaired.visibility = if (hasBonded) View.GONE else View.VISIBLE
        })
    }

    /// Start running the modern scan, once it has one result we enable the
    private fun startBackgroundScan() {
        // Disable the change button until our scan has some results
        changeRadioButton.isEnabled = false

        // To skip filtering based on name and supported feature flags (UUIDs),
        // don't include calls to setNamePattern() and addServiceUuid(),
        // respectively. This example uses Bluetooth.
        val deviceFilter: BluetoothDeviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("Meshtastic_.*"))
            // .addServiceUuid(ParcelUuid(RadioInterfaceService.BTM_SERVICE_UUID), null)
            .build()

        // The argument provided in setSingleDevice() determines whether a single
        // device name or a list of device names is presented to the user as
        // pairing options.
        val pairingRequest: AssociationRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        val mainActivity = requireActivity() as MainActivity

        // When the app tries to pair with the Bluetooth device, show the
        // appropriate pairing request dialog to the user.
        deviceManager.associate(
            pairingRequest,
            object : CompanionDeviceManager.Callback() {

                override fun onDeviceFound(chooserLauncher: IntentSender) {
                    debug("Found one device - enabling button")
                    changeRadioButton.isEnabled = true
                    changeRadioButton.setOnClickListener {
                        debug("User clicked BLE change button")
                        startIntentSenderForResult(
                            chooserLauncher,
                            RC_SELECT_DEVICE, null, 0, 0, 0, null
                        )
                    }
                }

                override fun onFailure(error: CharSequence?) {
                    warn("BLE selection service failed $error")
                    // changeDeviceSelection(mainActivity, null) // deselect any device
                }
            }, null
        )
    }

    private fun initModernScan() {
        // Turn off the widgets for the classic API
        scanProgressBar.visibility = View.GONE
        deviceRadioGroup.visibility = View.GONE
        changeRadioButton.visibility = View.VISIBLE

        val curRadio = RadioInterfaceService.getBondedDeviceAddress(requireContext())

        if (curRadio != null) {
            scanStatusText.text = getString(R.string.current_pair).format(curRadio)
            changeRadioButton.text = getString(R.string.change_radio)
        } else {
            scanStatusText.text = getString(R.string.not_paired_yet)
            changeRadioButton.setText(R.string.select_radio)
        }

        startBackgroundScan()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initCommonUI()
        if (hasCompanionDeviceApi)
            initModernScan()
        else
            initClassicScan()
    }

    override fun onPause() {
        super.onPause()
        scanModel.stopScan()
    }

    override fun onResume() {
        super.onResume()
        if (!hasCompanionDeviceApi)
            scanModel.startScan()
    }
}

