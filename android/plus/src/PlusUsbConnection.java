package com.yinyangeq.qudelixprobe;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

final class PlusUsbConnection {
    private PlusUsbConnection() {}

    interface Operation {
        PlusUsbProtocol.Status run(PlusUsbProtocol.Transport link, PlusUsbProtocol.Status initial) throws IOException;
    }

    static PlusUsbProtocol.Status run(UsbManager manager, UsbDevice device, Operation operation) throws IOException {
        if (!PlusUsbProtocol.known(device.getVendorId(), device.getProductId()))
            throw new IOException("Choose a Qudelix 5K Plus USB device.");
        UsbInterface hid = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_HID && candidate.getId() == 0) hid = candidate;
        }
        if (hid == null) throw new IOException("The device does not expose the expected USB control interface.");
        UsbEndpoint in = null, out = null;
        for (int i = 0; i < hid.getEndpointCount(); i++) {
            UsbEndpoint endpoint = hid.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
            if (endpoint.getAddress() == 0x81) in = endpoint;
            if (endpoint.getAddress() == 0x01) out = endpoint;
        }
        if (in == null || out == null) throw new IOException("The USB control endpoints did not match the 5K Plus.");
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Android could not open the 5K Plus.");
        try {
            if (!connection.claimInterface(hid, true))
                throw new IOException("Android could not claim the USB control interface. Close other Qudelix apps.");
            try {
                PlusUsbProtocol.Transport link = new InterruptTransport(connection, in, out);
                return operation.run(link, PlusUsbProtocol.status(link));
            } finally {
                connection.releaseInterface(hid);
            }
        } finally {
            connection.close();
        }
    }

    private static final class InterruptTransport implements PlusUsbProtocol.Transport {
        private final UsbDeviceConnection connection;
        private final UsbEndpoint in, out;

        InterruptTransport(UsbDeviceConnection connection, UsbEndpoint in, UsbEndpoint out) {
            this.connection = connection;
            this.in = in;
            this.out = out;
        }

        @Override public void send(byte[] report) throws IOException {
            if (transfer(out, report, 1500) != report.length) throw new IOException("The USB request was incomplete.");
        }

        @Override public byte[] receive(int timeoutMillis) throws IOException {
            byte[] report = new byte[PlusUsbProtocol.REPORT_SIZE];
            return Arrays.copyOf(report, transfer(in, report, timeoutMillis));
        }

        private int transfer(UsbEndpoint endpoint, byte[] bytes, int timeoutMillis) throws IOException {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            UsbRequest request = new UsbRequest();
            try {
                if (!request.initialize(connection, endpoint) || !request.queue(data))
                    throw new IOException("Android could not queue the USB transfer.");
                try {
                    if (connection.requestWait(timeoutMillis) != request)
                        throw new IOException("The USB device disconnected.");
                    return data.position();
                } catch (TimeoutException e) {
                    request.cancel();
                    throw new IOException("The 5K Plus did not answer. Check the USB cable and close the official app.", e);
                }
            } finally {
                request.close();
            }
        }
    }
}
