////====================================================================
//// MONITOR CONTROLER SIGNALS
////====================================================================
//
//
//
//package com.scooter.cockpit;
//
//import android.app.Activity;
//import android.graphics.Color;
//import android.graphics.Typeface;
//import android.os.Bundle;
//import android.os.Handler;
//import android.util.Log;
//import android.view.Gravity;
//import android.view.SurfaceView;
//import android.view.View;
//import android.view.WindowManager;
//import android.widget.Button;
//import android.widget.LinearLayout;
//import android.widget.ScrollView;
//import android.widget.TextView;
//
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.LinkedList;
//import java.util.Locale;
//
//public class MainActivity extends Activity {
//
//    private static final String TAG = "MainActivity";
//    private static final int MAX_HEX_LINES = 8;
//
//    // Hardware & background modules
//    private ControllerSerialReader serialReader;
//    private DashcamRecorder dashcamRecorder;
//    private final Handler uiHandler = new Handler();
//
//    // Live telemetry values updated from ControllerSerialReader background thread
//    private volatile int speed = 0;
//    private volatile int rpm = 0;
//    private volatile double volts = 0.0;
//    private volatile double amps = 0.0;
//    private volatile String gear = "D";
//    private volatile int errorCode = 0;
//    private volatile String detectedProtocol = "WAITING";
//    private volatile String latestHex = "--";
//    private volatile long packetCount = 0;
//
//    // Rolling log of incoming hex packets so you can compare byte changes live
//    private final LinkedList<String> hexHistory = new LinkedList<String>();
//    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
//    private final SimpleDateFormat clockFmt = new SimpleDateFormat("hh:mm a", Locale.US);
//
//    // Dashboard & UART Inspector Views
//    private LinearLayout rootCockpit;
//    private TextView tvConnStatus;
//    private TextView tvParsedSummary;
//    private TextView tvHexStream;
//    private TextView tvSpeed;
//    private TextView tvRpm;
//    private TextView tvVolts;
//    private TextView tvAmps;
//    private TextView tvGear;
//    private TextView tvError;
//    private TextView tvClock;
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//
//        // Keep screen awake while riding or bench-testing
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//
//        buildCockpitUi();
//        setupControllerSerial();
//    }
//
//    private void buildCockpitUi() {
//        rootCockpit = new LinearLayout(this);
//        rootCockpit.setOrientation(LinearLayout.VERTICAL);
//        rootCockpit.setBackgroundColor(Color.parseColor("#0B0F14"));
//        rootCockpit.setPadding(16, 12, 16, 12);
//        setContentView(rootCockpit);
//
//        // =====================================================================
//        // 1. TOP BAR: USB CONNECTION STATUS + CLOCK + CLEAR LOG BUTTON
//        // =====================================================================
//        LinearLayout topBar = new LinearLayout(this);
//        topBar.setOrientation(LinearLayout.HORIZONTAL);
//        topBar.setGravity(Gravity.CENTER_VERTICAL);
//        topBar.setBackgroundColor(Color.parseColor("#141B24"));
//        topBar.setPadding(14, 8, 14, 8);
//
//        tvConnStatus = new TextView(this);
//        tvConnStatus.setTextColor(Color.parseColor("#00E5FF"));
//        tvConnStatus.setTextSize(15f);
//        tvConnStatus.setTypeface(Typeface.DEFAULT_BOLD);
//        tvConnStatus.setText("UART: Initializing USB-TTL...");
//        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
//                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
//        topBar.addView(tvConnStatus, statusParams);
//
//        tvClock = new TextView(this);
//        tvClock.setTextColor(Color.LTGRAY);
//        tvClock.setTextSize(15f);
//        tvClock.setPadding(16, 0, 16, 0);
//        tvClock.setText(clockFmt.format(new Date()));
//        topBar.addView(tvClock);
//
//        Button btnClearLog = new Button(this);
//        btnClearLog.setText("CLEAR HEX LOG");
//        btnClearLog.setTextSize(12f);
//        btnClearLog.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                synchronized (hexHistory) {
//                    hexHistory.clear();
//                }
//                packetCount = 0;
//                tvHexStream.setText("Waiting for incoming bytes...");
//            }
//        });
//        topBar.addView(btnClearLog);
//
//        rootCockpit.addView(topBar, new LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
//
//        // =====================================================================
//        // 2. OBVIOUS PARSED TELEMETRY BANNER (ALL DECODED FIELDS IN ONE LINE)
//        // =====================================================================
//        tvParsedSummary = new TextView(this);
//        tvParsedSummary.setTextColor(Color.YELLOW);
//        tvParsedSummary.setBackgroundColor(Color.parseColor("#1C2430"));
//        tvParsedSummary.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
//        tvParsedSummary.setTextSize(14f);
//        tvParsedSummary.setPadding(14, 10, 14, 10);
//        tvParsedSummary.setText("PROTO: WAITING | SPD: 0 km/h | RPM: 0 | V: 0.0V | A: 0.0A | GEAR: D | ERR: 0 | PKTS: 0");
//
//        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
//        summaryParams.topMargin = 8;
//        rootCockpit.addView(tvParsedSummary, summaryParams);
//
//        // =====================================================================
//        // 3. MAIN SPLIT BODY: LEFT GAUGES + RIGHT RAW HEX STREAM & DASHCAM
//        // =====================================================================
//        LinearLayout bodyRow = new LinearLayout(this);
//        bodyRow.setOrientation(LinearLayout.HORIZONTAL);
//        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
//        bodyParams.topMargin = 10;
//        rootCockpit.addView(bodyRow, bodyParams);
//
//        // --- LEFT COLUMN: DIGITAL GAUGES ---
//        LinearLayout gaugePanel = new LinearLayout(this);
//        gaugePanel.setOrientation(LinearLayout.VERTICAL);
//        gaugePanel.setBackgroundColor(Color.parseColor("#121821"));
//        gaugePanel.setPadding(16, 12, 16, 12);
//        gaugePanel.setGravity(Gravity.CENTER);
//        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
//                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.45f);
//        leftParams.rightMargin = 10;
//        bodyRow.addView(gaugePanel, leftParams);
//
//        tvSpeed = new TextView(this);
//        tvSpeed.setTextColor(Color.WHITE);
//        tvSpeed.setTextSize(68f);
//        tvSpeed.setTypeface(Typeface.DEFAULT_BOLD);
//        tvSpeed.setGravity(Gravity.CENTER);
//        tvSpeed.setText("0");
//        gaugePanel.addView(tvSpeed);
//
//        TextView tvSpeedUnit = new TextView(this);
//        tvSpeedUnit.setTextColor(Color.parseColor("#00E5FF"));
//        tvSpeedUnit.setTextSize(16f);
//        tvSpeedUnit.setGravity(Gravity.CENTER);
//        tvSpeedUnit.setText("KM/H");
//        gaugePanel.addView(tvSpeedUnit);
//
//        // Secondary metrics row (Volt, Amps, RPM, Gear, Error)
//        LinearLayout metricsRow = new LinearLayout(this);
//        metricsRow.setOrientation(LinearLayout.HORIZONTAL);
//        metricsRow.setGravity(Gravity.CENTER);
//        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
//        metricsParams.topMargin = 16;
//        gaugePanel.addView(metricsRow, metricsParams);
//
//        tvVolts = createMetricBadge(metricsRow, "VOLT", "0.0 V", "#4CAF50");
//        tvAmps = createMetricBadge(metricsRow, "AMPS", "0.0 A", "#FF9800");
//        tvRpm = createMetricBadge(metricsRow, "RPM", "0", "#00E5FF");
//        tvGear = createMetricBadge(metricsRow, "GEAR", "D", "#E040FB");
//        tvError = createMetricBadge(metricsRow, "ERR", "0", "#FF5252");
//
//        // --- RIGHT COLUMN: LIVE HEX FRAME INSPECTOR + DASHCAM PREVIEW ---
//        LinearLayout rightPanel = new LinearLayout(this);
//        rightPanel.setOrientation(LinearLayout.VERTICAL);
//        rightPanel.setBackgroundColor(Color.parseColor("#121821"));
//        rightPanel.setPadding(14, 10, 14, 10);
//        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
//                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.55f);
//        bodyRow.addView(rightPanel, rightParams);
//
//        TextView tvHexHeader = new TextView(this);
//        tvHexHeader.setTextColor(Color.parseColor("#80CBC4"));
//        tvHexHeader.setTextSize(13f);
//        tvHexHeader.setTypeface(Typeface.DEFAULT_BOLD);
//        tvHexHeader.setText("INCOMING CONTROLLER FRAMES (LATEST AT TOP):");
//        rightPanel.addView(tvHexHeader);
//
//        ScrollView hexScroll = new ScrollView(this);
//        hexScroll.setBackgroundColor(Color.parseColor("#080B0F"));
//        hexScroll.setPadding(10, 8, 10, 8);
//        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
//                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
//        scrollParams.topMargin = 6;
//        rightPanel.addView(hexScroll, scrollParams);
//
//        tvHexStream = new TextView(this);
//        tvHexStream.setTextColor(Color.parseColor("#76FF03"));
//        tvHexStream.setTypeface(Typeface.MONOSPACE);
//        tvHexStream.setTextSize(13f);
//        tvHexStream.setText("Waiting for incoming bytes on USB-TTL (9600 8N1)...");
//        hexScroll.addView(tvHexStream);
//
//        // Dashcam preview surface (160x120 valid API 15 buffer)
//        SurfaceView camPreview = new SurfaceView(this);
//        LinearLayout.LayoutParams camParams = new LinearLayout.LayoutParams(160, 120);
//        camParams.topMargin = 8;
//        camParams.gravity = Gravity.RIGHT;
//        rightPanel.addView(camPreview, camParams);
//        dashcamRecorder = new DashcamRecorder(this, camPreview);
//    }
//
//    private TextView createMetricBadge(LinearLayout parent, String title, String initialVal, String colorHex) {
//        LinearLayout box = new LinearLayout(this);
//        box.setOrientation(LinearLayout.VERTICAL);
//        box.setGravity(Gravity.CENTER);
//        box.setPadding(8, 6, 8, 6);
//
//        TextView lbl = new TextView(this);
//        lbl.setTextColor(Color.GRAY);
//        lbl.setTextSize(11f);
//        lbl.setGravity(Gravity.CENTER);
//        lbl.setText(title);
//        box.addView(lbl);
//
//        TextView val = new TextView(this);
//        val.setTextColor(Color.parseColor(colorHex));
//        val.setTextSize(16f);
//        val.setTypeface(Typeface.DEFAULT_BOLD);
//        val.setGravity(Gravity.CENTER);
//        val.setText(initialVal);
//        box.addView(val);
//
//        parent.addView(box, new LinearLayout.LayoutParams(
//                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
//        return val;
//    }
//
//    private void setupControllerSerial() {
//        serialReader = new ControllerSerialReader(this, new ControllerSerialReader.TelemetryListener() {
//            @Override
//            public void onTelemetryUpdated(final ControllerSerialReader.TelemetryData data) {
//                // Store latest parsed values
//                detectedProtocol = data.detectedProtocol;
//                speed = data.speedKmh;
//                rpm = data.rpm;
//                if (data.volts > 0) {
//                    volts = data.volts;
//                }
//                amps = data.amps;
//                gear = data.gear;
//                errorCode = data.errorCode;
//                latestHex = data.rawHex;
//                packetCount++;
//
//                // Print complete parsed + raw readout to Android Studio Logcat
//                final String obviousLine = data.toObviousReadout();
//                Log.i("ControllerSerial", obviousLine.replace('\n', ' '));
//
//                // Push timestamped frame into rolling on-screen history
//                final String stampedLine = "[" + timeFmt.format(new Date()) + "] ("
//                        + data.detectedProtocol + ")\n" + data.rawHex;
//                final String historySnapshot;
//                synchronized (hexHistory) {
//                    hexHistory.addFirst(stampedLine);
//                    while (hexHistory.size() > MAX_HEX_LINES) {
//                        hexHistory.removeLast();
//                    }
//                    StringBuilder sb = new StringBuilder();
//                    for (String line : hexHistory) {
//                        sb.append(line).append("\n\n");
//                    }
//                    historySnapshot = sb.toString().trim();
//                }
//
//                // Update UI safely on main thread
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        refreshDashboardViews(historySnapshot);
//                    }
//                });
//            }
//
//            @Override
//            public void onConnectionStatus(final String status) {
//                Log.i("ControllerSerial", "Status: " + status);
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        if (tvConnStatus != null) {
//                            tvConnStatus.setText("UART: " + status);
//                        }
//                    }
//                });
//            }
//        });
//    }
//
//    private void refreshDashboardViews(String historySnapshot) {
//        if (tvParsedSummary != null) {
//            tvParsedSummary.setText(String.format(Locale.US,
//                    "PROTO: %s | SPD: %d km/h | RPM: %d | V: %.1fV | A: %.1fA | GEAR: %s | ERR: %d | PKTS: %d",
//                    detectedProtocol, speed, rpm, volts, amps, gear, errorCode, packetCount));
//        }
//        if (tvSpeed != null) tvSpeed.setText(String.valueOf(speed));
//        if (tvVolts != null) tvVolts.setText(String.format(Locale.US, "%.1f V", volts));
//        if (tvAmps != null) tvAmps.setText(String.format(Locale.US, "%.1f A", amps));
//        if (tvRpm != null) tvRpm.setText(String.valueOf(rpm));
//        if (tvGear != null) tvGear.setText(gear);
//        if (tvError != null) tvError.setText(String.valueOf(errorCode));
//        if (tvHexStream != null && historySnapshot != null) {
//            tvHexStream.setText(historySnapshot);
//        }
//    }
//
//    private final Runnable clockRunnable = new Runnable() {
//        @Override
//        public void run() {
//            if (tvClock != null) {
//                tvClock.setText(clockFmt.format(new Date()));
//            }
//            uiHandler.postDelayed(this, 1000L);
//        }
//    };
//
//    @Override
//    protected void onResume() {
//        super.onResume();
//        uiHandler.post(clockRunnable);
//        if (serialReader != null) {
//            serialReader.start();
//        }
//        if (dashcamRecorder != null) {
//            dashcamRecorder.startLoopRecording();
//        }
//    }
//
//    @Override
//    protected void onPause() {
//        super.onPause();
//        uiHandler.removeCallbacks(clockRunnable);
//        if (serialReader != null) {
//            serialReader.stop();
//        }
//        if (dashcamRecorder != null) {
//            dashcamRecorder.stopLoopRecording();
//        }
//    }
//}