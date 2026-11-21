package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;

/** Synthetic state. This class has no access to the Bluetooth transport. */
final class Simulation {
    static final byte[] REPLY = {(byte) 0xff, 1, 0, 8, (byte) 0xf0, 1, (byte) 0x81, 1, 0, 0, 1, 3, 1, 2, 3, 4};

    private Simulation() {}

    static String run() throws IOException {
        GaiaProbe.readInitialStateReply(new ByteArrayInputStream(REPLY), message -> {});
        Device device = new Device();
        Preset previous = device.read();
        Preset applied = exercise(device, () -> {});
        return "Simulation passed. Synthetic device-state reply verified."
                + "\nSaved preset · " + previous.describe()
                + "\nRead back · " + applied.describe()
                + "\nRestored and verified · " + device.read().describe();
    }

    static String runInterrupted() throws IOException {
        Device device = new Device();
        Preset previous = device.read();
        try {
            exercise(device, device::disconnect);
            throw new IOException("The simulated disconnection was not detected.");
        } catch (LinkLostException expected) {
            device.reconnect();
        }
        Preset afterReconnect = device.read();
        restore(device, previous);
        return "Recovery simulation passed. Link dropped before verification."
                + "\nSaved preset · " + previous.describe()
                + "\nRead after reconnect · " + afterReconnect.describe()
                + "\nRestored and verified · " + device.read().describe();
    }

    static Preset exercise(Device device, Runnable afterWrite) throws IOException {
        Preset previous = device.read();
        Preset changed = new Preset(previous.hz, previous.gainTenths - 10, previous.qHundredths, previous.preampTenths);
        Preset readBack;
        try {
            device.write(changed);
            afterWrite.run();
            readBack = device.read();
            if (!changed.equals(readBack)) throw new IOException("Simulated PEQ read-back differs.");
        } catch (IOException | RuntimeException failure) {
            try {
                restore(device, previous);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
        restore(device, previous);
        return readBack;
    }

    static void restore(Device device, Preset previous) throws IOException {
        device.write(previous);
        if (!previous.equals(device.read())) throw new IOException("Simulated preset restoration differs.");
    }

    static final class Device {
        private Preset preset = new Preset(1000, 0, 100, -30);
        private boolean connected = true;

        Preset read() throws IOException {
            if (!connected) throw new LinkLostException();
            return preset;
        }

        void write(Preset newValue) throws IOException {
            read();
            preset = newValue;
        }

        void disconnect() { connected = false; }

        void reconnect() { connected = true; }
    }

    private static final class LinkLostException extends IOException {
        LinkLostException() { super("Simulated Bluetooth connection lost."); }
    }

    static final class Preset {
        private final int hz;
        private final int gainTenths;
        private final int qHundredths;
        private final int preampTenths;

        Preset(int hz, int gainTenths, int qHundredths, int preampTenths) {
            this.hz = hz;
            this.gainTenths = gainTenths;
            this.qHundredths = qHundredths;
            this.preampTenths = preampTenths;
        }

        String describe() {
            return String.format(Locale.US, "Peak %,d Hz, %+.1f dB, Q %.2f, preamp %+.1f dB",
                    hz, gainTenths / 10.0, qHundredths / 100.0, preampTenths / 10.0);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Preset)) return false;
            Preset preset = (Preset) other;
            return hz == preset.hz && gainTenths == preset.gainTenths
                    && qHundredths == preset.qHundredths && preampTenths == preset.preampTenths;
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(hz, gainTenths, qHundredths, preampTenths);
        }
    }
}
