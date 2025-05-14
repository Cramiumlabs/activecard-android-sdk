package com.cramium.activecard.ble

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

class BondStateReceiver(
    private val callback: (device: BluetoothDevice, previousState: Int, newState: Int) -> Unit
): BroadcastReceiver() {

    companion object {
        private val TAG = BondStateReceiver::class.java.simpleName

        fun registerReceiver(context: Context, receiver: BondStateReceiver?) {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(receiver, filter)
        }

        fun unregisterReceiver(context: Context, receiver: BondStateReceiver?) {
            context.unregisterReceiver(receiver)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == action) {
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            callback(device, previousState, state)
        }
    }
}