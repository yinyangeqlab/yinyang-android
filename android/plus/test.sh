#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
javac -d "$scratch" "$root/../src/BlindRound.java" "$root/../src/UsbMode.java" "$root/../src/Peq.java" "$root/../src/PeqResponse.java" \
  "$root/src/PlusPreset.java" "$root/src/PlusUsbProtocol.java" "$root/src/PlusApply.java" "$root/src/PlusBlindRound.java" "$root/src/PlusChannelSwap.java" "$root/src/PlusBlindLevel.java" "$root/src/PlusGaia.java" "$root/src/PlusLevels.java" "$root/src/PlusLibrary.java" "$root"/test/*.java
java -cp "$scratch" com.yinyangeq.qudelixprobe.PlusTest
