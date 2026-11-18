# Android feature guide

**Reviewed builds** · YinYang Companion App for Qudelix 5K 0.6.0 and YinYang APK Manager 1.1.1. Hardware editing was tested on the original 5K with firmware 3.2.9 in split USR/SPK mode. Qudelix 5K Plus support is planned.

## What you can do today

| Area | What the original 5K provides | What you can do in YinYang |
| --- | --- | --- |
| Device presets | Stores and loads named presets in hardware | Start on Presets, browse USR presets by name, select one and verify its active slot after reconnect. Both editor tabs show their named device slots. Rename a saved custom preset independently of filter edits. |
| Parametric EQ | USR EQ and independent left/right SPK filters | See frequency-response curves, edit filter type, frequency, gain, Q and preamp, preview temporary changes and explicitly confirm a save. Restore the saved EQ and verify read-back. Choose Qudelix or YinYang stage names. |
| Files and pairs | Accepts settings for each hardware stage | Import and export Qudelix-format PEQs, rename files and pair a listening EQ with its Channel Match on the phone. Reopen a pair, recall a linked match, or replace either stage independently. |
| Earpiece swap | Reverses the DAC’s stereo output | Swap audio routing and the two Channel Match corrections in one action. The Companion exchanges all ten filters and the preamp per side, reads the result after reconnect and can resume an interrupted swap. Imported files keep their original earpiece order. |
| Listening comparisons | Applies EQ to audio passing through the 5K | Audition A/B on the device. Blind rounds randomize the assignment and hide names and curves until a favorite is chosen. Loudness matching defaults on and attenuates the modeled EQ responses. Actual SPL depends on the transducer, fit and program material. |
| Volume and status | Reports hardware gain, stream state, battery and charging information | Adjust the 5K and Android media volume separately. Read the active input, PCM or Bluetooth codec, bit depth, rate and available bitrate. USB bitrate is calculated PCM payload. See battery state, charging and the configured charging limit. |
| YinYang Lab | The 5K supplies audio output and EQ | Open My Collection, searchable headphones and Personal Tuning inside the app. Web pages require a connection and offer a retry if unreachable. |
| Android interface | The 5K runs the active EQ independently of its controller | Choose Blue, YinYang Green, AMOLED or Light. Use Presets, EQ, Audio and Lab as the main destinations. |
| Diagnostics | Reports its active configuration over the control link | Export app debug logs and active EQ snapshots. Run offline simulations or a reversible 1 dB test on a disposable preset, including restoration of the original selection after reconnect. |
| Installation and updates | Firmware is managed separately | APK Manager checks for Lab app updates, downloads full APKs or compatible smaller patches, verifies the final package and signer, and opens Android’s install confirmation. The paired personal Manager also tracks its configured Sharur Downmix and ALaCarte builds. Those apps have separate distribution arrangements. |

Qudelix documents its hardware EQ, independent channels and custom saves. The YinYang columns describe what this release implements. [Qudelix EQ documentation](https://www.qudelix.com/blogs/5k-dac-amp/equalizer)

## Virtual audio mixing · research

The original 5K selects one incoming playback source at a time. The current Companion sends controls and reads status. It has no audio mixer. The proposed design would bring eligible sources into one host, mix PCM there and send one finished stream to the 5K. Direct console USB into the 5K plus direct phone Bluetooth still uses the device’s source selection. [Qudelix support on concurrent USB and Bluetooth connections](https://forum.qudelix.com/post/windows-bluetooth-usb-and-microphone-functioning-wireless-gaming-application-10979316)

- **Console and podcast.** A candidate path is console audio delivered to the mixing host by a supported stream or capture interface, plus a locally played podcast, then one USB or Bluetooth output. Console capture, end-to-end delay and synchronization still need testing.
- **Voice chat and music.** Some apps can coexist through system audio routing. Android’s standard playback-capture API restricts eligible usages and excludes voice-communication playback. A controlled chat source or a host that already owns both streams would need a separate test for listening, microphone return and echo. [Android playback capture](https://developer.android.com/media/platform/av-capture) · [Android audio usage restrictions](https://developer.android.com/reference/android/media/AudioAttributes)

## 711 coupler and REW · proposed single-interface setup

The 5K already supports simultaneous USB playback and headset-mic capture at 44.1 or 48 kHz. That makes a setup with the **5K as the only USB audio interface** plausible. A suitable headset splitter and a compatible coupler microphone are still necessary. [Qudelix headset-mic setup](https://forum.qudelix.com/post/is-it-possible-to-use-the-microphone-in-usb-mode-12354187) · [Qudelix USB microphone formats](https://forum.qudelix.com/post/usb-mic-support-11121115)

| Connection | Signal |
| --- | --- |
| REW computer ↔ 5K USB | Playback to the 5K and microphone return to REW |
| 5K 3.5 mm headset jack → splitter headphone socket → IEM | Measurement sweep, one earpiece at a time |
| Sealed IEM → coupler mic → splitter mic socket → 5K | Acoustic response captured by the external mic |

Use a headset breakout with separate headphone and mic sockets. Match the plug contacts, mic bias and input level to the particular microphone. A CTIA plug carries left, right, ground and mic in that order. A stereo headphone duplicator provides two playback connections. Some laboratory 711 systems require a powered preamplifier. [CTIA contact specification](https://source.android.com/docs/core/interaction/accessories/headset/plug-headset-spec) · [Example of a 711 system with a CCP preamplifier](https://www.grasacoustics.com/products/product-pdf/69cc-6)

### Bench check

The Lab guide documents a Type E610A and a working Apple-adapter-plus-splitter reference. On the connected original 5K, the initial USB profile exposed two playback channels at 48 kHz and 24-bit, with no capture endpoint. That observation covers the playback-only profile. The external mic signal and the frequency response of the 5K recording path still require a bench measurement. [Lab’s 711 measurement guide](https://yinyangeq.com/glossary/iem-measurement)

1. With the IEM in the coupler, set **Input → USB → 48 kHz with Mic** on the 5K. Select **Earphone Inline Mic** in the microphone settings. Connect the REW computer over USB and confirm the OS exposes both playback and capture. Reopen the devices in REW after any USB profile change.
2. Select the named 5K output and input in REW, set 48 kHz, choose one output channel and load the calibration file for this coupler if available. Use a low sweep level with the IEM out of your ears. With monitoring and sidetone off, check levels and confirm the meter responds to the coupler. Unplugging its mic leg should remove that response. [REW input/output setup](https://www.roomeqwizard.com/help/help_en-GB/html/calsoundcard.html)
3. Hold the IEM seating, 5K playback path, volume, EQ and mic gain fixed. Record repeated sweeps with the reference input and then with the 5K input. Compare the shapes at matched levels and repeat at a lower level to check for compression, low-frequency roll-off and clipping. Record absolute SPL only with a valid sensitivity calibration. [REW measurement procedure](https://www.roomeqwizard.com/help/help_en-GB/html/makingmeasurements.html) · [REW SPL calibration](https://www.roomeqwizard.com/betahelp/help/html/inputcal.html)

REW runs on the computer in this setup. Android can control the 5K over Bluetooth. An in-app measurement assistant or remote REW controls would be additional development. REW requires its Pro upgrade for automated sweeps through its API. [REW API documentation](https://www.roomeqwizard.com/help/help/html/api.html)
