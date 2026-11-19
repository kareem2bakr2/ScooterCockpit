package com.scooter.cockpit;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

public class ControllerSerialReader {

    private static final String TAG = "ControllerSerial";
    private static final String ACTION_USB_PERMISSION = "com.scooter.cockpit.USB_PERMISSION";

    // Adjust to your Keeway X-Road rear wheel outer circumference in meters (e.g., 1.55m - 1.75m)
    private static final double WHEEL_CIRCUMFERENCE_M = 1.65;

    public static class TelemetryData {
        public String detectedProtocol = "UNKNOWN";
        public int speedKmh = 0;
        public int rpm = 0;
        public double volts = 0.0;
        public double amps = 0.0;
        public String gear = "D";
        public int errorCode = 0;
        public String rawHex = "";

        public String toObviousReadout() {
            return String.format(Locale.US,
                    "PROTO: %s | SPD: %d km/h | RPM: %d | V: %.1fV | A: %.1fA | GEAR: %s | ERR: %d\nHEX: %s",
                    detectedProtocol, speedKmh, rpm, volts, amps, gear, errorCode, rawHex);
        }
    }

    public interface TelemetryListener {
        void onTelemetryUpdated(TelemetryData data);
        void onConnectionStatus(String status);
    }

    private final Context context;
    private final TelemetryListener listener;
    private final UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Thread readThread;
    private volatile boolean isRunning = false;
    private boolean isReceiverRegistered = false;

    public ControllerSerialReader(Context context, TelemetryListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public void start() {
        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(usbReceiver, filter);
            isReceiverRegistered = true;
        }
        connectToFirstAvailableAdapter();
    }

    public void stop() {
        closePort();
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver);
            } catch (Exception ignored) {
            }
            isReceiverRegistered = false;
        }
    }

    private void closePort() {
        isRunning = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (Exception ignored) {
            }
            serialPort = null;
        }
    }

    private void connectToFirstAvailableAdapter() {
        if (isRunning && serialPort != null) {
            return;
        }

        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            listener.onConnectionStatus("No USB-TTL adapter detected");
            return;
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();

        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(device, pi);
            listener.onConnectionStatus("Requesting USB permission...");
            return;
        }

        openPort(driver);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectToFirstAvailableAdapter();
                } else {
                    listener.onConnectionStatus("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                connectToFirstAvailableAdapter();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                closePort();
                listener.onConnectionStatus("USB-TTL adapter disconnected");
            }
        }
    };

    private void openPort(UsbSerialDriver driver) {
        try {
            UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
            if (connection == null) {
                listener.onConnectionStatus("Failed to open USB connection");
                return;
            }

            serialPort = driver.getPorts().get(0);
            serialPort.open(connection);
            // Standard 9600 8N1 used by Protocol No. 2 and Kunteng controllers
            serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            isRunning = true;
            listener.onConnectionStatus("Connected: 9600 8N1");

            readThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "ControllerUartThread");
            readThread.start();
        } catch (Exception e) {
            listener.onConnectionStatus("Serial open error: " + e.getMessage());
        }
    }

    private void readLoop() {
        byte[] chunk = new byte[64];
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        while (isRunning && !Thread.currentThread().isInterrupted()) {
            try {
                int len = serialPort.read(chunk, 250);
                if (len > 0) {
                    buffer.write(chunk, 0, len);
                    processBuffer(buffer);
                }
            } catch (Exception e) {
                Log.e(TAG, "UART read error: " + e.getMessage());
                listener.onConnectionStatus("UART read error: " + e.getMessage());
                break;
            }
        }
    }

    private void processBuffer(ByteArrayOutputStream buffer) {
        byte[] data = buffer.toByteArray();
        if (data.length < 12) return;

        // 1. Try UART Protocol No. 2 (14 bytes starting with 0x02 0x0E)
        for (int i = 0; i <= data.length - 14; i++) {
            if ((data[i] & 0xFF) == 0x02 && (data[i + 1] & 0xFF) == 0x0E) {
                if (verifyXorChecksum(data, i, 13, data[i + 13])) {
                    TelemetryData t = parseProtocolNo2(data, i);
                    listener.onTelemetryUpdated(t);
                    buffer.reset();
                    return;
                }
            }
        }

        // 2. Try Kunteng (KT) Protocol (12 bytes starting with 0x41)
        for (int i = 0; i <= data.length - 12; i++) {
            if ((data[i] & 0xFF) == 0x41) {
                TelemetryData t = parseKuntengProtocol(data, i);
                listener.onTelemetryUpdated(t);
                buffer.reset();
                return;
            }
        }

        // 3. Wait up to 28 bytes in case a valid header started near the end of the previous chunk;
        // otherwise emit raw hex bytes so you can inspect custom headers or YXT pulse noise.
        if (data.length >= 28) {
            TelemetryData raw = new TelemetryData();
            raw.detectedProtocol = "RAW_HEX";
            raw.rawHex = toHex(data, 0, Math.min(data.length, 20));
            listener.onTelemetryUpdated(raw);
            buffer.reset();
        }
    }

    private TelemetryData parseProtocolNo2(byte[] b, int offset) {
        TelemetryData t = new TelemetryData();
        t.detectedProtocol = "UART_PROTOCOL_NO_2";
        t.rawHex = toHex(b, offset, 14);
        t.errorCode = b[offset + 3] & 0xFF;

        int gearVal = b[offset + 4] & 0xFF;
        t.gear = (gearVal == 0) ? "P" : String.valueOf(gearVal);

        // Wheel period in milliseconds (big-endian uint16)
        int wheelPeriodMs = ((b[offset + 6] & 0xFF) << 8) | (b[offset + 7] & 0xFF);
        if (wheelPeriodMs > 0 && wheelPeriodMs < 60000) {
            double rps = 1000.0 / wheelPeriodMs;
            t.rpm = (int) Math.round(rps * 60.0);
            t.speedKmh = (int) Math.round(rps * WHEEL_CIRCUMFERENCE_M * 3.6);
        }

        // Battery voltage scaled by 0.1V (or fallback if controller omits voltage bytes)
        int rawVolt = ((b[offset + 8] & 0xFF) << 8) | (b[offset + 9] & 0xFF);
        if (rawVolt > 100) {
            t.volts = rawVolt / 10.0;
        }
        return t;
    }

    private TelemetryData parseKuntengProtocol(byte[] b, int offset) {
        TelemetryData t = new TelemetryData();
        t.detectedProtocol = "KUNTENG_KT";
        t.rawHex = toHex(b, offset, 12);
        t.amps = (b[offset + 2] & 0xFF) * 0.5;
        t.errorCode = b[offset + 5] & 0xFF;

        int wheelPeriodMs = ((b[offset + 3] & 0xFF) << 8) | (b[offset + 4] & 0xFF);
        if (wheelPeriodMs > 0 && wheelPeriodMs < 60000) {
            double rps = 1000.0 / wheelPeriodMs;
            t.rpm = (int) Math.round(rps * 60.0);
            t.speedKmh = (int) Math.round(rps * WHEEL_CIRCUMFERENCE_M * 3.6);
        }
        return t;
    }

    private boolean verifyXorChecksum(byte[] b, int start, int len, byte expected) {
        int xor = 0;
        for (int i = start; i < start + len; i++) {
            xor ^= (b[i] & 0xFF);
        }
        return (xor & 0xFF) == (expected & 0xFF);
    }

    private String toHex(byte[] b, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + len && i < b.length; i++) {
            sb.append(String.format(Locale.US, "%02X ", b[i] & 0xFF));
        }
        return sb.toString().trim();
    }
}