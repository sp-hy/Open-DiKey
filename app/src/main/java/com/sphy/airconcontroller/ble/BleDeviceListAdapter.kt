package com.sphy.airconcontroller.ble

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.sphy.airconcontroller.R

class BleDeviceListAdapter(
    context: Context
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val items = ArrayList<ScannedBleDevice>()
    var selectedAddress: String? = null
        private set

    fun submit(devices: List<ScannedBleDevice>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    fun select(address: String?) {
        selectedAddress = address
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): ScannedBleDevice = items[position]

    override fun getItemId(position: Int): Long = items[position].address.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_ble_device, parent, false)
        val device = items[position]
        val nameView = view.findViewById<TextView>(R.id.bleDeviceName)
        val addressView = view.findViewById<TextView>(R.id.bleDeviceAddress)
        val metaView = view.findViewById<TextView>(R.id.bleDeviceMeta)
        val rssiView = view.findViewById<TextView>(R.id.bleDeviceRssi)

        nameView.text = device.displayName
        addressView.text = device.address
        val transport = when (device.transport) {
            BtTransport.LE -> view.context.getString(R.string.ble_transport_le)
            BtTransport.CLASSIC -> view.context.getString(R.string.ble_transport_classic)
            BtTransport.BOTH -> view.context.getString(R.string.ble_transport_both)
        }
        val connectable = if (device.connectable) {
            view.context.getString(R.string.ble_device_connectable)
        } else {
            view.context.getString(R.string.ble_device_non_connectable)
        }
        metaView.text = view.context.getString(
            R.string.ble_device_meta,
            transport,
            connectable,
            device.serviceUuids.size
        )
        rssiView.text = view.context.getString(R.string.ble_device_rssi, device.rssi)
        view.isActivated = device.address == selectedAddress
        return view
    }
}
