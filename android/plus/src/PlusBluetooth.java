package com.yinyangeq.qudelixprobe;

import android.bluetooth.*;
import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;

/** Each attempt authenticates the model with a read-only exchange before handing
 * the session to the editor. A write failure never triggers another transport. */
final class PlusBluetooth {
    private static UUID gaia(int id) {
        return UUID.fromString("0000" + Integer.toHexString(id) + "-d102-11e1-9b23-00025b00a5a5");
    }

    static int transportType(PlusUsbProtocol.Transport link) {
        if(link instanceof Serial)return BluetoothDevice.TRANSPORT_BREDR;
        if(link instanceof Ble)return BluetoothDevice.TRANSPORT_LE;
        return BluetoothDevice.TRANSPORT_AUTO;
    }

    static PlusUsbProtocol.Status run(Context context, BluetoothDevice device, PlusUsbConnection.Operation operation) throws IOException {
        StringBuilder failures = new StringBuilder();
        for (int link = 0; link < 2; link++) {
            for (int vendor : new int[]{PlusGaia.V3, PlusGaia.LEGACY}) {
                Link connection = link == 0 ? new Serial(device, vendor) : new Ble(context, device, vendor);
                try (Link active = connection) {
                    PlusUsbProtocol.Status status;
                    try {
                        active.open();
                        status = PlusUsbProtocol.status(active);
                    } catch (IOException failure) {
                        if (Thread.currentThread().isInterrupted()) throw failure;
                        failures.append(failure.getMessage()).append(" ");
                        continue;
                    }
                    return operation.run(active, status);
                }
            }
        }
        throw new IOException("No Plus control session. Pair the Plus in Android and close other controllers. " + failures.toString().trim());
    }

    private interface Link extends Closeable, PlusUsbProtocol.Transport {
        void open() throws IOException;
    }

    private static final class Serial implements Link {
        private final BluetoothDevice device;
        private final int vendor;
        private BluetoothSocket socket;
        private DataInputStream input;
        private OutputStream output;
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

        Serial(BluetoothDevice device, int vendor) {
            this.device = device;
            this.vendor = vendor;
        }
        public void open() throws IOException {
            socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
            ScheduledFuture<?> limit = deadline(5500);
            try {
                socket.connect();
                input = new DataInputStream(socket.getInputStream());
                output = socket.getOutputStream();
            } finally { limit.cancel(false); }
        }
        private ScheduledFuture<?> deadline(int ms) {
            return timer.schedule(() -> { try { socket.close(); } catch (IOException ignored) {} }, ms, TimeUnit.MILLISECONDS);
        }
        public void send(byte[] report) throws IOException {
            byte[] pdu = PlusGaia.encode(report, vendor);
            byte[] frame = ByteBuffer.allocate(pdu.length + 4).put((byte) 255).put((byte) 1)
                .put((byte) 0).put((byte) (pdu.length - 4)).put(pdu).array();
            ScheduledFuture<?> limit = deadline(2000);
            try { output.write(frame); output.flush(); } finally { limit.cancel(false); }
        }
        public byte[] receive(int ms) throws IOException {
            ScheduledFuture<?> limit = deadline(ms);
            try {
                while (true) {
                    if (input.readUnsignedByte() != 255) throw new IOException("Invalid Bluetooth serial header.");
                    int version = input.readUnsignedByte(), flags = input.readUnsignedByte();
                    if (version < 1 || version > 4 || flags != 0) throw new IOException("Unsupported Bluetooth serial frame.");
                    byte[] pdu = new byte[4 + input.readUnsignedByte()];
                    input.readFully(pdu);
                    byte[] frame = PlusGaia.decode(pdu, vendor);
                    if (frame != null) return frame;
                }
            } finally { limit.cancel(false); }
        }
        public void close() throws IOException {
            timer.shutdownNow();
            if (socket != null) socket.close();
        }
    }

    private static final class Ble extends BluetoothGattCallback implements Link {
        private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
        private final Context context;
        private final BluetoothDevice device;
        private final int vendor;
        private final BlockingQueue<String> events = new LinkedBlockingQueue<>(8);
        private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>(128);
        private final byte[] closed = {};
        private BluetoothGatt gatt;
        private BluetoothGattCharacteristic tx, rx;
        private volatile int mtu = 23;

        Ble(Context context, BluetoothDevice device, int vendor) {
            this.context = context;
            this.device = device;
            this.vendor = vendor;
        }
        public void open() throws IOException {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IOException("Android could not open Bluetooth LE.");
            expect("connected");
            if (!gatt.discoverServices()) throw new IOException("Bluetooth service discovery failed.");
            expect("services");
            BluetoothGattService service = gatt.getService(gaia(0x1100));
            if (service == null) throw new IOException("No GAIA Bluetooth LE service.");
            tx = service.getCharacteristic(gaia(0x1101));
            rx = service.getCharacteristic(gaia(0x1102));
            if (tx == null || rx == null || !gatt.setCharacteristicNotification(rx, true))
                throw new IOException("The Plus Bluetooth control endpoints are unavailable.");
            if (!gatt.requestMtu(185)) throw new IOException("Bluetooth LE MTU negotiation failed.");
            expect("mtu");
            BluetoothGattDescriptor cccd = rx.getDescriptor(CCCD);
            if (cccd == null) throw new IOException("Bluetooth control notifications are unavailable.");
            boolean notify = (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
            cccd.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            if (!gatt.writeDescriptor(cccd)) throw new IOException("Could not subscribe to the 5K Plus.");
            expect("subscribed");
        }
        public void send(byte[] report) throws IOException {
            byte[] pdu = PlusGaia.encode(report, vendor);
            if (pdu.length > mtu - 3) throw new IOException("Bluetooth LE MTU is too small for this EQ transfer.");
            tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            tx.setValue(pdu);
            if (!gatt.writeCharacteristic(tx)) throw new IOException("Bluetooth control write could not start.");
            expect("sent");
        }
        public byte[] receive(int ms) throws IOException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
            try {
                while (true) {
                    byte[] pdu = incoming.poll(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (pdu == null || pdu == closed) throw new IOException("Bluetooth LE did not return the requested data.");
                    byte[] frame = PlusGaia.decode(pdu, vendor);
                    if (frame != null) return frame;
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Bluetooth operation interrupted.", e); }
        }
        private void expect(String event) throws IOException {
            try {
                if (!event.equals(events.poll(5, TimeUnit.SECONDS))) throw new IOException("Bluetooth LE could not complete " + event + ".");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Bluetooth operation interrupted.", e); }
        }
        public void onConnectionStateChange(BluetoothGatt g, int s, int state) {
            if (s == 0 && state == BluetoothProfile.STATE_CONNECTED) events.offer("connected");
            else if (s != 0 || state == BluetoothProfile.STATE_DISCONNECTED) {
                events.offer("disconnected"); incoming.offer(closed);
            }
        }
        public void onServicesDiscovered(BluetoothGatt g, int s) { events.offer(s == 0 ? "services" : "error"); }
        public void onMtuChanged(BluetoothGatt g, int size, int s) { mtu = size; events.offer(s == 0 ? "mtu" : "error"); }
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int s) { events.offer(s == 0 ? "subscribed" : "error"); }
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int s) { events.offer(s == 0 ? "sent" : "error"); }
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onCharacteristicChanged(g, c, c.getValue());
        }
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (c == rx && value != null) incoming.offer(Arrays.copyOf(value, value.length));
        }
        public void close() {
            if (gatt != null) { gatt.disconnect(); gatt.close(); }
        }
    }
}
