package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.util.Arrays;

/** Idempotent routing change paired with the temporary speaker EQ, including recovery after lost replies. */
final class ChannelRouting {
    private static void checkContext(EqSnapshot baseline, EqSnapshot state) throws IOException {
        byte[] expected = baseline.configuration.clone();
        int selected = PeqSession.selector(state, 1);
        expected[12] = state.configuration[12];
        if (!Arrays.equals(expected, state.configuration) || !Arrays.equals(baseline.usr, state.usr))
            throw new IOException("Device settings changed during the channel swap. Review the current 5K settings.");
        if (selected != 255 && selected != PeqSession.selector(baseline, 1) && PeqSession.selector(baseline, 1) != 255)
            throw new IOException("The selected Channel Match preset changed.");
    }

    static void apply(GaiaProbe.Connection link, EqSnapshot baseline, EqSnapshot current,
            PeqSession session, boolean target, PeqSession.Journal journal) throws IOException {
        checkContext(baseline, current);
        Peq start = PeqSession.block(baseline, 1);
        Peq wanted = start.swapChannels();
        Peq actual = PeqSession.block(current, 1);
        if (!PeqSession.legalIntermediate(start, wanted, actual))
            throw new IOException("Channel Match changed outside the swap. Read the 5K before continuing.");
        if (actual.same(wanted)) {
            session.guard(current, false);
            session.confirmed = wanted.copy();
            session.pending = null;
            journal.persist(session);
        } else {
            session.preview(link, current, wanted, journal);
        }
        if (link.channelsSwapped() != target)
            link.routeChannels(target);
        if (restoreSelector(baseline, session, wanted) && PeqSession.selector(link.read(), 1) != session.originalSelector)
            link.preset(1, session.originalSelector, false);
    }

    private static boolean restoreSelector(EqSnapshot baseline, PeqSession session, Peq wanted) {
        return PeqSession.selector(baseline, 1) == 255 && session.originalSelector >= 0
                && session.originalSelector < 42 && wanted.same(session.original);
    }

    static void verify(GaiaProbe.Connection link, EqSnapshot baseline, EqSnapshot after,
            PeqSession session, boolean target) throws IOException {
        Peq wanted = PeqSession.block(baseline, 1).swapChannels();
        checkContext(baseline, after);
        int selected = PeqSession.selector(after, 1);
        if (selected != 255 && selected != PeqSession.selector(baseline, 1)
                && !(restoreSelector(baseline, session, wanted) && selected == session.originalSelector))
            throw new IOException("The selected Channel Match preset changed during verification.");
        if (!wanted.same(PeqSession.block(after, 1)) || link.channelsSwapped() != target
                || (restoreSelector(baseline, session, wanted) && PeqSession.selector(after, 1) != session.originalSelector))
            throw new IOException("5K routing or Channel Match needs verification. Tap Finish channel swap.");
    }
}
