package com.yinyangeq.qudelixprobe;

import java.security.SecureRandom;

/** Only verified auditions count towards revealing a blind round. */
final class BlindRound {
    private static final SecureRandom random = new SecureRandom();
    final boolean swapped;
    int heardMask, favorite;
    BlindRound(boolean swapped, int heardMask, int favorite) {
        this.swapped = swapped;
        this.heardMask = heardMask;
        this.favorite = favorite;
    }
    static BlindRound start() { return new BlindRound(random.nextBoolean(),0,-1); }
    int source(int letter) { return swapped ? 1-letter : letter; }
    boolean hidden() { return favorite < 0; }
    void verified(int letter) { heardMask |= 1 << letter; }
    void pick(int letter) {
        if (!hidden() || heardMask != 3 || (letter != 0 && letter != 1)) throw new IllegalStateException();
        favorite = letter;
    }
}
