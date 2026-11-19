# Scooter Cockpit (`com.scooter.cockpit`)

A dedicated **Android 4.0.4 (API 15)** smart scooter cockpit and telemetry dashboard built for an **Alcatel OneTouch Evo 7** tablet mounted on a **72V electric scooter (Keeway X-Road conversion)**.

---

## What We Have Done

### 1. Core Dashboard & Navigation (`MainActivity.java`)
- **Custom Home Launcher & Kiosk Mode:** Configured as an Android `HOME` launcher in landscape orientation with `FLAG_KEEP_SCREEN_ON`, day/night brightness toggling, and `DevicePolicyManager` screen sleep/wake controls.
- **Digital Gauge Cluster:** Real-time speedometer, custom `RpmArcView` tachometer, gear indicator (`P` / `D` / `1-3`), battery voltage readout, riding mode selector (`ECO` / `CITY` / `SPORT`), and live clock/ride timer.
- **Trip & Odometer Persistence (`CockpitDbHelper`):** SQLite-backed Trip A, average speed, and persistent odometer tracking with long-press reset.
- **Offline Egypt Map & GPS Navigation (`osmdroid 5.6.5`):** Offline `egypt.sqlite` tile archive loader with a custom `XYTileSource` (bypassing API 15 `BitmapPool` decoding issues), live GPS marker tracking, and destination search/routing.
- **Background Dashcam Loop Recorder (`DashcamRecorder`):** Continuous background video recording with an API 15-compatible `160x120` preview surface.

### 2. Motor Controller Serial Telemetry (`ControllerSerialReader.java`)
- **API 15 USB-OTG Serial Support:** Integrated `usb-serial-for-android:3.4.6` (`CP210x`, `CH340`, `FTDI`, `PL2303`) with `tools:overrideLibrary` manifest merger overrides and automatic USB attach/detach/permission handling.
- **Multi-Protocol Parser (`9600 baud, 8N1`):**
  - **UART Protocol No. 2** (`0x02 0x0E`, 14-byte frames with XOR checksum validation).
  - **Kunteng (KT) Protocol** (`0x41`, 12-byte frames).
  - **Raw Hex Fallback Inspector:** Captures and displays unknown/custom hex packets on screen and in Logcat (`ControllerSerial`) so new protocols can be reverse-engineered live.
- **UI Protection:** Added a `2.5s` packet timeout guard in `MainActivity` so live serial values are never overwritten by fallback defaults while packets are streaming.

### 3. Audio Receiver & Media Panel (`BluetoothSpeakerPanel.java`)
- **Centered Bluetooth & Music UI:** Built an overlay panel triggered from the dock (`BT` / `MUSIC`) with Bluetooth power toggle, device discovery/pairing, and AVRCP Now-Playing track/artist/album metadata.
- **Root-Free Wi-Fi Speaker Streaming (`ServerSocket` + `AudioTrack`):** Added a TCP PCM audio receiver on port `7355` to bypass stock Android A2DP Sink restrictions, allowing a Linux laptop to stream music directly to the tablet speaker over Wi-Fi/hotspot:
  ```bash
  parec -d @DEFAULT_MONITOR@ --format=s16le --rate=44100 --channels=2 | nc -N <TABLET_IP> 7355
