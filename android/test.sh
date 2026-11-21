#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
javac --release 8 -Xlint:-options -d "$scratch" "$root/src/DeviceProfile.java" "$root/src/UsbMode.java" "$root/src/ConsolePresence.java" "$root/src/UsbAudioSettings.java" "$root/src/ChannelRouting.java" "$root/src/BlindRound.java" "$root/src/PeqComparison.java" "$root/src/PeqResponse.java" "$root/src/Peq.java" "$root/src/PeqSession.java" "$root/src/DeviceLevels.java" "$root/src/SignalReading.java" "$root/src/DacSettings.java" "$root/src/EqEditHistory.java" "$root/src/PresetDirectory.java" "$root/src/PresetDiff.java" "$root/src/GaiaProbe.java" "$root/src/EqSnapshot.java" \
    "$root/src/Simulation.java" "$root/src/FirmwareCompatibility.java" "$root/src/ProbeLog.java" "$root/src/EqTestJournal.java" "$root/src/BatteryEstimate.java" \
    "$root/src/ReversibleEqTest.java" "$root/test/WorkbenchTest.java" "$root/test/ProbeTest.java" "$root/test/ReversibleEqTestTest.java" "$root/test/PresetPickerTest.java"
java -cp "$scratch" com.yinyangeq.qudelixprobe.ProbeTest
java -cp "$scratch" com.yinyangeq.qudelixprobe.ReversibleEqTestTest
java -cp "$scratch" com.yinyangeq.qudelixprobe.PresetPickerTest
java -cp "$scratch" com.yinyangeq.qudelixprobe.WorkbenchTest
