package com.yinyangeq.qudelixprobe;

final class ResponseGraphTest {
    private static final int MID=91;
    public static void main(String[] args) {
        StringBuilder file=new StringBuilder("Frequency,Left(dB),Right(dB)\n");
        for(int i=0;i<=10;i++)file.append(20*Math.pow(1000,i/10.0)).append(",83,75\n");
        ResponseCurve[] ears=ResponseCurve.parseText(file.toString(),true);
        check(ears.length==2 && ears[0].at(1000)==83 && ears[1].at(1000)==75,"stereo file and interpolation");
        check(Double.isNaN(ears[0].at(10)),"measurement does not extrapolate");
        ResponseCurve goal=ResponseCurve.parseText(file.toString(),false)[0];
        ResponseCurve.Points narrow=new ResponseCurve.Points();
        for(int i=0;i<=10;i++)narrow.add(500+i*10,90);
        double anchor=ResponseCurve.reference(narrow.build(),goal);
        check(anchor>500 && anchor<600,"alignment uses the common range");

        PeqResponse main=response(-3);
        PeqResponse[] matching={response(-4),response(4)};
        ResponseTraces before=new ResponseTraces(ResponseTraces.Stage.MATCH,null,matching,ears[0],ears[1],goal,false);
        ResponseTraces after=new ResponseTraces(ResponseTraces.Stage.MATCH,null,matching,ears[0],ears[1],goal,true);
        check(before.lines[0][MID]==4 && before.lines[1][MID]==-4,"deviation from fixed average");
        check(after.lines[0][MID]==0 && after.lines[1][MID]==0 && after.amplitude==before.amplitude,"same scale before and after");
        ResponseTraces pair=new ResponseTraces(ResponseTraces.Stage.PAIR,main,
                new PeqResponse[]{response(-1),response(3)},ears[0],ears[1],goal,true);
        check(pair.target && pair.lines[1][MID]==0 && pair.lines[0][MID]==-2,"pair applies both stages with a level-aligned target");
        ResponseTraces fallback=new ResponseTraces(ResponseTraces.Stage.MATCH,null,matching,ears[0],null,goal,false);
        check(!fallback.measured && fallback.lines[0][MID]==-4 && fallback.lines[1][MID]==4,"mono uses filter correction");
        System.out.println("Measured response, paired filters, target alignment and fixed before/after scale passed.");
    }
    private static PeqResponse response(double preamp){return new PeqResponse(preamp,48000,new double[0][]);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
