package com.yinyangeq.qudelixprobe;

import java.io.*;
import java.util.*;

/** A blind round owns only the working EQ registers. The journal precedes every hardware change. */
final class PlusBlindRound {
    static final class ExternalEdit extends IOException {
        ExternalEdit(){super("The device EQ changed outside this round. Restoration paused to protect those edits.");}
    }
    interface Journal {
        void save(byte[] bytes) throws IOException;
        void clear() throws IOException;
    }
    static final class Choice {
        final String name;
        final PlusPreset.Mode mode;
        final PlusPreset.Banks banks;
        Choice(String name, PlusPreset.Mode mode, PlusPreset.Banks banks) {
            this.name=name; this.mode=mode; this.banks=banks;
        }
        static Choice device() { return new Choice("Starting device EQ", null, null); }
        Map<Integer,byte[]> target(Map<Integer,byte[]> before) throws IOException {
            return mode==null ? before : PlusApply.plan(before,mode,banks);
        }
    }

    private final String identity;
    final String[] names;
    private final List<Map<Integer,byte[]>> states;
    final BlindRound round;
    final boolean levelMatched;
    final int[] attenuation;
    private int current, pending;
    private boolean restoring;

    private PlusBlindRound(String identity, String[] names, List<Map<Integer,byte[]>> states, BlindRound round,
                           int current, int pending, boolean restoring, boolean levelMatched, int[] attenuation) {
        this.identity=identity; this.names=names; this.states=states; this.round=round;
        this.current=current; this.pending=pending; this.restoring=restoring;
        this.levelMatched=levelMatched; this.attenuation=attenuation;
    }

    static PlusBlindRound start(PlusApply device, String identity, Choice first, Choice second, boolean matchLevel, Journal journal) throws IOException {
        Map<Integer,byte[]> before=device.read(PlusApply.WORKING);
        if (((before.get(0x80)[0]&255)>>>4)>1) throw new IOException("The Plus reported an unknown EQ layout.");
        Map<Integer,byte[]> a=first.target(before), b=second.target(before);
        if (same(a,b)) throw new IOException("Choose two different EQs for this round.");
        int[] cuts={0,0};
        if(matchLevel){
            PlusBlindLevel sideA=new PlusBlindLevel(a), sideB=new PlusBlindLevel(b);
            cuts=PeqResponse.matchAttenuation(sideA.level,sideB.level);
            a=sideA.trimmed(cuts[0]);b=sideB.trimmed(cuts[1]);
        }
        device.verify(before,PlusApply.WORKING);
        PlusBlindRound session=new PlusBlindRound(identity,new String[]{first.name,second.name},Arrays.asList(before,a,b),
                BlindRound.start(),0,-1,false,matchLevel,cuts);
        journal.save(session.bytes());
        return session;
    }

    private static boolean same(Map<Integer,byte[]> a, Map<Integer,byte[]> b) {
        for (int id:PlusApply.WORKING) if (!Arrays.equals(a.get(id),b.get(id))) return false;
        return true;
    }

    private void requireOwner(String actual) throws IOException {
        if (!identity.equals(actual)) throw new IOException("Reconnect the same 5K Plus to continue this round.");
    }

    boolean interrupted() { return restoring || pending!=-1; }

    void hear(int letter, PlusApply device, String actual, Journal journal) throws IOException {
        requireOwner(actual);
        if (interrupted()) throw new IOException("Finish restoring the starting EQ before another audition.");
        if (letter!=0 && letter!=1) throw new IOException("Unknown audition.");
        Map<Integer,byte[]> before=states.get(current);
        device.verify(before,PlusApply.WORKING);
        int target=1+round.source(letter);
        pending=target;
        journal.save(bytes());
        device.moveWorking(before,states.get(target),false);
        current=target;
        pending=-1;
        round.verified(letter);
        journal.save(bytes());
    }

    void choose(int letter, Journal journal) throws IOException {
        if (interrupted() || round.heardMask!=3 || !round.hidden()) throw new IOException("Listen to both EQs before choosing a preference.");
        round.pick(letter);
        journal.save(bytes());
    }

    void end(PlusApply device, String actual, Journal journal) throws IOException {
        requireOwner(actual);
        Map<Integer,byte[]> live=device.read(PlusApply.WORKING);
        Map<Integer,byte[]> baseline=states.get(0), confirmed=states.get(current);
        if (same(live,baseline)) {
            device.verify(baseline,PlusApply.WORKING);
            journal.clear();
            return;
        }
        Map<Integer,byte[]> attempted=pending==-1 ? confirmed : states.get(pending);
        for(int id:PlusApply.WORKING) {
            byte[] value=live.get(id);
            if (!Arrays.equals(value,confirmed.get(id)) && !Arrays.equals(value,attempted.get(id))
                    && !(restoring && Arrays.equals(value,baseline.get(id)))) {
                throw new ExternalEdit();
            }
        }
        restoring=true;
        journal.save(bytes());
        device.moveWorking(live,baseline,true);
        journal.clear();
    }

    byte[] bytes() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) {
            out.writeInt(0x59414232); out.writeUTF(identity);
            for(String name:names) out.writeUTF(name);
            out.writeBoolean(round.swapped); out.writeByte(round.heardMask); out.writeByte(round.favorite);
            out.writeByte(current); out.writeByte(pending); out.writeBoolean(restoring);
            out.writeBoolean(levelMatched);out.writeShort(attenuation[0]);out.writeShort(attenuation[1]);
            for(Map<Integer,byte[]> state:states) for(int id:PlusApply.WORKING) out.write(state.get(id));
        }
        return bytes.toByteArray();
    }

    static PlusBlindRound read(byte[] bytes) throws IOException {
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version=in.readInt();
            if(version!=0x59414231 && version!=0x59414232) throw new IOException("Unrecognized saved comparison.");
            String id=in.readUTF();
            String[] names={in.readUTF(),in.readUTF()};
            boolean swap=in.readBoolean();int heard=in.readByte(), favorite=in.readByte(), current=in.readByte(), pending=in.readByte();
            if(heard<0 || heard>3 || favorite < -1 || favorite>1 || current<0 || current>2 || (pending!=-1 && pending!=1 && pending!=2)
                    || (favorite!=-1 && heard!=3)) throw new IOException("Invalid saved comparison state.");
            boolean recovering=in.readBoolean();
            boolean matched=version==0x59414232 && in.readBoolean();
            int[] cuts=version==0x59414232?new int[]{in.readUnsignedShort(),in.readUnsignedShort()}:new int[]{0,0};
            if(cuts[0]>960 || cuts[1]>960 || (!matched && (cuts[0]!=0 || cuts[1]!=0)))throw new IOException("Invalid saved level settings.");
            List<Map<Integer,byte[]>> states=new ArrayList<>();
            for(int i=0;i<3;i++) {
                Map<Integer,byte[]> regs=new LinkedHashMap<>();
                for(int reg:PlusApply.WORKING) {
                    byte[] value=new byte[PlusApply.length(reg)];in.readFully(value);regs.put(reg,value);
                }
                states.add(regs);
            }
            if(in.read()!=-1)throw new IOException("Unexpected saved comparison data.");
            return new PlusBlindRound(id,names,states,new BlindRound(swap,heard,favorite),current,pending,recovering,matched,cuts);
        }
    }
}
