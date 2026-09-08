package com.sphy.airconcontroller.usb

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.sphy.airconcontroller.R

class UsbDeviceListAdapter(context: Context) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private val items = mutableListOf<UsbHostSerial.ListedDevice>()

    fun submit(next: List<UsbHostSerial.ListedDevice>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): UsbHostSerial.ListedDevice = items[position]

    override fun getItemId(position: Int): Long = items[position].device.deviceId.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_usb_device, parent, false)
        val item = items[position]
        view.findViewById<TextView>(R.id.usbDeviceTitle).text =
            if (item.likelyProbe) "★ ${item.title}" else item.title
        view.findViewById<TextView>(R.id.usbDeviceSubtitle).text = item.subtitle
        return view
    }
}
