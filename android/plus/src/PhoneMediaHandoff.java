package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.view.KeyEvent;
import java.util.List;

/** Media keys are scoped to the phone's current route to the linked DAC. */
final class PhoneMediaHandoff {
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();
    private final AudioManager audio;

    PhoneMediaHandoff(Context context) {
        audio = context.getSystemService(AudioManager.class);
    }

    boolean send(String address, int transport, boolean pause) {
        if (audio == null || transport < 0 || routedTransport(address) != transport
                || (pause && !audio.isMusicActive())) return false;
        int key = pause ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            return true;
        } catch (SecurityException unsupported) {
            return false;
        }
    }

    int routedTransport(String address) {
        if (Build.VERSION.SDK_INT < 33 || audio == null || address == null || address.isEmpty()
                || "02:00:00:00:00:00".equals(address) || audio.getMode() != AudioManager.MODE_NORMAL) return -1;
        List<AudioDeviceInfo> outputs;
        try {
            outputs=audio.getAudioDevicesForAttributes(MEDIA);
        } catch (SecurityException unsupported) {
            return -1;
        }
        if (outputs.size() != 1) return -1;
        AudioDeviceInfo output = outputs.get(0);
        if (!address.equalsIgnoreCase(output.getAddress())) return -1;
        switch (output.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return 0;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return 1;
            default: return -1;
        }
    }
}
