package com.yinyangeq.qudelixprobe;

/** Magnitude of the displayed digital biquad cascade at the chart's 48 kHz reference rate. */
final class PeqResponse {
    static final double RATE = 48000;
    private final double[][] sections;
    private final double preamp, rate;

    PeqResponse(Peq p, int ch) {
        preamp = p.pre(ch) / 10.0;
        rate = RATE;
        sections = new double[10][];
        for (int i = 0; i < sections.length; i++)
            sections[i] = section(p.type(ch, i), p.hz(ch, i), p.gain(ch, i) / 10.0, p.q(ch, i) / 1024.0, rate);
    }

    PeqResponse(double preamp, double rate, double[][] sections) {
        this.preamp = preamp;
        this.rate = rate;
        this.sections = sections;
    }

    static double[] section(int type, double hz, double gainDb, double q, double rate) {
        if (type == 0 || q <= 0 || hz <= 0 || hz >= rate / 2)
            return null;
        double w = 2 * Math.PI * hz / rate, c = Math.cos(w), a = Math.sin(w) / (2 * q);
        double A = Math.pow(10, gainDb / 40), m = A - 1, n = A + 1, t = 2 * Math.sqrt(A) * a;
        switch (type) {
            case 1:
                return new double[] {(1 - c) / 2, 1 - c, (1 - c) / 2, 1 + a, -2 * c, 1 - a};
            case 2:
                return new double[] {(1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + a, -2 * c, 1 - a};
            case 3:
                return new double[] {A * (n - m * c + t), 2 * A * (m - n * c), A * (n - m * c - t),
                        n + m * c + t, -2 * (m + n * c), n + m * c - t};
            case 4:
                return new double[] {A * (n + m * c + t), -2 * A * (m + n * c), A * (n + m * c - t),
                        n - m * c + t, 2 * (m - n * c), n - m * c - t};
            case 5:
                return new double[] {1 + a * A, -2 * c, 1 - a * A, 1 + a / A, -2 * c, 1 - a / A};
            default:
                return null;
        }
    }
    /** Equal energy per octave, sampled from 20 Hz to 20 kHz on the 48 kHz reference response. */
    static final class ModeledLevel {
        final double stereoPowerDb, peakDb;
        ModeledLevel(double stereoPowerDb,double peakDb){this.stereoPowerDb=stereoPowerDb;this.peakDb=peakDb;}
    }

    static ModeledLevel stereoLevel(PeqResponse[] first,PeqResponse[] second) {
        double power=0,peak=Double.NEGATIVE_INFINITY;
        for(int ear=0;ear<2;ear++) {
            for(int bin=0;bin<=768;bin++) {
                double hz=20*Math.pow(1000,bin/768.0);
                double db=(first[ear]==null?0:first[ear].db(hz))+(second[ear]==null?0:second[ear].db(hz));
                peak=Math.max(peak,db);
                power+=Math.pow(10,db/10);
            }
        }
        double level=10*Math.log10(power/(2*769));
        Peq.require(Double.isFinite(level)&&Double.isFinite(peak),"The stereo response cannot be level-matched.");
        return new ModeledLevel(level,peak);
    }

    static int[] matchAttenuation(ModeledLevel a,ModeledLevel b) {
        double quieter=Math.min(a.stereoPowerDb,b.stereoPowerDb);
        double reserve=Math.max(0,Math.max(a.peakDb+quieter-a.stereoPowerDb,b.peakDb+quieter-b.stereoPowerDb)+1);
        return new int[]{(int)Math.ceil(10*(a.stereoPowerDb-quieter+reserve)-1e-8),
                (int)Math.ceil(10*(b.stereoPowerDb-quieter+reserve)-1e-8)};
    }

    double db(double hz) {
        double w = 2 * Math.PI * hz / rate, c = Math.cos(w), s = Math.sin(w), c2 = Math.cos(2 * w),
               s2 = Math.sin(2 * w);
        double db = preamp;
        for (double[] f : sections)
            if (f != null) {
                double nr = f[0] + f[1] * c + f[2] * c2, ni = f[1] * s + f[2] * s2,
                       dr = f[3] + f[4] * c + f[5] * c2, di = f[4] * s + f[5] * s2;
                db += 10 * Math.log10(Math.max(1e-20, nr * nr + ni * ni) / Math.max(1e-20, dr * dr + di * di));
            }
        return db;
    }
}
