# Scooter Cockpit
### Smart Electric Scooter Dashboard & Telemetry System

**Android Package:** `com.scooter.cockpit`  
**Target Device:** Alcatel OneTouch Evo 7  
**Operating System:** Android 4.0.4 (API 15)  
**Vehicle:** 72V Electric Scooter — Keeway X-Road Conversion

---

## 1. Project Overview

Scooter Cockpit is a dedicated Android-based smart dashboard designed to transform an Alcatel OneTouch Evo 7 tablet into an integrated electric scooter cockpit.

The system provides a custom digital instrument cluster, GPS navigation, trip tracking, motor controller telemetry, and multimedia functionality.

The tablet operates as the primary user interface, while external hardware and communication interfaces provide access to vehicle data.

### Main Objectives

- Replace the conventional dashboard with a customizable digital cockpit.
- Retrieve real-time telemetry from the motor controller.
- Monitor battery voltage, speed, motor performance, and vehicle status.
- Provide offline GPS navigation and trip tracking.
- Integrate multimedia and audio streaming functionality.
- Maintain compatibility with the tablet's legacy Android 4.0.4 environment.

---

# 2. System Architecture

The project consists of three primary layers:

### 2.1 Vehicle Hardware Layer

- Electric motor controller (72V system).
- Battery and Battery Management System (BMS).
- Dashboard communication interface.
- External sensors and vehicle signal inputs.

### 2.2 Communication Layer

- USB OTG.
- USB-to-UART serial adapters.
- ESP32 microcontroller for protocol decoding and signal acquisition.
- UART communication between external hardware and the tablet.

### 2.3 Android Application Layer

- Custom dashboard interface.
- Controller telemetry reader.
- SQLite database.
- GPS navigation.
- Dashcam recorder.
- Bluetooth and Wi-Fi multimedia functionality.

### Data Flow

```text
          ELECTRIC SCOOTER
                 |
        +--------+--------+
        |                 |
   Motor Controller     Battery/BMS
        |
   Communication Bus
        |
   +----+----------------+
   |                     |
Direct USB Serial     ESP32 Decoder
   |                     |
   |                  USB Serial
   |                     |
   +----------+----------+
              |
         USB OTG
              |
      Android Tablet
              |
     Scooter Cockpit App
              |
      +-------+-------+
      |       |       |
   Dashboard SQLite  Logging
      |
   GPS / Multimedia
```

**Note:** Direct USB serial communication is suitable only when the controller exposes a compatible UART interface. Single-wire or proprietary communication protocols may require an ESP32 or another dedicated interface.

---

# 3. Implemented Features

## 3.1 Digital Dashboard & Navigation

**Main Component:** `MainActivity.java`

The application provides a custom digital instrument cluster optimized for landscape orientation and continuous operation.

### Features

- **Custom Home Launcher:** Configured as an Android `HOME` application.
- **Kiosk-Style Operation:** Keeps the display active using `FLAG_KEEP_SCREEN_ON`.
- **Brightness Control:** Day/night display brightness adjustment.
- **Screen Management:** DevicePolicyManager-based screen sleep/wake controls where supported.
- **Digital Speedometer:** Real-time speed display.
- **RPM Tachometer:** Custom `RpmArcView` visualization.
- **Gear Indicator:** Supports `P`, `D`, and gears `1–3`.
- **Battery Monitoring:** Battery voltage readout.
- **Riding Modes:** ECO, CITY, and SPORT.
- **Ride Timer:** Live clock and session timer.

### Trip & Odometer Management

**Component:** `CockpitDbHelper`

SQLite persistence provides:

- Trip A distance tracking.
- Average speed calculation.
- Persistent total odometer.
- Trip reset functionality using long press.
- Data persistence across application restarts.

---

## 3.2 Offline GPS Navigation

**Technology:** `osmdroid 5.6.5`

The navigation system provides offline map functionality without depending on an active internet connection.

### Features

- Offline Egypt map tiles using `egypt.sqlite`.
- Custom `XYTileSource` implementation.
- Live GPS position tracking.
- Custom location marker.
- Destination search.
- Route visualization.

### Compatibility Workaround

A custom tile-loading implementation was introduced to bypass Android API 15 compatibility issues with `BitmapPool` image decoding.

---

## 3.3 Background Dashcam

**Component:** `DashcamRecorder`

A background video recording subsystem provides basic dashcam functionality.

### Features

- Continuous video recording.
- Loop recording architecture.
- Low-resolution preview surface.
- API 15-compatible `160 × 120` preview configuration.

The recording subsystem is designed to operate alongside the dashboard without requiring a separate camera application.

