package com.yinyangeq.qudelixprobe;

import java.util.ArrayList;
import java.util.List;

final class PresetDiff {
    private PresetDiff() {}

    static Peq restore(Peq current, Peq archived, boolean exchangeChannels) {
        Peq.require(current.group == archived.group && current.parametric() && archived.parametric(),
                "Choose a parametric preset in the same bank.");
        Peq result = current.copy();
        result.metadata(archived.xfeed(), archived.impedance(), archived.sensitivity());
        for (int ch = 0; ch < current.channels; ch++) {
            int from = exchangeChannels ? current.channels - 1 - ch : ch;
            result.pre(ch, archived.pre(from));
            for (int band = 0; band < 10; band++) {
                result.band(ch, band, archived.type(from, band), archived.hz(from, band),
                        archived.gain(from, band), archived.q(from, band));
            }
        }
        return result;
    }

    static String describe(Peq before, Peq after) {
        List<String> changes = new ArrayList<>();
        if (before.xfeed() != after.xfeed())
            changes.add("Crossfeed · " + before.xfeed() + " → " + after.xfeed());
        if (before.impedance() != after.impedance())
            changes.add(Peq.fmt("Impedance · %.1f → %.1f Ω", before.impedance()/10.0, after.impedance()/10.0));
        if (before.sensitivity() != after.sensitivity())
            changes.add(Peq.fmt("Sensitivity · %.1f → %.1f dB SPL/mW", before.sensitivity()/10.0, after.sensitivity()/10.0));
        for (int ch = 0; ch < before.channels; ch++) {
            String side = before.channels == 1 ? "" : (ch == 0 ? "Left " : "Right ");
            if (before.pre(ch) != after.pre(ch))
                changes.add(Peq.fmt("%sPreamp · %+.1f → %+.1f dB", side, before.pre(ch)/10.0, after.pre(ch)/10.0));
            for (int band = 0; band < 10; band++) {
                String earlier = filter(before,ch,band);
                String next = filter(after,ch,band);
                if (!earlier.equals(next))
                    changes.add(side+"Filter "+(band+1)+"\n"+earlier+" → "+next);
            }
        }
        return changes.isEmpty() ? "No filter, preamp or device metadata changes."
                : String.join("\n\n",changes);
    }

    private static String filter(Peq eq, int ch, int band) {
        int type = eq.type(ch,band);
        String label = type < Peq.TYPES.length ? Peq.TYPES[type] : "Type "+type;
        return Peq.fmt("%s  %d Hz  %+.1f dB  Q %.4f",label,eq.hz(ch,band),eq.gain(ch,band)/10.0,
                eq.q(ch,band)/1024.0);
    }
}
