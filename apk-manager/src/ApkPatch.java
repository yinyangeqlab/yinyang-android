package com.yinyangeq.apkmanager;

import java.io.*;
import java.util.zip.GZIPInputStream;

/** Rebuild the publisher's signed APK in a new file from literal bytes and verified base ranges. */
final class ApkPatch {
    static void apply(File installed, File patch, File staged, long expected) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                     new GZIPInputStream(new FileInputStream(patch))));
             RandomAccessFile base = new RandomAccessFile(installed, "r");
             FileOutputStream out = new FileOutputStream(staged)) {
            if (in.readInt() != 0x59594431)
                throw new IOException("Unknown update patch.");
            byte[] buffer = new byte[65536];
            long written = 0;
            for (int count = 0; count < 65536; count++) {
                int op = in.readUnsignedByte();
                if (op == 2 && written == expected && in.read() == -1) {
                    out.getFD().sync();
                    return;
                }
                if (op != 0 && op != 1)
                    throw new IOException("Invalid patch command.");
                long offset = op == 0 ? Integer.toUnsignedLong(in.readInt()) : 0;
                long remaining = Integer.toUnsignedLong(in.readInt());
                if (remaining == 0 || remaining > expected - written || (op == 0 && offset + remaining > base.length()))
                    throw new IOException("Invalid patch bounds.");
                if (op == 0)
                    base.seek(offset);
                written += remaining;
                while (remaining > 0) {
                    int n = (int)Math.min(buffer.length, remaining);
                    if (op == 0)
                        base.readFully(buffer, 0, n);
                    else
                        in.readFully(buffer, 0, n);
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            throw new IOException("Too many patch commands.");
        }
    }
}