---

## 3.4 Motor Controller Serial Telemetry

**Component:** `ControllerSerialReader.java`

The application integrates USB serial communication to retrieve telemetry from the scooter's motor controller.

### USB Communication

**Library:** `usb-serial-for-android:3.4.6`

Supported USB-to-serial chipsets include:

- CP210x
- CH340
- FTDI
- PL2303

The implementation includes:

- USB device detection.
- USB permission handling.
- Automatic device attachment and detachment handling.
- Serial port initialization.
- Incoming data buffering.
- Protocol parsing.
- Raw hexadecimal packet inspection.

### Serial Configuration

| Parameter | Configuration |
|---|---|
| Baud rate | 9600 |
| Data bits | 8 |
| Parity | None |
| Stop bits | 1 |
| Configuration | 9600 8N1 |

### Implemented Protocol Parsers

**Protocol 1 — UART Protocol No. 2**

- Frame header: `0x02 0x0E`
- Frame length: 14 bytes.
- XOR checksum validation.
- Structured telemetry extraction.

**Protocol 2 — Kunteng (KT)**

- Frame header: `0x41`
- Frame length: 12 bytes.
- Structured packet parsing.

**Protocol 3 — Raw Hex Inspector**

For unknown or proprietary protocols, the application captures raw incoming bytes.

The inspector displays hexadecimal packets in the application and Logcat:

```bash
adb logcat -s ControllerSerial
```

This functionality supports protocol identification and reverse engineering.

### UI Data Protection

A 2.5-second packet timeout guard was implemented in `MainActivity`.

Its purpose is to prevent fallback or simulated dashboard values from overwriting live telemetry while valid serial packets are being received.

---

# 4. Bluetooth & Multimedia System

**Component:** `BluetoothSpeakerPanel.java`

The multimedia subsystem provides Bluetooth device management, media information, and an alternative audio streaming solution.

## 4.1 Bluetooth Management

The interface provides:

- Bluetooth power toggle.
- Nearby device discovery.
- Device pairing.
- Bluetooth device management.
- AVRCP Now-Playing metadata.

Supported media metadata includes:

- Track title.
- Artist.
- Album.

## 4.2 Wi-Fi Audio Streaming

### Why Wi-Fi Streaming Was Implemented

The tablet runs Android 4.0.4, whose stock Bluetooth audio stack does not provide the required A2DP Sink functionality.

Consequently, the tablet cannot natively act as a Bluetooth speaker for an external phone or laptop.

Instead, a custom TCP audio receiver was implemented.

### Architecture

```text
Linux Laptop
     |
PulseAudio Monitor
     |
PCM Audio Stream
     |
TCP Connection
Port: 7355
     |
Android Tablet
     |
ServerSocket
     |
AudioTrack
     |
Tablet Speaker
```

### Linux Streaming Command

```bash
parec -d @DEFAULT_MONITOR@ \
  --format=s16le \
  --rate=44100 \
  --channels=2 \
  | nc -N <TABLET_IP> 7355
```

The Android application receives raw PCM audio through a TCP `ServerSocket` and plays it using `AudioTrack`.

This approach works without root access and bypasses the stock Android Bluetooth audio limitations.

---

# 5. Hardware & Operating System Findings

## 5.1 Android 4.0.4 Bluetooth Limitations

The Alcatel OneTouch Evo 7 runs Android 4.0.4 (Ice Cream Sandwich).

The stock Bluetooth stack does not support the required A2DP Sink functionality.

**Result:**

- Bluetooth audio output is supported.
- Receiving Bluetooth audio as a wireless speaker is not supported by the stock configuration.
- Rooting alone does not guarantee A2DP Sink functionality.

**Current Solution:** Continue using the custom Wi-Fi TCP audio receiver.

**Future Upgrade:** Consider a newer Android device or head unit with confirmed A2DP Sink support.

## 5.2 USB-to-Serial Electrical Compatibility

A USB cable cannot be connected directly to the controller's UART TX/RX pins.

USB and UART use different electrical signaling methods:

| Interface | Signaling |
|---|---|
| USB | Differential D+ / D− |
| UART | TX / RX / GND |
| Typical UART logic | 3.3V or 5V |

A USB-to-UART bridge is required to convert between these interfaces.

Examples:

- CP2102
- CH340
- FT232

**Critical safety requirements:**

- Never connect controller power wires to USB data pins.
- Never connect the scooter's 72V supply directly to the ESP32 or tablet.
- Verify signal voltage before connecting any serial interface.
- Use appropriate voltage-level conversion.
- Use galvanic isolation where required.
- Verify grounding and signal references before testing.

