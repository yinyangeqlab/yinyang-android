package com.yinyangeq.qudelixprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlusPreset {
    private static final Pattern FILTER = Pattern.compile(
        "Filter(?:\\s+(\\d+))?\\s*:\\s*(ON|OFF)\\s+(PK|PEQ|LSC?|HSC?|LP|HP)\\s+Fc\\s+(\\S+)\\s+Hz"
        + "\\s+Gain\\s+(\\S+)\\s+dB(?:\\s+Q\\s+(\\S+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREAMP = Pattern.compile("Preamp\\s*:\\s*(\\S+)\\s+dB", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL = Pattern.compile("(?:CH\\s*:\\s*([01])|Channel\\s*:\\s*([LR]))",
        Pattern.CASE_INSENSITIVE);
    static final int Q_SCALE = 1024;

    enum Mode {
        LAYERED(30, "USR 10 / SPK 20 layered"), SHARED(30, "USR 30-band");

        final int limit;
        final String deviceLabel;

        Mode(int limit, String deviceLabel) {
            this.limit = limit;
            this.deviceLabel = deviceLabel;
        }
    }

    static final class Band {
        final int type, hz, gain, q;

        Band(int type, int hz, int gain, int q) {
            Peq.require(type >= 1 && type <= 5 && hz >= 10 && hz <= 22000 && Math.abs((long) gain) <= 500
                && q >= 10 && q <= 63 * Q_SCALE, "Filter is outside the 5K Plus PEQ range.");
            this.type = type;
            this.hz = hz;
            this.gain = gain;
            this.q = q;
        }

        static Band input(int type, String frequency, String gain, String q) {
            return new Band(type, Peq.number(frequency, 10, 22000, 1, "Frequency"),
                Peq.number(gain, -50, 50, 10, "Gain"), Peq.number(q, .01, 63, Q_SCALE, "Q"));
        }
    }

    static final class Channel {
        final int preamp;
        final List<Band> bands;

        Channel(int preamp, List<Band> bands) {
            Peq.require(Math.abs((long) preamp) <= 240, "Preamp must be between −24 and +24 dB.");
            this.preamp = preamp;
            this.bands = Collections.unmodifiableList(new ArrayList<>(bands));
        }

        static Channel flat() {
            return new Channel(0, Collections.emptyList());
        }

        Channel clearFilters() {
            return bands.isEmpty() ? this : new Channel(preamp, Collections.emptyList());
        }

        Channel replace(int index, Band band) {
            List<Band> next = new ArrayList<>(bands);
            if (band == null) next.remove(index);
            else if (index == bands.size()) next.add(band);
            else next.set(index, band);
            return new Channel(preamp, next);
        }
    }

    static final class Banks {
        final Channel user;
        final Channel[] match;
        final int userReduction;

        Banks(Channel user, Channel[] match, int userReduction) {
            this.user = user;
            this.match = match;
            this.userReduction = userReduction;
        }
    }

    static Channel merge(Channel first, Channel second) {
        List<Band> bands = new ArrayList<>(first.bands);
        bands.addAll(second.bands);
        // This representation is used for the two physical stages before the preamps are applied.
        return new Channel(0, bands);
    }

    static int sharedCapacity(Channel left, Channel right) {
        return 30 - Math.max(left.bands.size(), right.bands.size());
    }

    static int matchCapacity(Channel shared) {
        return 20 - Math.max(0, shared.bands.size() - 10);
    }

    static Banks banks(Mode mode, Channel common, Channel left, Channel right, boolean protectHeadroom) {
        Peq.require(common.bands.size() <= mode.limit, "This layout fits up to " + mode.limit + " Main EQ filters.");
        if (mode == Mode.LAYERED) {
            Peq.require(left.bands.size() <= 20 && right.bands.size() <= 20, "Each channel has 20 match slots.");
            int capacity = sharedCapacity(left, right);
            Peq.require(common.bands.size() <= capacity, "These corrections leave room for " + capacity + " Main EQ filters.");
        }
        Channel user = common;
        Channel[] match = null;
        if (mode == Mode.LAYERED) {
            int boundary = Math.min(10, common.bands.size());
            user = new Channel(common.preamp, common.bands.subList(0, boundary));
            Channel overflow = new Channel(0, common.bands.subList(boundary, common.bands.size()));
            match = new Channel[] {
                new Channel(left.preamp, merge(overflow, left).bands),
                new Channel(right.preamp, merge(overflow, right).bands),
            };
        }
        int userReduction = protectHeadroom ? attenuation(new Channel[]{user}) : 0;
        int matchReduction = protectHeadroom && match != null ? attenuation(match) : 0;
        user = reduce(user, userReduction);
        if (match != null) {
            match[0] = reduce(match[0], matchReduction);
            match[1] = reduce(match[1], matchReduction);
        }
        return new Banks(user, match, userReduction);
    }

    private static Channel reduce(Channel channel, int attenuation) {
        Peq.require(channel.preamp - attenuation >= -240,
            "One bank needs more attenuation than its preamp supports. Reduce boost or the left/right preamp difference.");
        return new Channel(channel.preamp - attenuation, channel.bands);
    }

    private static int attenuation(Channel[] channels) {
        double peak = Double.NEGATIVE_INFINITY;
        for (Channel channel : channels) {
            for (double rate : new double[]{44100, 48000, 88200, 96000}) {
                PeqResponse response = response(channel, rate);
                peak = Math.max(peak, Math.max(response.db(0), response.db(rate / 2)));
                for (int i = 0; i <= 4096; i++)
                    peak = Math.max(peak, response.db(.1 * Math.pow(rate / .2, i / 4096.0)));
                for (Band band : channel.bands)
                    peak = Math.max(peak, response.db(band.hz));
            }
        }
        return Math.max(0, (int) Math.ceil(peak * 10 + 15));
    }

    static PeqResponse response(Channel channel, double rate) {
        double[][] sections = new double[channel.bands.size()][];
        for (int i = 0; i < sections.length; i++) {
            Band band = channel.bands.get(i);
            sections[i] = PeqResponse.section(band.type, band.hz, band.gain / 10.0, band.q / (double) Q_SCALE, rate);
        }
        return new PeqResponse(channel.preamp / 10.0, rate, sections);
    }

    static String file(Channel[] channels, int slots) {
        StringBuilder text = new StringBuilder("TYPE: PEQ\r\n");
        for (int ch = 0; ch < channels.length; ch++) {
            Channel channel = channels[ch];
            if (channels.length == 2) text.append(Peq.fmt("\r\nCH: %d\r\n", ch));
            text.append(Peq.fmt("Preamp: %.1f dB\r\n", channel.preamp / 10.0));
            for (int i = 0; i < slots; i++) {
                Band band = i < channel.bands.size() ? channel.bands.get(i) : null;
                if (band == null) text.append(Peq.fmt("Filter %d: OFF PK Fc 1000 Hz Gain 0.0 dB Q 1.000\r\n", i + 1));
                else text.append(Peq.fmt("Filter %d: ON %s Fc %d Hz Gain %.1f dB Q %.5f\r\n",
                    i + 1, Peq.TYPES[band.type], band.hz, band.gain / 10.0, band.q / (double) Q_SCALE));
            }
        }
        return text.toString();
    }

    static Channel[] parse(String text, boolean stereo, int limit) {
        Peq.require(text.length() <= 100000, "Choose a text file under 100 KB.");
        List<List<Band>> bands = new ArrayList<>();
        int[] preamps = new int[stereo ? 2 : 1];
        boolean[] channels = new boolean[preamps.length];
        boolean[] levels = new boolean[preamps.length];
        boolean[][] used = new boolean[preamps.length][31];
        int[] rows = new int[preamps.length];
        for (int i = 0; i < preamps.length; i++) bands.add(new ArrayList<>());
        int channel = stereo ? -1 : 0, line = 0;
        for (String raw : text.replace("\ufeff", "").split("\\r\\n|[\\r\\n]")) {
            line++;
            String trimmed = raw.split("#|//", 2)[0].trim();
            if (trimmed.isEmpty() || trimmed.matches("(?i)TYPE\\s*:\\s*PEQ")
                || trimmed.matches("(?i)(?:IMPEDANCE|SENSITIVITY)\\s*:.*")
                || trimmed.matches("(?i)(?:Xfeed\\s*:\\s*\\d+\\s+0|Xspace\\s*:\\s*0)")) continue;
            if (!stereo && trimmed.matches("(?i)Channel\\s*:\\s*(ALL|L\\s+R|1\\s+2)")) continue;
            Matcher header = CHANNEL.matcher(trimmed);
            if (stereo && header.matches()) {
                String token = header.group(1) == null ? header.group(2) : header.group(1);
                channel = token.equals("0") || token.equalsIgnoreCase("L") ? 0 : 1;
                Peq.require(!channels[channel], "Repeated channel on line " + line + ".");
                channels[channel] = true;
                continue;
            }
            Peq.require(channel >= 0, "Put a Left or Right channel heading before line " + line + ".");
            Matcher level = PREAMP.matcher(trimmed);
            if (level.matches()) {
                Peq.require(!levels[channel], "Repeated preamp on line " + line + ".");
                levels[channel] = true;
                preamps[channel] = Peq.number(level.group(1), -24, 24, 10, "Preamp");
                continue;
            }
            Matcher row = FILTER.matcher(trimmed);
            Peq.require(row.matches(), "Unsupported setting on line " + line + ". Use PEQ filter settings without additional effects.");
            int slot = row.group(1) == null ? rows[channel] + 1 : Integer.parseInt(row.group(1));
            Peq.require(slot > 0 && slot <= 30 && !used[channel][slot], "Invalid or repeated filter number on line " + line + ".");
            used[channel][slot] = true;
            rows[channel]++;
            if (row.group(2).equalsIgnoreCase("OFF")) continue;
            String label = row.group(3).toUpperCase(Locale.ROOT).replace("SC", "S");
            int type = label.equals("PEQ") ? 5 : Arrays.asList(Peq.TYPES).indexOf(label);
            String q = row.group(6);
            Peq.require(q != null || type == 3 || type == 4, "Filter on line " + line + " needs a Q value.");
            bands.get(channel).add(Band.input(type, row.group(4), row.group(5), q == null ? "0.707" : q));
            Peq.require(bands.get(channel).size() <= limit, "This layout fits up to " + limit + " filters per imported channel.");
        }
        Channel[] result = new Channel[preamps.length];
        for (int ch = 0; ch < result.length; ch++) {
            Peq.require((!stereo || channels[ch]) && (rows[ch] > 0 || levels[ch]), "The file is missing PEQ settings for a required channel.");
            result[ch] = new Channel(preamps[ch], bands.get(ch));
        }
        return result;
    }
}
