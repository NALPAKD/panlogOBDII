package com.nalpakd.obdscanner.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.nalpakd.obdscanner.core.AdapterInfo
import com.nalpakd.obdscanner.core.DemoTransport
import com.nalpakd.obdscanner.core.ElmTransport
import com.nalpakd.obdscanner.core.ObdScanner

/** App-wide connection state shared by all screens. Blocking methods: call off the main thread. */
object ObdSession {
    @Volatile var transport: ElmTransport? = null
        private set
    @Volatile var scanner: ObdScanner? = null
        private set
    @Volatile var deviceName: String = ""
        private set
    @Volatile var isDemo: Boolean = false
        private set

    val connected: Boolean get() = transport?.isConnected == true
    val adapterInfo: AdapterInfo? get() = scanner?.adapterInfo

    fun connectBluetooth(adapter: BluetoothAdapter, device: BluetoothDevice, name: String): AdapterInfo {
        disconnect()
        val t = BluetoothTransport(adapter, device)
        t.connect()
        return attach(t, name, demo = false)
    }

    fun connectDemo(): AdapterInfo {
        disconnect()
        return attach(DemoTransport(), "Demo vehicle (simulated)", demo = true)
    }

    private fun attach(t: ElmTransport, name: String, demo: Boolean): AdapterInfo {
        val s = ObdScanner(t)
        try {
            val info = s.initialize()
            transport = t; scanner = s; deviceName = name; isDemo = demo
            return info
        } catch (e: Exception) {
            t.close()
            throw e
        }
    }

    fun disconnect() {
        try { transport?.close() } catch (_: Exception) { }
        transport = null; scanner = null; deviceName = ""; isDemo = false
    }
}