The controller's communication voltage must be measured and verified. Do not assume that every signal wire operates at 3.3V or 5V.

---

# 6. Controller Communication Investigation

Before implementing the final telemetry integration, the controller-to-display communication interface must be identified.

The scooter may use standard UART, CAN bus, or a proprietary single-wire communication protocol.

## 6.1 Standard UART vs. Single-Wire Communication

| Characteristic | Standard UART | Single-Wire / Proprietary |
|---|---|---|
| Data wires | TX and RX, or TX only for monitoring | One shared data line |
| Communication | Asynchronous serial | Protocol-dependent |
| Decoding | UART serial receiver | Dedicated timing/protocol decoder may be required |
| USB serial compatibility | Yes, if electrically compatible | Not necessarily |
| ESP32 requirement | Optional | Often useful |

A single data wire does not automatically mean YXT or another specific protocol. The protocol must be identified from the actual hardware and captured signals.

## 6.2 Inspection Procedure

1. Locate the motor controller and identify its manufacturer and model.
2. Locate the connector between the controller and the original display.
3. Record the number of wires and connector pinout.
4. Identify power, ground, and communication signals.
5. Inspect controller labels and available documentation.
6. Measure signal levels using suitable equipment.
7. Capture communication traffic without disrupting the original display.
8. Analyze packet structure, timing, and checksums.

### Expected UART Characteristics

For a standard UART interface:

- Separate TX and RX signals may be present.
- The idle state is typically logic HIGH.
- Repeating packets may appear at a fixed baud rate.
- A compatible USB-to-UART adapter can receive the data.

### Expected Single-Wire Characteristics

For a proprietary single-wire interface:

- One shared communication signal may be present.
- Timing and signaling may differ from standard UART.
- A conventional USB-to-UART adapter may produce unreadable data.
- A microcontroller may be needed to decode the protocol.

**Important:** A multimeter can help identify voltage levels, but an oscilloscope or logic analyzer is preferable for determining the actual signaling method.

---

# 7. ESP32 Integration Strategy

The ESP32 is intended to serve as an external communication and telemetry processor when direct USB serial access is insufficient.

## 7.1 Proposed Architecture

```text
       Scooter Controller
               |
       Communication Bus
               |
       +-------+-------+
       |               |
   Standard UART   Proprietary Bus
       |               |
   USB-UART       ESP32 Decoder
       |               |
       |           USB Serial
       |               |
       +-------+-------+
               |
          USB OTG
               |
         Android Tablet
               |
       ControllerSerialReader
               |
       Telemetry Parser
               |
          MainActivity
               |
         Digital Dashboard
```

## 7.2 ESP32 Responsibilities

Depending on the controller interface, the ESP32 may:

- Capture UART communication.
- Decode proprietary single-wire signals.
- Measure external digital inputs.
- Acquire additional sensor readings.
- Read isolated analog measurements.
- Convert telemetry into a standardized serial format.
- Forward telemetry to the Android tablet through USB.

### Important Implementation Note

The ESP32 must not interfere with the original controller-to-display communication.

For initial development, prioritize passive monitoring. Any connection that requires transmitting commands onto the controller bus must be independently investigated and validated.

## 7.3 Proposed Hardware

**Initial development board:**

ESP32-WROOM-32, 38-pin development board with CP2102 USB-to-UART bridge.

Potential additional hardware:

- Appropriate voltage-level shifters.
- Isolated UART interface, if necessary.
- CAN transceiver, if the controller uses CAN.
- Voltage divider with appropriate protection and isolation for battery measurements.
- External ADC where additional analog accuracy is required.

The selected hardware must match the actual controller's electrical interface.

---

# 8. Current Development Roadmap

## Phase 1 — Controller Identification

- [ ] Locate and photograph the motor controller.
- [ ] Record manufacturer, model, voltage, and current ratings.
- [ ] Identify the original display connector.
- [ ] Document connector pinout and wire functions.
- [ ] Determine whether communication uses UART, CAN, or a proprietary protocol.
- [ ] Obtain available controller documentation.

## Phase 2 — Communication Testing

- [ ] Verify communication signal voltage.
- [ ] Identify the correct electrical interface.
- [ ] Test passive serial reception.
- [ ] Capture raw hexadecimal packets.
- [ ] Identify packet headers and frame lengths.
- [ ] Determine checksums and data fields.
- [ ] Validate decoded values against the original dashboard.

## Phase 3 — ESP32 Integration

