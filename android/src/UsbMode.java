package com.yinyangeq.qudelixprobe;

/** Mode indices in both Qudelix USB protocols. */
enum UsbMode {
    NORMAL("Normal"), PLAYSTATION("PS4 / PS5"), SWITCH("Nintendo Switch");
    final String label;
    UsbMode(String label){this.label=label;}
    static boolean supported(int index){return index>=0 && index<=SWITCH.ordinal();}
}
