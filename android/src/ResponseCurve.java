package com.yinyangeq.qudelixprobe;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** Frequency response samples, interpolated on the logarithmic frequency axis. */
final class ResponseCurve {
    final double[] frequencies, levels;
    private ResponseCurve(double[] frequencies, double[] levels) {
        this.frequencies = frequencies;
        this.levels = levels;
    }

    double at(double hz) {
        if (hz < frequencies[0] || hz > frequencies[frequencies.length - 1]) return Double.NaN;
        int index = java.util.Arrays.binarySearch(frequencies,hz);
        if (index >= 0) return levels[index];
        int next = -index - 1;
        double t = Math.log(hz / frequencies[next - 1]) / Math.log(frequencies[next] / frequencies[next - 1]);
        return levels[next - 1] + (levels[next] - levels[next - 1]) * t;
    }

    double first() { return frequencies[0]; }
    double last() { return frequencies[frequencies.length - 1]; }

    static final class Points {
        private final TreeMap<Double,Double> points = new TreeMap<>();
        void add(double hz, double db) {
            if (Double.isFinite(hz) && Double.isFinite(db) && hz>=20 && hz<=20000 && Math.abs(db)<=400)
                points.putIfAbsent(hz, db);
        }
        ResponseCurve build() {
            Peq.require(points.size()>=10,"Use at least 10 frequency and dB rows from 20 Hz to 20 kHz.");
            double[] hz = new double[points.size()], db = new double[points.size()];
            int i=0;
            for (Map.Entry<Double,Double> point : points.entrySet()) {
                hz[i] = point.getKey();
                db[i++] = point.getValue();
            }
            ResponseCurve original = new ResponseCurve(hz, db);
            if (hz.length<=512) return original;
            hz = new double[512];
            db = new double[512];
            for (i=0;i<hz.length;i++) {
                hz[i] = i==hz.length-1 ? original.last() : original.first()*Math.pow(original.last()/original.first(),i/511.0);
                db[i] = original.at(hz[i]);
            }
            return new ResponseCurve(hz, db);
        }
    }

    static ResponseCurve[] parseText(String text, boolean stereoAllowed) {
        Points left=new Points(), right=new Points();
        int hz=0, l=1, r=-1;
        for (String raw : text.split("\\R")) {
            String line=raw.trim().replaceFirst("^[#*]\\s*", "");
            if(line.isEmpty())continue;
            String[] cells=line.replace("\"", "").split("[,;\\s]+");
            try {
                if(cells.length<=Math.max(hz,Math.max(l,r)))continue;
                double frequency=Double.parseDouble(cells[hz]);
                double a=Double.parseDouble(cells[l]);
                double b=r<0?Double.NaN:Double.parseDouble(cells[r]);
                if(r<0 || Double.isFinite(b)) left.add(frequency,a);
                if(r>=0 && Double.isFinite(a)) right.add(frequency,b);
            } catch (NumberFormatException header) {
                if(!stereoAllowed || r>=0)continue;
                int frequency=-1, leftColumn=-1, rightColumn=-1;
                for(int column=0;column<cells.length;column++) {
                    String token=cells[column].toLowerCase(java.util.Locale.ROOT);
                    if(token.equals("hz") || token.startsWith("freq"))frequency=column;
                    if(token.equals("l") || token.startsWith("left"))leftColumn=column;
                    if(token.equals("r") || token.startsWith("right"))rightColumn=column;
                }
                if(frequency>=0 && leftColumn>=0 && rightColumn>=0){
                    hz=frequency;l=leftColumn;r=rightColumn;
                }
            }
        }
        return r<0 ? new ResponseCurve[]{left.build()} : new ResponseCurve[]{left.build(),right.build()};
    }

    static double reference(ResponseCurve... curves) {
        double low=20, high=20000;
        for(ResponseCurve curve:curves) if(curve!=null){
            low=Math.max(low,curve.first());
            high=Math.min(high,curve.last());
        }
        if(high<=low)return Double.NaN;
        return 1000>=low && 1000<=high ? 1000 : Math.sqrt(low*high);
    }

    static double average(double a, double b) { return (a+b)/2; }
}
