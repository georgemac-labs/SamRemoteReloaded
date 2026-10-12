# SamRemote Reloaded

Control your Samsung TV over Wi-Fi from your Android phone.

- Supports Samsung TVs using the legacy TCP protocol or the modern Tizen WebSocket protocol.
- Finds TVs automatically, with TV selection and manual IP entry.
- Remembers the selected TV and pairing approval.
- Provides navigation, volume, channel, power, source, Home and playback controls.
- Supports launcher shortcuts and a Quick Settings remote.

Samsung’s intermediate encrypted protocol and smartwatch apps are not supported.

Connect the phone to the TV’s network and approve the connection on the TV when prompted. Tap the status icon to search for another TV or enter an IPv4 address.

Back, Home and Play/Pause sit around the bottom of the directional pad. On legacy TVs, tap Play/Pause to pause and hold it to play.

## Development

Use JDK 21 and an Android SDK containing the compile SDK specified in [mobile/build.gradle](mobile/build.gradle). Set `JAVA_HOME` and `ANDROID_HOME` for your installation, then run:

```sh
./gradlew :mobile:assembleDebug :samremotecommon:testDebugUnitTest :mobile:lintDebug
```

The APK is written to `mobile/build/outputs/apk/debug/mobile-debug.apk`. Debug builds have a separate application ID, so they can coexist with release builds. The JVM tests use simulated TVs; physical-device validation is separate.

The shared controller orders logical remote keys and delegates authorization and framing to independent legacy and Tizen transports. Discovery probes candidates without pairing them.
