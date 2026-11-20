# Android feature guide

**Release builds** · YinYang Companion App for Qudelix 5K 0.9.9 and YinYang App Manager 1.5.2. Hardware editing was tested on the original 5K with firmware 3.2.9 in split USR/SPK mode. Qudelix 5K Plus support is planned.

## What you can do today

| Area | What the original 5K provides | What you can do in YinYang |
| --- | --- | --- |
| Device presets | Stores and loads named presets in hardware | Start on Presets, browse USR presets by name, select one and verify its active slot after reconnect. Both editor tabs show their named device slots. Rename a saved custom preset independently of filter edits. |
| Parametric EQ | USR EQ and independent left/right SPK filters | See frequency-response curves, edit filter type, frequency, gain, Q and preamp, preview temporary changes and explicitly confirm a save. Restore the saved EQ and verify read-back. Choose Qudelix or YinYang stage names. |
| Files and pairs | Accepts settings for each hardware stage | Import and export Qudelix-format PEQs, rename files and pair a listening EQ with its Channel Match on the phone. Reopen a pair, recall a linked match, or replace either stage independently. |
| Earpiece swap | Reverses the DAC’s stereo output | Swap audio routing and the two Channel Match corrections in one action. The Companion exchanges all ten filters and the preamp per side, reads the result after reconnect and can resume an interrupted swap. Imported files keep their original earpiece order. |
| Listening comparisons | Applies EQ to audio passing through the 5K | Audition A/B on the device. Blind rounds randomize the assignment and hide names and curves until a favorite is chosen. Loudness matching defaults on and attenuates the modeled EQ responses. Actual SPL depends on the transducer, fit and program material. |
| Volume and status | Reports hardware gain, stream state, battery and charging information | Adjust the 5K and Android media volume separately. Read the active input, PCM or Bluetooth codec, bit depth, rate and available bitrate. USB bitrate is calculated PCM payload. See battery state, charging and the configured charging limit. |
| USB and microphone | Offers platform-specific sample rates, inline or built-in mic, gain and pass-through | Choose supported USB profiles and microphone options from Audio. |
| YinYang Lab | The 5K supplies audio output and EQ | Open My Collection and Personal Tuning inside the app. Headphone search is part of Tuning. Web pages require a connection and offer a retry if unreachable. |
| Android interface | The 5K runs the active EQ independently of its controller | Choose Blue, YinYang Green, AMOLED or Light. Use Presets, EQ, Audio and Lab as the main destinations. |
| Diagnostics | Reports its active configuration over the control link | Export app debug logs and active EQ snapshots. Run offline simulations or a reversible 1 dB test on a disposable preset, including restoration of the original selection after reconnect. |
| Installation and updates | Firmware is managed separately | App Manager checks automatically and offers alerts for the apps you choose. Download and verify one update or all available updates for installed apps. Open each APK from Android Files and confirm in Android's installer. |

Qudelix documents its hardware EQ, independent channels and custom saves. The YinYang columns describe what this release implements. [Qudelix EQ documentation](https://www.qudelix.com/blogs/5k-dac-amp/equalizer)

## Virtual audio mixing · research

The original 5K selects one incoming playback source at a time. The current Companion sends controls and reads status. It has no audio mixer. The proposed design would bring eligible sources into one host, mix PCM there and send one finished stream to the 5K. Direct console USB into the 5K plus direct phone Bluetooth still uses the device’s source selection. [Qudelix support on concurrent USB and Bluetooth connections](https://forum.qudelix.com/post/windows-bluetooth-usb-and-microphone-functioning-wireless-gaming-application-10979316)

Native **mic pass-through** can blend ambient microphone sound with playback. Its purpose is hearing the room or your voice during listening. A host mixer would take two independent program streams. [Qudelix 5K user manual](https://support-qudelix.github.io/Qudelix5K_User_Manual.pdf)

- **Console and podcast.** A candidate path is console audio delivered to the mixing host by a supported stream or capture interface, plus a locally played podcast, then one USB or Bluetooth output. Console capture, end-to-end delay and synchronization still need testing.
- **Voice chat and music.** Some apps can coexist through system audio routing. Android’s standard playback-capture API restricts eligible usages and excludes voice-communication playback. A controlled chat source or a host that already owns both streams would need a separate test for listening, microphone return and echo. [Android playback capture](https://developer.android.com/media/platform/av-capture) · [Android audio usage restrictions](https://developer.android.com/reference/android/media/AudioAttributes)
