package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PresetPickerTest {
    private static int passed;
    private static final String NAME = "Étude 🎧";

    public static void main(String[] args) throws IOException {
        byte[] before = config(22);
        byte[] after = config(41);
        Wire listing = new Wire(before, NAME);
        PresetDirectory list = PresetDirectory.read(listing.link());
        check(NAME.equals(list.name(0,19)) && list.name(0,1) == null && NAME.equals(list.activeName(0)), "sparse UTF-8 names");
        check(list.selector(0) == 22 && list.enabled(0), "active selector and EQ state");
        check(Arrays.equals(listing.requests.toByteArray(), packetList(false)), "read only advertised USR slots 0 and 19");
        Wire writes = new Wire(before, NAME);
        Wire reads = new Wire(after, NAME);
        int[] opens = {0};
        PresetDirectory verified = list.switchTo(() -> {
            if (opens[0]++ == 0) return writes.link();
            check(writes.closed, "original socket closed before verifying");
            return reads.link();
        }, 0, 19);
        check(opens[0] == 2 && reads.closed && verified.selector(0) == 41, "verified duplicate name by selector on reconnect");
        check(Arrays.equals(writes.requests.toByteArray(), packetList(true)), "only one load of slot 20");
        check(Arrays.equals(reads.requests.toByteArray(), packetList(false)), "fresh verification contains only reads");
        Wire same = new Wire(before, NAME);
        check(list.switchTo(same::link, 0, 0).selector(0) == 22 && Arrays.equals(same.requests.toByteArray(), packetList(false)),
                "already active is verified without writes");
        reject(() -> list.switchTo(() -> { throw new AssertionError(); }, 0, 1), "saved preset");
        Wire renamed = new Wire(before, "Renamed");
        reject(() -> list.switchTo(renamed::link, 0, 19), "list changed");
        check(Arrays.equals(renamed.requests.toByteArray(), packetList(false)), "renamed slot never loaded");
        Wire stale = new Wire(config(255), NAME);
        reject(() -> list.switchTo(stale::link, 0, 19), "active preset changed");
        int[] stalled = {0};
        reject(() -> list.switchTo(() -> {stalled[0]++; return new Wire(before, NAME).link();}, 0, 19), "different active preset");
        check(stalled[0] == 2, "failed load has no blind retry");
        byte[] empty = config(255);
        empty[6] = 0;
        empty[8] = 0;
        Wire blank = new Wire(empty, NAME);
        check(PresetDirectory.read(blank.link()).activeName(0).equals("Unsaved EQ"), "empty directory and unsaved EQ");
        byte[] both = config(22);
        both[12]=22; both[13]=1; both[15]=1; both[17]=8;
        Wire stereo=new Wire(both,NAME);
        PresetDirectory banks=PresetDirectory.read(stereo.link());
        check(banks.name(1,1)==null && "L/R · Ω".equals(banks.name(1,19)) &&
                banks.enabled(1) && banks.activeName(1).equals("L/R · Ω"),"sparse independent Speaker names");
        byte[] changed=both.clone(); changed[12]=41;
        Wire from=new Wire(both,NAME), to=new Wire(changed,NAME);
        int[] switches={0};
        PresetDirectory loaded=banks.switchTo(()->switches[0]++==0?from.link():to.link(),1,19);
        check(loaded.selector(0)==22 && loaded.selector(1)==41 && switches[0]==2,"Speaker load verified after reconnect");
        byte[] commands=from.requests.toByteArray();
        check(Arrays.equals(Arrays.copyOfRange(commands,commands.length-10,commands.length),
                new byte[]{(byte)255,1,0,2,(byte)0xf0,1,7,9,1,41}),"Speaker switch writes to selected bank only");
        byte[] drift=changed.clone();drift[3]=41;
        int[] attempts={0};
        reject(()->banks.switchTo(()->new Wire(attempts[0]++==0?both:drift,NAME).link(),1,19),"configuration");
        Wire badGroup=new Wire(both,NAME);badGroup.speakerGroup=0;
        reject(()->PresetDirectory.read(badGroup.link()),"unexpected slot");
        Wire wrong = new Wire(before, NAME);
        wrong.replyIndex = 1;
        reject(() -> PresetDirectory.read(wrong.link()), "unexpected slot");
        Wire truncated = new Wire(before, NAME);
        truncated.nameLength = 33;
        reject(() -> PresetDirectory.read(truncated.link()), "Incomplete");
        Wire unstable = new Wire(before, NAME);
        unstable.end = after;
        reject(() -> PresetDirectory.read(unstable.link()), "changed during");
        Wire fw = new Wire(before, NAME);
        fw.firmware = 0x80;
        reject(() -> PresetDirectory.read(fw.link()), "firmware 3.2.9");
        System.out.println(passed + " preset picker checks passed.");
    }

    private static byte[] packetList(boolean load) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        frame(out,0x0100,new byte[]{0,0,4});
        frame(out,0x0120,new byte[]{(byte)128});
        frame(out,0x070b,new byte[]{0,0});
        frame(out,0x070b,new byte[]{0,19});
        frame(out,0x0120,new byte[]{(byte)128});
        if (load) frame(out,0x0709,new byte[]{0,41});
        return out.toByteArray();
    }

    static byte[] config(int selector) {
        byte[] config = new byte[19];
        config[0] = (byte)128;
        config[3] = (byte)selector;
        config[4] = 1;
        config[6] = 1;
        config[8] = 8;
        config[10] = 1;
        return config;
    }

    static final class Wire {
        final byte[] config;
        byte[] end;
        final String lastName;
        int replyIndex, speakerGroup=1;
        int nameLength = -1;
        int firmware = 0x90;
        boolean closed;
        final ByteArrayOutputStream requests = new ByteArrayOutputStream();
        Wire(byte[] config, String name) {
            this.config = config;
            end = config;
            lastName = name;
        }
        GaiaProbe.Connection link() {
            ByteArrayOutputStream replies = new ByteArrayOutputStream();
            reply(replies,0x8101,new byte[]{0,1,3,(byte)0x83,(byte)firmware,0,0});
            reply(replies,0x8121,config);
            if (config[6] != 0) nameReply(replies,0,replyIndex,NAME);
            if (config[8] != 0) nameReply(replies,0,19,lastName);
            if(config[15]!=0)nameReply(replies,speakerGroup,0,"L/R · Ω");
            if(config[17]!=0)nameReply(replies,speakerGroup,19,"L/R · Ω");
            reply(replies,0x8121,end);
            return new GaiaProbe.Connection(new ByteArrayInputStream(replies.toByteArray()),requests,
                    text -> {}, () -> closed = true);
        }
        private void nameReply(ByteArrayOutputStream out, int group, int slot, String value) {
            byte[] str = value.getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[str.length+3];
            data[0]=(byte)group;
            data[1] = (byte)slot;
            data[2] = (byte)(nameLength < 0 ? str.length : nameLength);
            System.arraycopy(str,0,data,3,str.length);
            reply(out,0x870c,data);
        }
    }

    private static void reply(ByteArrayOutputStream out,int cmd,byte[] data) {
        byte[] body = new byte[data.length+1];
        System.arraycopy(data,0,body,1,data.length);
        frame(out,cmd,body);
    }
    private static void frame(ByteArrayOutputStream out,int cmd,byte[] body) {
        byte[] header = {(byte)255,1,0,(byte)body.length,(byte)0xf0,1,(byte)(cmd>>8),(byte)cmd};
        out.write(header,0,header.length);
        out.write(body,0,body.length);
    }
    private interface Action { void run() throws IOException; }
    private static void reject(Action action,String fragment) throws IOException {
        try { action.run(); } catch (ProtocolException error) {
            check(error.getMessage().contains(fragment),error.getMessage());
            return;
        }
        throw new AssertionError("Expected " + fragment);
    }
    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
}
