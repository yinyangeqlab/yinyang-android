package com.yinyangeq.qudelixprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Original 5K v3 split-mode PEQ. Keep unused channels and reserved bits byte-exact. */
final class Peq {
    static final String[] TYPES = {"OFF", "LP", "HP", "LS", "HS", "PK"};
    final int group, channels;
    final byte[] bytes;
    private final ByteBuffer data;
    Peq(int group, byte[] bytes) {
        require((group == 0 && bytes.length == 88) || (group == 1 && bytes.length == 128),
                "Unexpected EQ block.");
        this.group = group;
        channels = group + 1;
        this.bytes = bytes.clone();
        data = ByteBuffer.wrap(this.bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    static void require(boolean ok, String message) {
        if (!ok)
            throw new IllegalArgumentException(message);
    }
    static Peq flat(int group) {
        Peq p = new Peq(group, new byte[group == 0 ? 88 : 128]);
        p.bytes[0] = 1;
        for (int ch = 0; ch < p.channels; ch++)
            for (int b = 0; b < 10; b++)
                p.band(ch, b, 0, 1000, 0, 1024);
        return p;
    }
    Peq copy() {
        return new Peq(group, bytes);
    }
    Peq swapChannels() {
        require(group == 1 && parametric(), "Channel routing needs a left/right speaker PEQ.");
        Peq result = copy();
        for (int ch = 0; ch < 2; ch++) {
            int other = 1 - ch;
            result.pre(ch, pre(other));
            for (int band = 0; band < 10; band++)
                result.band(ch, band, type(other, band), hz(other, band), gain(other, band), q(other, band));
        }
        return result;
    }
    boolean same(Peq other) {
        return other != null && group == other.group && Arrays.equals(bytes, other.bytes);
    }
    boolean parametric() {
        return (bytes[0] & 1) != 0;
    }
    int pre(int ch) {
        return data.getShort(4 + 2 * ch);
    }
    void pre(int ch, int value) {
        data.putShort(4 + 2 * ch, (short) value);
    }
    int hz(int ch, int b) {
        return Short.toUnsignedInt(data.getShort(8 + 20 * ch + 2 * b));
    }
    int word(int ch, int b) {
        return data.getInt(48 + 40 * ch + 4 * b);
    }
    int type(int ch, int b) {
        return word(ch, b) & 15;
    }
    int gain(int ch, int b) {
        return word(ch, b) << 18 >> 22;
    }
    int q(int ch, int b) {
        return (word(ch, b) >>> 14) & 16383;
    }
    int xfeed() {
        return data.getInt(0) >>> 26;
    }
    int impedance() {
        return (data.getInt(0) >>> 1) & 16383;
    }
    int sensitivity() {
        return (data.getInt(0) >>> 15) & 2047;
    }
    void metadata(int xfeed, int impedance, int sensitivity) {
        data.putInt(0, (bytes[0] & 1) | (impedance << 1) | (sensitivity << 15) | (xfeed << 26));
    }
    void band(int ch, int b, int type, int hz, int gain, int q) {
        require(ch >= 0 && ch < channels && b >= 0 && b < 10 && type >= 0 && type < TYPES.length,
                "Invalid filter.");
        data.putShort(8 + 20 * ch + 2 * b, (short) hz);
        int offset = 48 + 40 * ch + 4 * b;
        data.putInt(offset, (data.getInt(offset) & 0xf0000000) | type | ((gain & 1023) << 4) | (q << 14));
    }
    static int number(String s, double min, double max, int scale, String label) {
        double value;
        try {
            value = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a number for " + label + ".");
        }
        require(Double.isFinite(value) && value >= min && value <= max,
                label + " must be between " + min + " and " + max + ".");
        return (int) Math.round(value * scale);
    }
    void removeBand(int ch,int index){
        require(parametric() && ch>=0 && ch<channels && index>=0 && index<10,"Invalid filter row.");
        for(int i=index;i<9;i++){
            data.putShort(8+20*ch+2*i,(short)hz(ch,i+1));
            data.putInt(48+40*ch+4*i,(word(ch,i)&0xf0000000)|(word(ch,i+1)&0x0fffffff));
        }
        band(ch,9,0,1000,0,1024);
    }
    boolean hasFilters() {
        for(int ch=0;ch<channels;ch++)
            for(int i=0;i<10;i++)if(type(ch,i)!=0)return true;
        return false;
    }
    Peq clearFilters() {
        require(parametric(),"Select a PEQ preset first.");
        Peq next=copy();
        for(int ch=0;ch<channels;ch++)
            for(int i=0;i<10;i++)
                if(type(ch,i)!=0)next.band(ch,i,0,1000,0,1024);
        return next;
    }
    void edit(int ch, int band, int type, String hz, String gain, String q) {
        band(ch, band, type, number(hz, 10, 22000, 1, "Frequency"), number(gain, -50, 50, 10, "Gain"),
                number(q, 0.009765625, 15, 1024, "Q"));
    }
    static String fmt(String format, Object... values) {
        return String.format(Locale.ROOT, format, values);
    }
    String exportText() {
        require(parametric(), "This group is using GEQ. Select a PEQ preset first.");
        StringBuilder text = new StringBuilder("TYPE: PEQ\r\n");
        for (int ch = 0; ch < channels; ch++) {
            if (channels == 2)
                text.append(ch == 0 ? "\r\nChannel: L\r\n" : "\r\nChannel: R\r\n");
            text.append(fmt("Preamp: %.1f dB\r\n", pre(ch) / 10.0));
            if (group == 0)
                text.append(fmt("Xfeed: 1 %d\r\n", xfeed()));
            for (int b = 0; b < 10; b++) {
                int t = type(ch, b);
                require(t < TYPES.length, "Unknown filter type on the 5K.");
                text.append(
                        fmt("Filter %d: %s %s Fc %d Hz Gain %.1f dB Q %.8f\r\n", b + 1, t == 0 ? "OFF" : "ON",
                                t == 0 ? "PK" : TYPES[t], hz(ch, b), gain(ch, b) / 10.0, q(ch, b) / 1024.0));
            }
        }
        if (group == 1)
            text.append(fmt("IMPEDANCE: %.1f ohm\r\nSENSITIVITY: %.1f dBSPL/mW\r\n", impedance() / 10.0,
                    sensitivity() / 10.0));
        return text.toString();
    }
    private static final Pattern FILTER =
            Pattern.compile("Filter\\s+(\\d+)\\s*:\\s*(ON|OFF)\\s+(PK|LSC?|HSC?|LP|HP)\\s+Fc\\s+(\\S+)\\s+"
                            + "Hz\\s+Gain\\s+(\\S+)\\s+dB\\s+Q\\s+(\\S+)",
                    Pattern.CASE_INSENSITIVE);
    Peq importText(String source) {
        if (source.contains("Preference Tuning Lab · Qudelix 5K entry guide"))
            source = fromTuningGuide(source);
        Peq p = copy();
        require(parametric(), "Choose a parametric preset before importing.");
        boolean[][] seen = new boolean[channels][10];
        boolean[] channelSeen = new boolean[channels];
        HashSet<String> fields = new HashSet<>();
        int ch = 0, line = 0;
        for (String raw : source.replace("\ufeff", "").split("\\r\\n|[\\r\\n]")) {
            line++;
            String s = raw.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//"))
                continue;
            String[] pair = s.split("\\s*:\\s*", 2);
            require(pair.length == 2, "Unexpected text on line " + line + ".");
            String key = pair[0].toUpperCase(Locale.ROOT), value = pair[1].trim();
            if (key.equals("CHANNEL") || key.equals("CH")) {
                require(group == 1, "A left/right file belongs in Channel Match.");
                String token = value.split("\\s+")[0];
                ch = token.equalsIgnoreCase("L") || token.equals("0")      ? 0
                        : token.equalsIgnoreCase("R") || token.equals("1") ? 1
                                                                           : -1;
                require(ch >= 0 && !channelSeen[ch], "Invalid or duplicate channel on line " + line + ".");
                channelSeen[ch] = true;
            } else if (key.startsWith("FILTER ")) {
                Matcher m = FILTER.matcher(s);
                require(m.matches(), "Invalid filter on line " + line + ".");
                int b = Integer.parseInt(m.group(1)) - 1;
                require(b >= 0 && b < 10 && !seen[ch][b], "Each channel supports ten unique filter numbers.");
                require(group == 0 || channelSeen[ch],
                        "Choose Main EQ for a stereo file, or include left and right sections.");
                int type = m.group(2).equalsIgnoreCase("OFF")
                        ? 0
                        : Arrays.asList(TYPES).indexOf(m.group(3).toUpperCase(Locale.ROOT).replace("SC", "S"));
                if (type == 0)
                    p.band(ch, b, 0, number(m.group(4), 0, 22000, 1, "Frequency"),
                            number(m.group(5), -50, 50, 10, "Gain"), number(m.group(6), 0, 15, 1024, "Q"));
                else
                    p.edit(ch, b, type, m.group(4), m.group(5), m.group(6));
                seen[ch][b] = true;
            } else {
                require(fields.add(key + (key.equals("PREAMP") ? ch : "")),
                        "Duplicate " + key + " on line " + line + ".");
                switch (key) {
                    case "MODE":
                        require(group == 0 ? value.equalsIgnoreCase("Normal")
                                           : value.replace(" ", "").equalsIgnoreCase("L/R"),
                                "File channel mode does not match this group.");
                        break;
                    case "TYPE":
                        require(value.equalsIgnoreCase("PEQ"), "Choose a PEQ file.");
                        break;
                    case "PREAMP":
                        require(group == 0 || channelSeen[ch], "Put the channel before its preamp.");
                        p.pre(ch, number(unit(value, "dB"), -24, 24, 10, "Preamp"));
                        break;
                    case "XFEED":
                        String[] x = value.split("\\s+");
                        require(group == 0 && x.length == 2 && x[0].equals("1"),
                                "Unsupported crossfeed channel.");
                        p.metadata(number(x[1], 0, 50, 1, "Crossfeed"), p.impedance(), p.sensitivity());
                        break;
                    case "IMPEDANCE":
                        require(group == 1, "Receiver data belongs in Channel Match.");
                        p.metadata(p.xfeed(), number(unit(value, "ohm"), 0, 1000, 10, "Impedance"),
                                p.sensitivity());
                        break;
                    case "SENSITIVITY":
                        require(group == 1, "Receiver data belongs in Channel Match.");
                        p.metadata(p.xfeed(), p.impedance(),
                                number(unit(value, "dBSPL/mW"), 0, 150, 10, "Sensitivity"));
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported setting on line " + line + ".");
                }
            }
        }
        for (int c = 0; c < channels; c++) {
            boolean any = false;
            for (int b = 0; b < 10; b++) {
                if (seen[c][b])
                    any = true;
                else
                    p.band(c, b, 0, 1000, 0, 1024);
            }
            require(any,
                    "Include filters for "
                            + (group == 0 ? "the stereo group"
                                            : c == 0 ? "the left channel"
                                                     : "the right channel")
                            + ".");
            if (!fields.contains("PREAMP" + c))
                p.pre(c, 0);
        }
        return p;
    }
    private static String fromTuningGuide(String text) {
        Matcher gain = Pattern.compile("(?m)^Set PRE GAIN to ([+-]?[0-9.]+) dB[.]").matcher(text);
        String[] sections = text.replace("\r", "").split(
                "(?m)^Band\\tType\\tFrequency \\(Hz\\)\\tGain \\(dB\\)\\tQ\\s*\n", 2);
        require(gain.find() && sections.length == 2, "Incomplete YinYang EQ entry guide.");
        StringBuilder result = new StringBuilder("TYPE: PEQ\nPreamp: ").append(gain.group(1)).append(" dB\n");
        for (String row : sections[1].trim().split("\n")) {
            String[] col = row.trim().split("\\s+");
            require(col.length == 5, "Invalid filter row in the YinYang EQ guide.");
            result.append(fmt("Filter %s: ON %s Fc %s Hz Gain %s dB Q %s\n", (Object[]) col));
        }
        return result.toString();
    }

    private static String unit(String value, String unit) {
        require(value.toLowerCase(Locale.ROOT).endsWith(unit.toLowerCase(Locale.ROOT)),
                "Expected " + unit + ".");
        return value.substring(0, value.length() - unit.length()).trim();
    }
}
