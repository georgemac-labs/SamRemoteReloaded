# SamRemote

Control your Samsung TV with SamRemote

Note: This App uses WiFI **not Infrared**

Supports Samsung TVs using the legacy TCP protocol or the Tizen WebSocket protocol. Samsung’s intermediate encrypted protocol is not supported.

Connect the phone to the TV’s network and allow Sam Remote on the TV when prompted. Tap the status icon to search for another TV or enter an IPv4 address. One selected TV and its pairing token are remembered.

[<img src="https://f-droid.org/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="90">](https://f-droid.org/packages/mkg20001.net.samremote/)

## Buttons
Navigation: Up, Down, Left, Right
Special: Power Off, Source, HDMI, Channel List, Menu
Navigation: Left, Right, Up, Down, Exit, Return, Enter
Other: Volume Up/Down, Channel Up/Down
Below the directional pad: Back, Home, Play/Pause. On legacy TVs, tap Play/Pause to pause and hold it to play.

## Development

The active build covers the Android phone app, shortcuts and Quick Settings tile. The historical smartwatch source and screenshots remain in the repository; the smartwatch app is not part of this build.

Use JDK 21 and an Android SDK containing the compile SDK specified in [mobile/build.gradle](mobile/build.gradle). Set `JAVA_HOME` and `ANDROID_HOME` for your installation, then run:

```sh
./gradlew :mobile:assembleDebug :samremotecommon:testDebugUnitTest :mobile:lintDebug
```

The APK is written to `mobile/build/outputs/apk/debug/mobile-debug.apk`. Debug builds have a separate application ID, so they can coexist with the F-Droid release. The JVM tests use simulated TVs; physical-device validation is separate.

The shared controller orders logical remote keys and delegates authorization and framing to independent legacy and Tizen transports. Discovery probes candidates without pairing them.

## Historical screenshots
![Nexus 5X (Android N)](/Screenshots/Nexus5X_N.png?raw=true "Nexus 5X")
![Android Wear Round](/Screenshots/Wear_Round_Big.png?raw=true "Android Wear")

[» More Screenshots](/Screenshots/)
