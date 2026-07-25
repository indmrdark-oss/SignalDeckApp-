package com.signaldeck.scope

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class UsbSerialManager(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var isCh340 = false

    companion object {
        private const val USB_CLASS_CDC_DATA = 0x0A
        private const val USB_CLASS_COMM = 0x02
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
    }

    fun open(baudRate: Int): Boolean {
        val conn = usbManager.openDevice(device) ?: return false
        connection = conn

        var commInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                USB_CLASS_CDC_DATA -> dataInterface = intf
                USB_CLASS_COMM -> commInterface = intf
            }
        }

        if (dataInterface == null) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var hasIn = false; var hasOut = false
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                        else hasOut = true
                    }
                }
                if (hasIn && hasOut) {
                    dataInterface = intf
                    isCh340 = true
                    break
                }
            }
        }

        val dIntf = dataInterface ?: return false
        conn.claimInterface(dIntf, true)
        if (commInterface != null) conn.claimInterface(commInterface, true)

        for (i in 0 until dIntf.endpointCount) {
            val ep = dIntf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                else epOut = ep
            }
        }
        if (epIn == null || epOut == null) return false

        if (isCh340) {
            configureCh340Baud(conn, baudRate)
        } else {
            val lineCoding = byteArrayOf(
                (baudRate and 0xFF).toByte(),
                ((baudRate shr 8) and 0xFF).toByte(),
                ((baudRate shr 16) and 0xFF).toByte(),
                ((baudRate shr 24) and 0xFF).toByte(),
                0x00,
                0x00,
                0x08
            )
            conn.controlTransfer(
                0x21, SET_LINE_CODING, 0, commInterface?.id ?: dIntf.id, lineCoding, lineCoding.size, 1000
            )
            conn.controlTransfer(
                0x21, SET_CONTROL_LINE_STATE, 0x03, commInterface?.id ?: dIntf.id, null, 0, 1000
            )
        }
        return true
    }

    private fun configureCh340Baud(conn: UsbDeviceConnection, baudRate: Int) {
        val ch340BaudBase = 1532620800
        val divisor = ch340BaudBase / baudRate
        conn.controlTransfer(0x40, 0xA1, 0, 0, null, 0, 1000)
        conn.controlTransfer(0x40, 0x9A, 0x1312, 0xD980, null, 0, 1000)
        val byteVal = ((divisor and 0xFF) shl 8) or 0x80
        conn.controlTransfer(0x40, 0x9A, 0x1312, byteVal, null, 0, 1000)
        conn.controlTransfer(0x40, 0xA4, 0x0000, 0, null, 0, 1000)
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val ep = epIn ?: return -1
        val conn = connection ?: return -1
        return conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
    }

    fun writeLine(text: String) {
        val ep = epOut ?: return
        val conn = connection ?: return
        val bytes = (text + "\n").toByteArray(Charsets.US_ASCII)
        conn.bulkTransfer(ep, bytes, bytes.size, 1000)
    }

    fun close() {
        try {
            dataInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) {
        }
    }

    fun debugInInfo(): String = "addr=${epIn?.address} type=${epIn?.type} isCh340=$isCh340"
    fun debugOutInfo(): String = "addr=${epOut?.address} type=${epOut?.type}"
}
