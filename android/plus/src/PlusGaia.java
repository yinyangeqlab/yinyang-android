package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;

final class PlusGaia {
    static final int V3 = 0xf003, LEGACY = 0xf001;

    static byte[] encode(byte[] report, int vendor) {
        int size = (report[1] & 255) - 3;
        return ByteBuffer.allocate(4 + size).putShort((short) vendor)
            .put(report, 3, 2 + size).array();
    }

    static byte[] decode(byte[] pdu, int vendor) throws IOException {
        if (pdu.length < 4 || (ByteBuffer.wrap(pdu).getShort() & 65535) != vendor) return null;
        int command = (pdu[2] & 255) << 8 | (pdu[3] & 255);
        if (command < 0x9000 || command > 0x9005) return null;
        int start = vendor == LEGACY ? 5 : 4;
        if (pdu.length <= start || pdu.length - start > 124)
            throw new ProtocolException("Invalid Bluetooth control frame.");
        if (vendor == LEGACY && pdu[4] != 0)
            throw new ProtocolException("The 5K Plus declined the Bluetooth command.");
        byte[] frame = new byte[4 + pdu.length - start];
        frame[0] = 9;
        frame[1] = (byte) (frame.length - 2);
        frame[2] = pdu[2];
        frame[3] = pdu[3];
        System.arraycopy(pdu, start, frame, 4, pdu.length - start);
        return frame;
    }
}
