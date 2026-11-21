package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.Arrays;

/** Tracks the saved selection, confirmed working copy and any incomplete hardware transaction. */
final class PeqSession {
    interface Journal {
        void persist(PeqSession session) throws IOException;
    }
    final int group, originalSelector;
    final Peq original;
    Peq confirmed, pending;
    int saveTarget = -1;
    String newSlotName;
    PeqSession(int group, int selector, Peq original) {
        this.group = group;
        originalSelector = selector;
        this.original = original.copy();
        confirmed = original.copy();
    }
    static Peq block(EqSnapshot s, int g) {
        return new Peq(g, g == 0 ? s.usr : s.spk);
    }
    static int selector(EqSnapshot s, int g) {
        return s.configuration[3 + 9 * g] & 255;
    }
    private static void check(boolean ok, String message) throws ProtocolException {
        if (!ok)
            throw new ProtocolException(message);
    }
    static boolean legalIntermediate(Peq from, Peq to, Peq live) {
        // The radio may have stopped between any two parameter commands. Reject unrelated edits.
        Peq copy = from.copy();
        if (live.xfeed() == to.xfeed() && live.impedance() == to.impedance()
                && live.sensitivity() == to.sensitivity())
            copy.metadata(to.xfeed(), to.impedance(), to.sensitivity());
        for (int ch = 0; ch < copy.channels; ch++) {
            if (live.pre(ch) == to.pre(ch))
                copy.pre(ch, to.pre(ch));
            for (int b = 0; b < 10; b++)
                if (live.hz(ch, b) == to.hz(ch, b) && live.word(ch, b) == to.word(ch, b))
                    copy.band(ch, b, to.type(ch, b), to.hz(ch, b), to.gain(ch, b), to.q(ch, b));
        }
        return copy.same(live);
    }
    void guard(EqSnapshot s) throws IOException {
        guard(s, true);
    }
    void guard(EqSnapshot s, boolean requireEnabled) throws IOException {
        Peq live = block(s, group);
        int selected = selector(s, group);
        check(selected == originalSelector || selected == 255 || (saveTarget >= 22 && selected == saveTarget),
                "The active slot changed. Reopen the current preset before editing.");
        check(live.same(confirmed) || (selected == originalSelector && live.same(original))
                        || (pending != null && legalIntermediate(confirmed, pending, live)),
                "The 5K settings changed outside this editor. Reopen the current preset.");
        check((!requireEnabled || (s.configuration[4 + 9 * group] & 1) != 0) && live.parametric(),
                "Enable this PEQ group on the 5K first.");
    }
    int preview(GaiaProbe.Connection link, EqSnapshot start, Peq wanted, Journal journal) throws IOException {
        guard(start);
        check(saveTarget < 0, "A save still needs verification. Use Verify save.");
        Peq live = block(start, group);
        // The journal precedes the first write and records the actual starting point on retry.
        confirmed = live;
        pending = wanted.copy();
        journal.persist(this);
        if (!live.same(wanted)) {
            if (live.xfeed() != wanted.xfeed() || live.impedance() != wanted.impedance()
                    || live.sensitivity() != wanted.sensitivity())
                link.metadata(wanted);
            int edits = 0;
            for (int ch = 0; ch < wanted.channels; ch++) {
                if (wanted.pre(ch) < live.pre(ch))
                    link.preamp(wanted, ch);
                for (int b = 0; b < 10; b++)
                    if (live.hz(ch, b) != wanted.hz(ch, b) || live.word(ch, b) != wanted.word(ch, b)) {
                        link.editBand(wanted, ch, b);
                        if (++edits % 4 == 0) {
                            byte[] reported = link.readEqConfiguration();
                            EqSnapshot.checkConfiguration(reported);
                            byte[] expected = start.configuration.clone();
                            int selector = reported[3 + 9 * group] & 255;
                            check(selector == 255 || selector == selector(start, group),
                                    "Preset selection changed during preview.");
                            expected[3 + 9 * group] = (byte) selector;
                            check(Arrays.equals(expected, reported),
                                    "Device configuration changed during preview.");
                        }
                    }
                if (wanted.pre(ch) > live.pre(ch))
                    link.preamp(wanted, ch);
            }
            ReversibleEqTest.settle();
        }
        EqSnapshot actual = link.read();
        verifyTransition(start, actual, wanted);
        int selector = selector(actual, group);
        check(selector == 255 || selector == selector(start, group),
                "Unexpected preset after preview. Read the device again.");
        confirmed = wanted.copy();
        pending = null;
        journal.persist(this);
        return selector;
    }
    void verifyTransition(EqSnapshot start, EqSnapshot actual, Peq wanted) throws IOException {
        byte[] config = start.configuration.clone();
        config[3 + 9 * group] = actual.configuration[3 + 9 * group];
        check(Arrays.equals(config, actual.configuration) && block(actual, group).same(wanted)
                        && block(actual, 1 - group).same(block(start, 1 - group)),
                "5K read-back differs. The hardware operation needs verification.");
    }
    void restore(GaiaProbe.Connection link, EqSnapshot start) throws IOException {
        if (!(selector(start, group) == originalSelector && block(start, group).same(original)))
            guard(start);
        check(saveTarget < 0, "Verify the pending save before restoring.");
        check(originalSelector >= 0 && originalSelector < 42,
                "Choose a saved preset first to establish a restore point.");
        link.preset(group, originalSelector, false);
    }
}
