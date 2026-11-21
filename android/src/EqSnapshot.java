package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.ProtocolException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** Read-only capture of the active USR and SPK blocks for the verified original 5K layout. */
final class EqSnapshot {
    enum Group {
        USR(0, 88),
        SPK(1, 128);

        final int id;
        final int size;

        Group(int id, int size) {
            this.id = id;
            this.size = size;
        }
    }

    final GaiaProbe.Device device;
    final byte[] configuration;
    final byte[] usr;
    final byte[] spk;
    private final Instant capturedAt;

    EqSnapshot(GaiaProbe.Device device, byte[] configuration, byte[] usr, byte[] spk) throws ProtocolException {
        checkConfiguration(configuration);
        if (device.model != 1 || usr.length != Group.USR.size || spk.length != Group.SPK.size) {
            throw new ProtocolException("The EQ snapshot does not contain complete original 5K blocks.");
        }
        this.device = device;
        this.configuration = configuration.clone();
        this.usr = usr.clone();
        this.spk = spk.clone();
        capturedAt = Instant.now();
    }

    static EqSnapshot read(GaiaProbe.Connection connection) throws IOException {
        GaiaProbe.Device device = connection.readDevice();
        if (device.model != 1) throw new ProtocolException("EQ capture requires an original Qudelix-5K.");
        byte[] config = connection.readEqConfiguration();
        checkConfiguration(config);
        byte[] usr = readGroup(connection, Group.USR);
        byte[] spk = readGroup(connection, Group.SPK);
        if (!Arrays.equals(config, connection.readEqConfiguration())) {
            throw new ProtocolException("The active EQ configuration changed during the read. Run the capture again.");
        }
        return new EqSnapshot(device, config, usr, spk);
    }

    static void checkConfiguration(byte[] config) throws ProtocolException {
        if (config.length != 19 || config[0] != (byte) 0x80 || config[1] != 0 || config[10] != 1) {
            throw new ProtocolException("EQ capture currently requires the verified USR/SPK configuration layout.");
        }
    }

    static byte[] readGroup(GaiaProbe.Connection connection, Group group) throws IOException {
        connection.requestPreset(group);
        byte[] result = new byte[group.size];
        int offset = 0;
        int lastIndex = -1;
        for (int index = 0; index < 16; index++) {
            byte[] segment = connection.readPresetSegment();
            if (segment.length < 7 || Byte.toUnsignedInt(segment[0]) != group.id) throw invalidGroup(group);
            int position = Byte.toUnsignedInt(segment[1]);
            int reportedLastIndex = position >> 4;
            int reportedOffset = Byte.toUnsignedInt(segment[4]) << 8 | Byte.toUnsignedInt(segment[5]);
            if (index == 0) lastIndex = reportedLastIndex;
            int bytes = segment.length - 6;
            if (reportedLastIndex != lastIndex || (position & 15) != index || index > lastIndex
                    || reportedOffset != offset || offset + bytes > result.length) {
                throw invalidGroup(group);
            }
            System.arraycopy(segment, 6, result, offset, bytes);
            offset += bytes;
            if (index == lastIndex) {
                if (offset != result.length) throw invalidGroup(group);
                return result;
            }
        }
        throw invalidGroup(group);
    }

    private static ProtocolException invalidGroup(Group group) {
        return new ProtocolException("The " + group + " EQ preset segments did not form a complete block.");
    }

    String summary() {
        return "Read active USR and SPK EQ blocks. The configuration matched before and after capture.";
    }

    String toJson() {
        Base64.Encoder base64 = Base64.getEncoder();
        return "{\n"
                + "  \"format\": \"yinyang-eq-qudelix-active-eq-v1\",\n"
                + "  \"capturedAt\": \"" + capturedAt + "\",\n"
                + "  \"deviceModelId\": " + device.model + ",\n"
                + "  \"firmware\": \"" + device.version + "\",\n"
                + "  \"firmwareBytes\": \"" + device.firmwareBytes + "\",\n"
                + "  \"mode\": \"USR/SPK\",\n"
                + "  \"verifiedConfigurationBase64\": \"" + base64.encodeToString(configuration) + "\",\n"
                + "  \"usrPresetBase64\": \"" + base64.encodeToString(usr) + "\",\n"
                + "  \"spkPresetBase64\": \"" + base64.encodeToString(spk) + "\"\n"
                + "}\n";
    }
}
