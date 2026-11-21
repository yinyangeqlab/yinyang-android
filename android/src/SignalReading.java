package com.yinyangeq.qudelixprobe;

final class SignalReading {
    enum State { UNAVAILABLE, NORMAL, LOW_HEADROOM, NEAR_FULL_SCALE, CLIPPING_RISK }
    static State state(double rms) {
        if(Double.isNaN(rms)) return State.UNAVAILABLE;
        if(rms>=0) return State.CLIPPING_RISK;
        if(rms>=-1) return State.NEAR_FULL_SCALE;
        if(rms>=-6) return State.LOW_HEADROOM;
        return State.NORMAL;
    }
    static double spl(double rms,double mv,double ohms,double sensitivity) {
        if(!Double.isFinite(mv) || !Double.isFinite(ohms) || !Double.isFinite(sensitivity)
                || mv<0 || ohms<=0 || sensitivity<=0) return Double.NaN;
        if(mv==0) return Double.isNaN(rms)?Double.NaN:Double.NEGATIVE_INFINITY;
        return sensitivity+10*Math.log10(mv*mv/(1000*ohms))+rms;
    }
}
