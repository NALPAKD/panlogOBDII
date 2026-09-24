package com.nalpakd.obdscanner.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.nalpakd.obdscanner.core.ElmTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth (SPP / RFCOMM) link to a Panlong / ELM327 adapter.
 * The adapter must be paired first in Android Bluetooth settings (PIN is usually 1234 or 0000).
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice
) : ElmTransport {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    /** Opens the RFCOMM socket, trying the secure, insecure and channel-1 fallbacks many cheap clones need. */
    @Throws(IOException::class)
    fun connect() {
        try { adapter.cancelDiscovery() } catch (_: Exception) { }
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            }
        )
        var last: Exception? = null
        for (make in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                return
            } catch (e: Exception) {
                last = e
                try { s?.close() } catch (_: Exception) { }
                Thread.sleep(300)
            }
        }
        throw IOException("Could not connect to ${device.name ?: device.address}: ${last?.message}", last)
    }

    @Synchronized
    override fun send(cmd: String, timeoutMs: Long): String {
        val inp = input ?: throw IOException("Not connected")
        val out = output ?: throw IOException("Not connected")
        try {
            // Drop anything left over from a previous command
            while (inp.available() > 0) inp.read()
            out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val b = inp.read()
                    if (b < 0) throw IOException("Connection closed")
                    val c = b.toChar()
                    if (c == '>') return sb.toString()
                    if (b != 0) sb.append(c)
                } else {
                    Thread.sleep(8)
                }
            }
            return sb.toString() // timed out: return what we have (often empty -> treated as no data)
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) { }
        try { output?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        socket = null; input = null; output = null
    }
}
