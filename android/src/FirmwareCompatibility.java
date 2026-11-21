package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.ProtocolException;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Runs the existing read commands against an unverified original 5K firmware. */
final class FirmwareCompatibility {
    private FirmwareCompatibility() {}

    private interface Inspection {
        String read(GaiaProbe.Connection link) throws IOException;
    }

    private static final class Results {
        int passed, review, unread, skipped;
        final StringBuilder details = new StringBuilder();

        void record(String status, String feature, String detail) {
            details.append(status).append(" · ").append(feature).append('\n')
                    .append(detail).append("\n\n");
        }

        void inspect(PresetDirectory.Connector connector, GaiaProbe.Device expected,
                     String feature, Inspection check, BooleanSupplier stopped) {
            if (stopped.getAsBoolean()) {
                skipped++;
                record("NOT TESTED",feature,"The check was interrupted.");
                return;
            }
            String finding;
            try (GaiaProbe.Connection link = connector.open()) {
                GaiaProbe.Device actual = link.readDevice();
                if (actual.model != expected.model || !Arrays.equals(actual.firmwareField,expected.firmwareField))
                    throw new IOException("Device or firmware changed during the check.");
                finding = check.read(link);
            } catch (ProtocolException error) {
                review++;
                record("NEEDS REVIEW",feature,detail(error,"The reply did not match the expected protocol."));
                return;
            } catch (IOException | SecurityException error) {
                unread++;
                record("COULD NOT READ",feature,detail(error,"Check the device connection and try again."));
                return;
            }
            if (finding == null) {
                skipped++;
                record("NOT TESTED",feature,"This bank has no named slots to check.");
            } else {
                passed++;
                record("READ PASS",feature,finding);
            }
        }
    }

    static String run(PresetDirectory.Connector connector, BooleanSupplier stopped) {
        String header = "YinYang EQ · Firmware compatibility\nChecked at "+Instant.now()+"\n";
        GaiaProbe.Device device;
        try (GaiaProbe.Connection link = connector.open()) {
            device = link.readDevice();
        } catch (IOException | SecurityException error) {
            return header+"\nCOULD NOT READ · Bluetooth and firmware\n"
                    +detail(error,"Check pairing and close other controllers.")+"\n\nOther checks were not run.";
        }
        header += "Firmware read from device · "+device.version+"\n"
                +"Firmware supported by this build · "+GaiaProbe.EDITOR_FIRMWARE+"\n\n";
        if (device.model != 1)
            return header+"NEEDS REVIEW · Model ID "+device.model
                    +"\nThis check expects an original Qudelix 5K. Other commands were not sent.";
        Results results = new Results();
        results.passed++;
        results.record("READ PASS","Bluetooth and firmware","The original 5K responded with its firmware version.");
        results.inspect(connector,device,"Audio, battery, volume and input priority",link -> {
            DeviceLevels value = link.readLevels();
            if (value.priority < 0)
                throw new ProtocolException("Audio and volume blocks decoded, but the input priority field was unavailable.");
            return "Status and priority decoded. "+(value.playing ? "Playing now · "+value.stream
                    : "Play audio and rerun the check to read the stream format and rate.");
        },stopped);
        results.inspect(connector,device,"USB sample-rate profiles and microphone settings",link -> {
            UsbAudioSettings current = link.readUsbAudio();
            return UsbMode.values()[current.platform].label+" · "+UsbAudioSettings.RATES[current.rate]
                    +". USB and mic settings decoded.";
        },stopped);
        results.inspect(connector,device,"Left and right channel routing",link ->
                link.readChannelRouting() ? "The DAC reports swapped channels." : "The DAC reports normal channels.",stopped);
        for (EqSnapshot.Group group : EqSnapshot.Group.values()) {
            results.inspect(connector,device,group+" active EQ",link -> inspectEq(link,group),stopped);
            results.inspect(connector,device,group+" saved preset names",link -> inspectNames(link,group),stopped);
        }
        return header+results.passed+" read passed · "+results.review+" need review · "+results.unread
                +" could not read · "+results.skipped+" not tested\n\n"
                +results.details
                +"Still to verify on hardware\n"
                +"Preset switching, rename, save, restore and undo. Temporary EQ, blind A/B and channel swap. "
                +"Volume and input priority changes. USB profile changes and PS5 switching. "
                +"Actual speaker and headphone routing needs a playback test. "
                +"This check sends no setting changes and does not unlock controls on unverified firmware.";
    }

    private static String inspectEq(GaiaProbe.Connection link, EqSnapshot.Group group) throws IOException {
        byte[] before = eqConfiguration(link);
        byte[] block = EqSnapshot.readGroup(link,group);
        checkUnchanged(link,before);
        Peq eq = new Peq(group.id,block);
        if (!eq.parametric()) return block.length+" bytes read. Load a parametric preset to check PEQ fields.";
        for (int ch = 0; ch < eq.channels; ch++) {
            for (int filter = 0; filter < 10; filter++) {
                if (eq.type(ch,filter) >= Peq.TYPES.length)
                    throw new ProtocolException("The EQ block contains a filter type this editor does not recognize.");
            }
        }
        return block.length+" bytes read. The current PEQ fields decoded.";
    }

    private static String inspectNames(GaiaProbe.Connection link, EqSnapshot.Group group) throws IOException {
        byte[] before = eqConfiguration(link);
        String[] names = PresetDirectory.readBank(link,before,group.id);
        checkUnchanged(link,before);
        int count = 0;
        for (String name : names) if (name != null) count++;
        return count == 0 ? null : count+" saved slot "+(count == 1 ? "name read." : "names read.");
    }

    private static byte[] eqConfiguration(GaiaProbe.Connection link) throws IOException {
        byte[] config = link.readEqConfiguration();
        EqSnapshot.checkConfiguration(config);
        return config;
    }

    private static void checkUnchanged(GaiaProbe.Connection link, byte[] before) throws IOException {
        if (!Arrays.equals(before,eqConfiguration(link)))
            throw new IOException("EQ configuration changed mid-check. Keep other controllers closed and retry.");
    }

    private static String detail(Exception error, String fallback) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) return fallback;
        String clean = message.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}","[device]").replaceAll("\\p{Cntrl}"," ");
        return clean.substring(0,Math.min(clean.length(),220));
    }
}