- [ ] Purchase a suitable ESP32 development board.
- [ ] Implement the required communication interface.
- [ ] Decode controller telemetry where necessary.
- [ ] Design a standardized serial output protocol.
- [ ] Test USB communication with the Android tablet.
- [ ] Verify stable operation during scooter startup and shutdown.

## Phase 4 — Live Dashboard Integration

**Files:** `MainActivity.java` and `ControllerSerialReader.java`

Replace simulated or hardcoded values with validated telemetry.

Target variables:

| Variable | Description |
|---|---|
| `speed` | Vehicle speed in km/h |
| `rpm` | Motor RPM |
| `volts` | Battery voltage |
| `current` | Battery or motor current |
| `fuelPct` | Estimated battery percentage |
| `gear` | Current gear or riding state |
| `controllerErrorCode` | Controller error code |
| `motorTemp` | Motor temperature, if available |
| `controllerTemp` | Controller temperature, if available |
| `leftBlinkerOn` | Left indicator status |
| `rightBlinkerOn` | Right indicator status |
| `ridingMode` | ECO / CITY / SPORT |

### Speed & RPM Calibration

Set the actual wheel circumference:

```java
WHEEL_CIRCUMFERENCE_M = /* measured circumference */;
```

For speed calculations:

```text
Speed (km/h) = Wheel RPM × Circumference (m) × 60 / 1000
```

For a hub motor:

```text
Motor RPM ≈ Wheel RPM × Gear Ratio
```

For a direct-drive hub motor, the gear ratio is generally 1:1.

Controller-provided speed or RPM should be preferred when its measurement and scaling have been validated.

## Phase 5 — Android OS Optimization

Optimize the Alcatel Evo 7 for dedicated cockpit operation.

Tasks:

- [ ] Identify unnecessary system applications.
- [ ] Disable unused background services.
- [ ] Remove unnecessary startup applications.
- [ ] Reduce background synchronization.
- [ ] Optimize available RAM.
- [ ] Review storage usage.
- [ ] Improve boot time.
- [ ] Preserve USB OTG, GPS, Bluetooth, camera, and required system services.

Use ADB for initial investigation:

```bash
adb shell pm list packages
adb shell df -h
adb shell free
adb shell dumpsys meminfo
```

Avoid removing system packages until their dependencies and impact on required hardware functionality have been verified.

## Phase 6 — Multimedia Hardware Upgrade

Investigate replacing the Alcatel Evo 7 with a newer Android device.

Target requirements:

- Native USB Host / OTG support.
- GPS support.
- A2DP Sink support if Bluetooth speaker functionality is required.
- Sufficient RAM and processing power.
- Continuous display operation.
- Compatibility with the cockpit application.

---

# 9. Final Project Vision

The long-term goal is to create a complete smart electric scooter cockpit rather than simply replace the original speedometer.

### Target Dashboard Capabilities

**Vehicle Telemetry**
- Live speed.
- Motor RPM.
- Battery voltage and current.
- Instantaneous power consumption.
- Controller and motor temperatures.
- Controller error codes.

**Battery Intelligence**
- Battery state-of-charge estimation.
- Energy consumption in Wh/km.
- Estimated remaining range.
- Battery voltage history.
- BMS cell-level information, if accessible.

**Navigation & Trip Management**
- Offline Egypt maps.
- Live GPS tracking.
- Trip distance and duration.
- Average speed.
- Persistent odometer.
- Route history.

**Multimedia**
- Bluetooth device management.
- Media metadata.
- Wi-Fi audio streaming.
- Future native Bluetooth speaker functionality.

**Data Management**
- Persistent SQLite storage.
- Historical telemetry logging.
- Trip statistics.
- Future synchronization with an external database or cloud backend.

---

# 10. Important Project Constraints

| Component | Constraint |
|---|---|
| Android OS | Android 4.0.4 / API 15 |
| Tablet | Alcatel OneTouch Evo 7 |
| Vehicle | 72V electric scooter conversion |
| USB | USB Host / OTG |
| Serial | USB-to-UART bridge required for UART |
| Controller protocol | Not yet fully verified |
| Display communication | Requires hardware investigation |
| ESP32 | Optional for standard UART; potentially required for proprietary protocols |
| Bluetooth audio | Stock A2DP Sink functionality unavailable |
| Navigation | Offline map support |

---

## Current Project Status

The Android cockpit, navigation, trip management, dashcam, Bluetooth interface, Wi-Fi audio receiver, and initial serial telemetry infrastructure have been implemented.

The main outstanding engineering challenge is identifying and validating the actual motor controller communication protocol.

**The immediate priority is hardware investigation and protocol verification before purchasing additional components or finalizing the telemetry implementation.**
