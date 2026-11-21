package com.yinyangeq.qudelixprobe;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Private recovery data. Its device address and preset bytes stay out of the debug log. */
final class EqTestJournal {
    static final String FILE_NAME = "eq-test-recovery.bin";
    private static final int FORMAT = 0x59594531;
    private final File file;

    EqTestJournal(File directory) {
        file = new File(directory, FILE_NAME);
    }

    boolean exists() {
        return file.exists();
    }

    Entry read() throws IOException {
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != FORMAT) throw new IOException("Unsupported EQ recovery format.");
            String address = input.readUTF();
            boolean cutVerified = input.readBoolean();
            int model = input.readUnsignedShort();
            byte[] field = readBytes(input, 4);
            byte[] configuration = readBytes(input, 19);
            byte[] usr = readBytes(input, EqSnapshot.Group.USR.size);
            byte[] spk = readBytes(input, EqSnapshot.Group.SPK.size);
            if (!address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") || input.read() != -1) {
                throw new IOException("Invalid EQ recovery data.");
            }
            return new Entry(address, new EqSnapshot(new GaiaProbe.Device(model, field), configuration, usr, spk), cutVerified);
        }
    }

    private static byte[] readBytes(DataInputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return bytes;
    }

    void save(Entry entry) throws IOException {
        Path temporary = Files.createTempFile(file.getParentFile().toPath(), "eq-recovery-", ".tmp");
        try {
            try (FileOutputStream disk = new FileOutputStream(temporary.toFile());
                    DataOutputStream output = new DataOutputStream(disk)) {
                output.writeInt(FORMAT);
                output.writeUTF(entry.address);
                output.writeBoolean(entry.cutVerified);
                output.writeShort(entry.original.device.model);
                output.write(entry.original.device.firmwareField);
                output.write(entry.original.configuration);
                output.write(entry.original.usr);
                output.write(entry.original.spk);
                output.flush();
                disk.getFD().sync();
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void clear() throws IOException {
        Files.delete(file.toPath());
    }

    static final class Entry {
        final String address;
        final EqSnapshot original;
        final boolean cutVerified;

        Entry(String address, EqSnapshot original, boolean cutVerified) {
            this.address = address;
            this.original = original;
            this.cutVerified = cutVerified;
        }

        Entry withVerifiedCut() {
            return new Entry(address, original, true);
        }
    }
}
