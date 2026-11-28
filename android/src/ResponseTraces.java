package com.yinyangeq.qudelixprobe;

final class ResponseTraces {
    enum Stage { MAIN, MATCH, PAIR }

    final float[][] lines;
    final float amplitude;
    final boolean target, measured;

    ResponseTraces(Stage stage, PeqResponse main, PeqResponse[] ears,
                   ResponseCurve left, ResponseCurve right, ResponseCurve goal, boolean after) {
        boolean stereo=left!=null && right!=null && Double.isFinite(ResponseCurve.reference(left,right));
        measured=stage==Stage.MATCH?stereo:left!=null;
        target=stage!=Stage.MATCH && measured && goal!=null
                && Double.isFinite(ResponseCurve.reference(left,stereo?right:null,goal));
        double anchor=target?ResponseCurve.reference(left,stereo?right:null,goal)
                :ResponseCurve.reference(left,stereo?right:null);
        double measurementLevel=measured && stage!=Stage.MATCH?level(left,right,stereo,anchor):0;
        double targetLevel=target?goal.at(anchor):0;
        lines=new float[stage==Stage.MATCH || target?2:1][161];
        double peak=6;
        for(int index=0;index<lines[0].length;index++){
            double frequency=frequency(index);
            double a=ears.length>0?response(ears[0],frequency):0;
            double b=ears.length>1?response(ears[1],frequency):a;
            if(stage==Stage.MATCH){
                double difference=measured?(left.at(frequency)-right.at(frequency))/2:0;
                lines[0][index]=(float)(difference+(after || !measured?a:0));
                lines[1][index]=(float)(-difference+(after || !measured?b:0));
                peak=max(peak,difference);
                peak=max(peak,difference+a);
                peak=max(peak,-difference+b);
            }else{
                double source=measured?level(left,right,stereo,frequency)-measurementLevel:0;
                double correction=response(main,frequency)+(stage==Stage.PAIR?ResponseCurve.average(a,b):0);
                lines[0][index]=(float)(source+correction);
                peak=max(peak,lines[0][index]);
                if(target){
                    lines[1][index]=(float)(goal.at(frequency)-targetLevel);
                    peak=max(peak,lines[1][index]);
                }
            }
        }
        amplitude=(float)(6*Math.ceil(peak/6));
    }

    static double frequency(int point) { return point==160?20000:20*Math.pow(1000,point/160.0); }

    private static double level(ResponseCurve left,ResponseCurve right,boolean stereo,double hz){
        return stereo?ResponseCurve.average(left.at(hz),right.at(hz)):left.at(hz);
    }
    private static double response(PeqResponse eq,double frequency){ return eq==null?0:eq.db(frequency); }
    private static double max(double peak,double point){ return Double.isFinite(point)?Math.max(peak,Math.abs(point)):peak; }
}
