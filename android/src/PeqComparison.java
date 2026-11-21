package com.yinyangeq.qudelixprobe;

/** Prepare the two audition curves on the same equipment profile and estimate their relative level. */
final class PeqComparison {
    private final Peq[] curves = new Peq[2];
    final int[] attenuation = new int[2];

    PeqComparison(Peq a, Peq b, Peq original, Peq other, boolean normalize) {
        Peq[] sources = {a, b};
        PeqResponse.ModeledLevel[] levels = new PeqResponse.ModeledLevel[2];
        for (int i = 0; i < 2; i++) {
            Peq src = sources[i];
            Peq.require(src.parametric() && src.group == original.group, "Choose two PEQs from the same stage.");
            Peq out = original.copy();
            for (int ch = 0; ch < out.channels; ch++) {
                out.pre(ch, src.pre(ch));
                for (int band = 0; band < 10; band++)
                    out.band(ch, band, src.type(ch, band), src.hz(ch, band), src.gain(ch, band), src.q(ch, band));
            }
            curves[i] = out;
            if (!normalize)
                continue;
            Peq.require(other == null || other.parametric(), "Level match needs a parametric EQ in each enabled stage.");
            PeqResponse[] edited=new PeqResponse[2], fixed=new PeqResponse[2];
            for (int ear=0;ear<2;ear++) {
                edited[ear]=new PeqResponse(out,out.group==0?0:ear);
                if(other!=null)fixed[ear]=new PeqResponse(other,other.group==0?0:ear);
            }
            levels[i]=PeqResponse.stereoLevel(edited,fixed);
        }
        if (normalize) {
            int[] cuts=PeqResponse.matchAttenuation(levels[0],levels[1]);
            for(int i=0;i<2;i++){
                attenuation[i]=cuts[i];
                for (int ch = 0; ch < curves[i].channels; ch++) {
                    int pre = curves[i].pre(ch) - attenuation[i];
                    Peq.require(pre >= -240 && pre <= 240, "These EQs exceed the preamp range for level matching. Reduce their boosts.");
                    curves[i].pre(ch, pre);
                }
            }
        }
    }
    Peq side(int i) {
        return curves[i].copy();
    }
}
