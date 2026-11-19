package com.scooter.cockpit;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.MapTileProviderArray;
import org.osmdroid.tileprovider.modules.DatabaseFileArchive;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "CockpitMap";
    private static final long SERIAL_TIMEOUT_MS = 2500L;

    private CockpitDbHelper dbHelper;
    private double avgspeedDistance = 0;

    // GPS & Telemetry State
    private LocationManager locationManager;
    private double currentLat = 0.0;
    private double currentLng = 0.0;
    private double currentAlt = 0.0;

    private final Date reusableDate = new Date();
    private long startElapsedRealtime;
    private volatile String gear = "D";
    private volatile double volts = 76.0;
    private volatile int rpm = 0;
    private volatile int speed = 0;
    private volatile int controllerErrorCode = 0;
    private volatile long lastSerialPacketTimeMs = 0L;
    private double odoMetersBuffer = 0.0;

    private final String[] ScooterStatus = {"Drive", "Sleep", "Contact"};
    private String CurrState = ScooterStatus[0];
    private String prevState = "";

    // Top Status Bar Views
    private LinearLayout rootCockpit;
    private TextView tvTurnLeft, tvTurnRight, tvHighBeam;
    private TextView tvEspDot, tvEspStatus, tvScooterVolt, tvTabletBatt, tvClock;
    private LinearLayout btnDayNight;
    private TextView tvSunIcon, tvMoonIcon;
    private DashcamRecorder dashcamRecorder;
    private Button btnSettings;

    // Left Dock Buttons
    private Button btnDockRide, btnDockNavi, btnDockMusic, btnDockBt, btnDockApps;

    // Center Gauge Cluster
    private RpmArcView rpmArcView;
    private TextView tvRpm, tvSpeed, tvGear, tvAssistStatus;
    private LinearLayout boxTripA;
    private TextView tvTripA, tvOdo, tvAvgSpeed;

    // Right Telemetry Cards
    private FuelBarView fuelBarView;
    private TextView tvFuelRange, tvFuelPct, tvFuelLiters;
    private TextView tvCoolantTemp, tvCoolantStatus;
    private LinearLayout cardRidingMode;
    private TextView tvRidingMode, tvRideTime;

    // Navigation & Map Views
    private IMapController mapController;
    private Marker scooterMarker, destMarker;
    private Polyline directLine;
    private LinearLayout panelRide, panelNavi;
    private EditText etDestination;
    private Button btnSearchDest, btnRecenterMap;
    private MapView mapView;

    // Centered Bluetooth Speaker & Music Panel
    private BluetoothSpeakerPanel btSpeakerPanel;
    private View btPanelView;

    // Controller UART Serial Reader & Live Hex Monitor
    private ControllerSerialReader serialReader;
    private TextView tvSerialDebug;

    // Simulation & Clock State
    private final Handler handler = new Handler();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private long startTimeMillis;
    private double tripAKm = 42.6;
    private double odoKm = 8426.0;
    private int fuelPct = 64;
    private int coolantTemp = 84;
    private int tickCounter = 0;
    private boolean isDayMode = false;
    private boolean leftBlinkerOn = false;
    private boolean rightBlinkerOn = false;
    private boolean blinkerPhase = false;

    private final String[] ridingModes = {"ECO", "CITY", "SPORT"};

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;

            currentLat = location.getLatitude();
            currentLng = location.getLongitude();

            if (location.hasAltitude()) {
                currentAlt = location.getAltitude();
            }
            if (scooterMarker != null && panelNavi != null && panelNavi.getVisibility() == View.VISIBLE) {
                scooterMarker.setPosition(getActiveMapPoint());
                mapView.invalidate();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    // Main 200ms Telemetry + Clock Loop
    private final Runnable simulationRunnable = new Runnable() {
        @Override
        public void run() {
            UpdateVars();
            updateClockAndRideTimer();
            updateSimulatedTelemetry();
            tickCounter++;
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context ctx = getApplicationContext();
        File osmDir = new File(getFilesDir(), "osmdroid");
        if (!osmDir.exists()) {
            osmDir.mkdirs();
        }
        prepareOfflineMapDatabase(osmDir);

        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setOsmdroidBasePath(osmDir);
        Configuration.getInstance().setOsmdroidTileCache(new File(osmDir, "tiles"));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().setDebugMode(true);
        Configuration.getInstance().setDebugTileProviders(true);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        setContentView(R.layout.activity_main);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        dbHelper = CockpitDbHelper.getInstance(this);

        startTimeMillis = System.currentTimeMillis();
        bindViews();
        setupBluetoothCenterPanel();
        setupSerialTelemetry();
        setupInteractiveControls();
        setupMap();
    }

    private void prepareOfflineMapDatabase(File osmDir) {
        File targetFile = new File(osmDir, "egypt.sqlite");

        // 1. Copy from /sdcard/osmdroid/egypt.sqlite or bundled assets if missing
        if (!targetFile.exists() || targetFile.length() < 1000000) {
            InputStream in = null;
            OutputStream out = null;
            try {
                File sdFile = new File(Environment.getExternalStorageDirectory(), "osmdroid/egypt.sqlite");
                if (sdFile.exists() && sdFile.length() > 1000000) {
                    in = new FileInputStream(sdFile);
                } else {
                    try {
                        in = getAssets().open("egypt.sqlite");
                    } catch (Exception e) {
                        in = getAssets().open("eqypt.sqlite");
                    }
                }

                out = new FileOutputStream(targetFile);
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (Exception e) {
                Toast.makeText(this, "Copy failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
        }

        // 2. Inject android_metadata so Android 4.0.4 can open the DB in READONLY mode
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(
                    targetFile.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            db.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)");
            cursor = db.rawQuery("SELECT COUNT(*) FROM android_metadata", null);
            if (cursor.moveToFirst() && cursor.getInt(0) == 0) {
                db.execSQL("INSERT INTO android_metadata (locale) VALUES ('en_US')");
            }
            cursor.close();

            cursor = db.rawQuery("SELECT provider, COUNT(*) FROM tiles GROUP BY provider", null);
            while (cursor.moveToNext()) {
                Log.d(TAG, "DB provider='" + cursor.getString(0) + "' tiles=" + cursor.getInt(1));
            }
            cursor.close();

            cursor = db.rawQuery("SELECT COUNT(*) FROM tiles WHERE provider = 'OSMPublicTransport'", null);
            int tileCount = (cursor.moveToFirst()) ? cursor.getInt(0) : 0;
            Toast.makeText(this, "Map DB ready: " + tileCount + " tiles", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "DB init error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "DB init error", e);
        } finally {
            if (cursor != null) cursor.close();
            if (db != null) db.close();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.invalidate();
        }
        if (dashcamRecorder != null) {
            dashcamRecorder.startLoopRecording();
        }
        if (serialReader != null) {
            serialReader.start();
        }
        if (btSpeakerPanel != null) {
            btSpeakerPanel.register();
        }
        startGpsTracking();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        handler.post(simulationRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dashcamRecorder != null) {
            dashcamRecorder.stopLoopRecording();
        }
        if (serialReader != null) {
            serialReader.stop();
        }
        if (btSpeakerPanel != null) {
            btSpeakerPanel.unregister();
        }
        handler.removeCallbacks(simulationRunnable);
        if (locationManager != null) {
            locationManager.removeUpdates(gpsListener);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (btSpeakerPanel != null) {
            btSpeakerPanel.release();
        }
        if (mapView != null) {
            mapView.onDetach();
        }
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    private void startGpsTracking() {
        if (locationManager == null || locationManager.getProvider(LocationManager.GPS_PROVIDER) == null) {
            return;
        }

        Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (lastKnown != null) {
            currentLat = lastKnown.getLatitude();
            currentLng = lastKnown.getLongitude();
            if (lastKnown.hasAltitude()) {
                currentAlt = lastKnown.getAltitude();
            }
        }

        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                5.0f,
                gpsListener
        );
    }

    private boolean isInEgypt(double lat, double lng) {
        return lat >= 21.5 && lat <= 32.0 && lng >= 24.5 && lng <= 37.0;
    }

    private GeoPoint getActiveMapPoint() {
        if (isInEgypt(currentLat, currentLng)) {
            return new GeoPoint(currentLat, currentLng);
        }
        return new GeoPoint(30.0444, 31.2357);
    }

    private void bindViews() {
        rootCockpit = (LinearLayout) findViewById(R.id.rootCockpit);
        tvTurnLeft = (TextView) findViewById(R.id.tvTurnLeft);
        tvTurnRight = (TextView) findViewById(R.id.tvTurnRight);
        tvHighBeam = (TextView) findViewById(R.id.tvHighBeam);
        tvEspDot = (TextView) findViewById(R.id.tvEspDot);
        tvEspStatus = (TextView) findViewById(R.id.tvEspStatus);
        tvScooterVolt = (TextView) findViewById(R.id.tvScooterVolt);
        tvTabletBatt = (TextView) findViewById(R.id.tvTabletBatt);
        tvClock = (TextView) findViewById(R.id.tvClock);

        btnDayNight = (LinearLayout) findViewById(R.id.btnDayNight);
        tvSunIcon = (TextView) findViewById(R.id.tvSunIcon);
        tvMoonIcon = (TextView) findViewById(R.id.tvMoonIcon);
        btnSettings = (Button) findViewById(R.id.btnSettings);
        SurfaceView camPreview = (SurfaceView) findViewById(R.id.camPreview);
        dashcamRecorder = new DashcamRecorder(this, camPreview);
        btnDockRide = (Button) findViewById(R.id.btnDockRide);
        btnDockNavi = (Button) findViewById(R.id.btnDockNavi);
        btnDockMusic = (Button) findViewById(R.id.btnDockMusic);
        btnDockBt = (Button) findViewById(R.id.btnDockBt);
        btnDockApps = (Button) findViewById(R.id.btnDockApps);

        rpmArcView = (RpmArcView) findViewById(R.id.rpmArcView);
        tvRpm = (TextView) findViewById(R.id.tvRpm);
        tvSpeed = (TextView) findViewById(R.id.tvSpeed);
        tvGear = (TextView) findViewById(R.id.tvGear);
        tvAssistStatus = (TextView) findViewById(R.id.tvAssistStatus);

        boxTripA = (LinearLayout) findViewById(R.id.boxTripA);
        tvTripA = (TextView) findViewById(R.id.tvTripA);
        tvOdo = (TextView) findViewById(R.id.tvOdo);
        tvAvgSpeed = (TextView) findViewById(R.id.tvAvgSpeed);

        fuelBarView = (FuelBarView) findViewById(R.id.fuelBarView);
        tvFuelRange = (TextView) findViewById(R.id.tvFuelRange);
        tvFuelPct = (TextView) findViewById(R.id.tvFuelPct);
        tvFuelLiters = (TextView) findViewById(R.id.tvFuelLiters);

        tvCoolantTemp = (TextView) findViewById(R.id.tvCoolantTemp);
        tvCoolantStatus = (TextView) findViewById(R.id.tvCoolantStatus);
        cardRidingMode = (LinearLayout) findViewById(R.id.cardRidingMode);
        tvRidingMode = (TextView) findViewById(R.id.tvRidingMode);
        tvRideTime = (TextView) findViewById(R.id.tvRideTime);

        panelRide = (LinearLayout) findViewById(R.id.panelRide);
        panelNavi = (LinearLayout) findViewById(R.id.panelNavi);
        etDestination = (EditText) findViewById(R.id.etDestination);
        btnSearchDest = (Button) findViewById(R.id.btnSearchDest);
        btnRecenterMap = (Button) findViewById(R.id.btnRecenterMap);
        mapView = (MapView) findViewById(R.id.mapView);
    }

    private void setupBluetoothCenterPanel() {
        btSpeakerPanel = new BluetoothSpeakerPanel(this, new BluetoothSpeakerPanel.PanelCloseListener() {
            @Override
            public void onCloseRequested() {
                showCenterPanel(panelRide);
                resetDockButtons();
                btnDockRide.setBackgroundResource(R.drawable.bg_dock_active);
                btnDockRide.setTextColor(Color.parseColor("#00E5FF"));
            }
        });

        btPanelView = btSpeakerPanel.getView();
        btPanelView.setVisibility(View.GONE);

        // Attach into the exact same centered parent container as panelRide and panelNavi
        if (panelRide != null && panelRide.getParent() instanceof ViewGroup) {
            ViewGroup centerHost = (ViewGroup) panelRide.getParent();
            ViewGroup.LayoutParams rideParams = panelRide.getLayoutParams();
            if (rideParams instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams src = (LinearLayout.LayoutParams) rideParams;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(src.width, src.height, src.weight);
                lp.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
                centerHost.addView(btPanelView, lp);
            } else {
                centerHost.addView(btPanelView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
    }

    private void showCenterPanel(View targetPanel) {
        if (panelRide != null) {
            panelRide.setVisibility(targetPanel == panelRide ? View.VISIBLE : View.GONE);
        }
        if (panelNavi != null) {
            panelNavi.setVisibility(targetPanel == panelNavi ? View.VISIBLE : View.GONE);
        }
        if (btPanelView != null) {
            btPanelView.setVisibility(targetPanel == btPanelView ? View.VISIBLE : View.GONE);
        }
    }

    private void setupSerialTelemetry() {
        // Compact high-contrast UART banner at top of rootCockpit (tap to toggle visibility)
        if (rootCockpit != null) {
            tvSerialDebug = new TextView(this);
            tvSerialDebug.setTextColor(Color.YELLOW);
            tvSerialDebug.setBackgroundColor(Color.parseColor("#CC0B0F14"));
            tvSerialDebug.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvSerialDebug.setTextSize(12f);
            tvSerialDebug.setPadding(12, 4, 12, 4);
            tvSerialDebug.setSingleLine(false);
            tvSerialDebug.setMaxLines(2);
            tvSerialDebug.setText("UART: Waiting for USB-TTL...");
            tvSerialDebug.setVisibility(View.GONE);
            rootCockpit.addView(tvSerialDebug, 0, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // Tapping the top status label toggles the raw UART hex debug strip on/off
        if (tvEspStatus != null) {
            tvEspStatus.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tvSerialDebug != null) {
                        tvSerialDebug.setVisibility(
                                tvSerialDebug.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                    }
                }
            });
        }

        serialReader = new ControllerSerialReader(this, new ControllerSerialReader.TelemetryListener() {
            @Override
            public void onTelemetryUpdated(final ControllerSerialReader.TelemetryData data) {
                lastSerialPacketTimeMs = System.currentTimeMillis();
                speed = data.speedKmh;
                rpm = data.rpm;
                if (data.volts > 0) {
                    volts = data.volts;
                }
                gear = data.gear;
                controllerErrorCode = data.errorCode;

                final String readout = data.toObviousReadout();
                Log.i("ControllerSerial", readout.replace('\n', ' '));

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tvSerialDebug != null) {
                            if (tvSerialDebug.getVisibility() != View.VISIBLE) {
                                tvSerialDebug.setVisibility(View.VISIBLE);
                            }
                            tvSerialDebug.setText(readout);
                        }
                        if (tvEspDot != null) {
                            tvEspDot.setTextColor(Color.parseColor("#00E676"));
                        }
                        if (tvEspStatus != null) {
                            tvEspStatus.setText("UART " + data.detectedProtocol);
                        }
                    }
                });
            }

            @Override
            public void onConnectionStatus(final String status) {
                Log.i("ControllerSerial", "Status: " + status);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tvSerialDebug != null) {
                            tvSerialDebug.setText("UART: " + status);
                        }
                        if (tvEspStatus != null) {
                            tvEspStatus.setText(status);
                        }
                    }
                });
            }
        });
    }

    private void setupInteractiveControls() {
        // Open Android Settings
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        // Recenter map on scooter GPS location
        btnRecenterMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                centerMapOnScooter();
            }
        });

        // Long-press Trip A to reset
        boxTripA.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startTimeMillis = System.currentTimeMillis();
                avgspeedDistance = 0;
                tickCounter = 0;
                dbHelper.endTrip(speed, (int) odoMetersBuffer, startTimeMillis, currentLat, currentLng, currentAlt);
                odoMetersBuffer = 0.0;
                tvTripA.setText("0.0");
                Toast.makeText(MainActivity.this, "Trip Reset", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        // Day/Night toggle
        btnDayNight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isDayMode = !isDayMode;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (isDayMode) {
                    rootCockpit.setBackgroundColor(Color.parseColor("#1E293B"));
                    tvSunIcon.setBackgroundResource(R.drawable.bg_dock_active);
                    tvSunIcon.setTextColor(Color.parseColor("#FFD600"));
                    tvMoonIcon.setBackgroundResource(0);
                    tvMoonIcon.setTextColor(Color.parseColor("#8B949E"));
                    lp.screenBrightness = 1.0f;
                } else {
                    rootCockpit.setBackgroundColor(Color.parseColor("#000000"));
                    tvMoonIcon.setBackgroundResource(R.drawable.bg_dock_active);
                    tvMoonIcon.setTextColor(Color.parseColor("#00E5FF"));
                    tvSunIcon.setBackgroundResource(0);
                    tvSunIcon.setTextColor(Color.parseColor("#8B949E"));
                    lp.screenBrightness = 0.45f;
                }
                getWindow().setAttributes(lp);
            }
        });

        // Left Dock Selection Highlighting
        View.OnClickListener dockListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetDockButtons();
                Button clicked = (Button) v;
                clicked.setBackgroundResource(R.drawable.bg_dock_active);
                clicked.setTextColor(Color.parseColor("#00E5FF"));

                if (v == btnDockNavi) {
                    showCenterPanel(panelNavi);
                    if (mapView != null) {
                        mapView.requestLayout();
                        mapView.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mapController != null) {
                                    mapController.setZoom(14);
                                }
                                centerMapOnScooter();
                                mapView.invalidate();
                                Log.d(TAG, "Navi opened. MapView size = "
                                        + mapView.getWidth() + "x" + mapView.getHeight()
                                        + ", zoom=" + mapView.getZoomLevel());
                            }
                        });
                    }
                } else if (v == btnDockRide) {
                    showCenterPanel(panelRide);
                } else if (v == btnDockBt || v == btnDockMusic) {
                    // Open centered Bluetooth Speaker + Now Playing panel
                    showCenterPanel(btPanelView);
                } else {
                    Toast.makeText(MainActivity.this,
                            clicked.getText().toString().replace("\n", " ") + " selected",
                            Toast.LENGTH_SHORT).show();
                }
            }
        };

        btnDockRide.setOnClickListener(dockListener);
        btnDockNavi.setOnClickListener(dockListener);
        btnDockMusic.setOnClickListener(dockListener);
        btnDockBt.setOnClickListener(dockListener);
        btnDockApps.setOnClickListener(dockListener);
    }

    private void resetDockButtons() {
        Button[] buttons = {btnDockRide, btnDockNavi, btnDockMusic, btnDockBt, btnDockApps};
        for (Button b : buttons) {
            b.setBackgroundResource(R.drawable.bg_dock_normal);
            b.setTextColor(Color.parseColor("#94A3B8"));
        }
    }

    private void updateClockAndRideTimer() {
        reusableDate.setTime(System.currentTimeMillis());
        tvClock.setText(clockFormat.format(reusableDate));

        long elapsedSec = Math.max(0L, (System.currentTimeMillis() - startElapsedRealtime) / 1000L);

        long days = elapsedSec / 86400L;
        long hours = (elapsedSec % 86400L) / 3600L;
        long mins = (elapsedSec % 3600L) / 60L;
        long secs = elapsedSec % 60L;

        if (days > 0) {
            tvRideTime.setText(String.format(Locale.US, "%02d:%02d:%02d:%02d", days, hours, mins, secs));
        } else if (hours > 0) {
            tvRideTime.setText(String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs));
        } else {
            tvRideTime.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
        }
    }

    private void updateSimulatedTelemetry() {
        speed = Math.max(0, Math.min(120, speed));

        if (rpm < 140) {
            tvGear.setTextColor(Color.parseColor("#00E676"));
        } else if (rpm < 3400) {
            tvGear.setTextColor(Color.parseColor("#FFFFFF"));
        } else {
            tvGear.setTextColor(Color.parseColor("#00E5FF"));
        }
        rpm = Math.min(9800, rpm);

        // Update Speed, Gear & Custom RPM Arc
        tvSpeed.setText(String.valueOf(speed));
        tvGear.setText(gear);
        tvRpm.setText(String.format(Locale.US, "%,d", rpm));
        rpmArcView.setRpm(rpm, 10000);

        if (tvAssistStatus != null) {
            if (controllerErrorCode != 0) {
                tvAssistStatus.setText("ERR " + controllerErrorCode);
                tvAssistStatus.setTextColor(Color.parseColor("#EF4444"));
            } else {
                tvAssistStatus.setText("READY");
                tvAssistStatus.setTextColor(Color.parseColor("#00E676"));
            }
        }

        if (rpm >= 8800) {
            tvRpm.setTextColor(Color.parseColor("#EF4444"));
        } else if (rpm >= 7800) {
            tvRpm.setTextColor(Color.parseColor("#F59E0B"));
        } else {
            tvRpm.setTextColor(Color.parseColor("#00E5FF"));
        }

        // Accumulate Trip A and Odometer based on speed
        double kmDelta = (speed / 3600.0) * 0.2;
        tripAKm += kmDelta;
        dbHelper.UpdateTrip(speed, (int) (kmDelta * 1000), currentLat, currentLng, currentAlt);

        avgspeedDistance += kmDelta;
        double elapsedHours = (tickCounter * 0.2) / 3600.0;
        double averageSpeed = (elapsedHours > 0) ? (avgspeedDistance / elapsedHours) : 0.0;
        odoKm += kmDelta;

        tvAvgSpeed.setText(String.format(Locale.US, "%.1f", averageSpeed));
        tvTripA.setText(String.format(Locale.US, "%.1f", tripAKm));
        tvOdo.setText(String.format(Locale.US, "%,d", (int) odoKm));

        tvScooterVolt.setText(String.format(Locale.US, "🔋 %.1fV", volts));

        tvCoolantTemp.setText(coolantTemp + "°");
        if (coolantTemp > 91) {
            tvCoolantStatus.setText("WARM");
            tvCoolantStatus.setTextColor(Color.parseColor("#F59E0B"));
        } else {
            tvCoolantStatus.setText("NORMAL");
            tvCoolantStatus.setTextColor(Color.parseColor("#00E676"));
        }

        fuelBarView.setFuelPercent(fuelPct);
        tvFuelPct.setText(fuelPct + "%");
        tvFuelRange.setText(String.valueOf(Math.round(fuelPct * 2.9)));
        tvFuelLiters.setText(String.format(Locale.US, "%.1f L", (fuelPct / 100.0) * 12.2));

        if (tickCounter % 2 == 0) {
            blinkerPhase = !blinkerPhase;
            int activeColor = Color.parseColor("#00E676");
            int dimColor = Color.parseColor("#232A35");
            if (leftBlinkerOn) {
                tvTurnLeft.setTextColor(blinkerPhase ? activeColor : dimColor);
            }
            if (rightBlinkerOn) {
                tvTurnRight.setTextColor(blinkerPhase ? activeColor : dimColor);
            }
        }
    }

    private void UpdateVars() {
        String[] last_tripInfo = dbHelper.getTripInfo();
        try {
            startElapsedRealtime = Long.parseLong(last_tripInfo[1].trim());
            tripAKm = Double.parseDouble(last_tripInfo[0].trim());
        } catch (Exception e) {
            startElapsedRealtime = System.currentTimeMillis();
            tripAKm = 0;
        }

        // Only use fallback defaults when no live controller packet arrived in the last 2.5s
        boolean hasLiveSerial = (System.currentTimeMillis() - lastSerialPacketTimeMs) < SERIAL_TIMEOUT_MS;
        if (!hasLiveSerial) {
            gear = "D";
            volts = 76.0;
            rpm = 2000;
            speed = 0;
            controllerErrorCode = 0;
        }

        odoMetersBuffer += (speed * (1000.0 / 3600.0)) * 0.2;

        String odoDB = dbHelper.GetValueByKey("ODO");
        if (odoDB != null) {
            try {
                double c = Double.parseDouble(odoDB.trim());
                if (odoMetersBuffer >= 1.0) {
                    int wholeMeters = (int) odoMetersBuffer;
                    c += wholeMeters;
                    odoMetersBuffer -= wholeMeters;
                    dbHelper.EditKeyValue("ODO", String.valueOf(c));
                }
                odoKm = c / 1000.0;
            } catch (Exception e) {
                odoKm = 0;
            }
        }

        fuelPct = 64;
        coolantTemp = 84;
        leftBlinkerOn = false;
        rightBlinkerOn = false;

        String mode = ridingModes[0];
        tvRidingMode.setText(mode);
        if ("ECO".equals(mode)) {
            tvRidingMode.setTextColor(Color.parseColor("#00E676"));
        } else if ("CITY".equals(mode)) {
            tvRidingMode.setTextColor(Color.parseColor("#FFFFFF"));
        } else {
            tvRidingMode.setTextColor(Color.parseColor("#00E5FF"));
        }

        int pct = 89;
        tvTabletBatt.setText("▯ " + pct + "%");

        if (!CurrState.equals(prevState)) {
            prevState = CurrState;
            if (CurrState.equals(ScooterStatus[1])) {
                turnScreenOff();
                closeBluetooth();
            } else if (CurrState.equals(ScooterStatus[2])) {
                turnScreenOn();
                openBluetooth();
                tickCounter = 0;
                restartCockpit();
            }
        }
    }

    private void restartCockpit() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void turnScreenOff() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(this, CockpitDeviceAdmin.class);

        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            dpm.lockNow();
        } else {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allows Scooter Cockpit to turn off the display.");
            startActivity(intent);
        }
    }

    @SuppressWarnings("deprecation")
    private void turnScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isScreenOn()) {
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "ScooterCockpit:WakeScreen"
            );
            wakeLock.acquire(3000L);
        }
    }

    private void openBluetooth() {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && !btAdapter.isEnabled()) {
            btAdapter.enable();
        }
    }

    private void closeBluetooth() {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null && btAdapter.isEnabled()) {
            btAdapter.disable();
        }
    }

    private void setupMap() {
        if (mapView == null) return;

        mapView.setUseDataConnection(false);

        final ITileSource tileSource = new XYTileSource(
                "OSMPublicTransport",
                6, 16, 256, ".png",
                new String[]{"http://localhost/"}
        ) {
            @Override
            public Drawable getDrawable(InputStream aFileInputStream) {
                Bitmap bmp = BitmapFactory.decodeStream(aFileInputStream);
                if (bmp != null) {
                    return new BitmapDrawable(getResources(), bmp);
                }
                return null;
            }
        };

        File sqliteFile = new File(new File(getFilesDir(), "osmdroid"), "egypt.sqlite");
        String diag;

        try {
            SimpleRegisterReceiver receiver = new SimpleRegisterReceiver(getApplicationContext());
            IArchiveFile archive = DatabaseFileArchive.getDatabaseFileArchive(sqliteFile);

            MapTile testTile = new MapTile(14, 9613, 6757);
            InputStream is = archive.getInputStream(tileSource, testTile);
            if (is == null) {
                diag = "MAP ERR: NULL_STREAM";
            } else {
                Drawable d = tileSource.getDrawable(is);
                is.close();
                diag = (d != null)
                        ? "MAP OK: " + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
                        : "MAP ERR: NULL_DRAWABLE";
            }

            OfflineArchiveTileProvider offlineProvider =
                    new OfflineArchiveTileProvider(tileSource, archive);
            MapTileProviderArray providerArray = new MapTileProviderArray(
                    tileSource, receiver, new MapTileModuleProviderBase[]{offlineProvider});

            mapView.setTileProvider(providerArray);
        } catch (Exception e) {
            diag = "MAP EXC: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            Log.e(TAG, "setupMap failed", e);
        }

        Log.d(TAG, diag);
        Toast.makeText(this, diag, Toast.LENGTH_LONG).show();

        mapView.setBuiltInZoomControls(true);
        mapView.setMultiTouchControls(true);

        mapController = mapView.getController();
        mapController.setZoom(14);

        GeoPoint startPoint = getActiveMapPoint();
        mapController.setCenter(startPoint);

        scooterMarker = new Marker(mapView);
        scooterMarker.setPosition(startPoint);
        scooterMarker.setTitle("Scooter");
        mapView.getOverlays().add(scooterMarker);
        mapView.invalidate();

        btnSearchDest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerDestinationSearch();
            }
        });

        etDestination.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    triggerDestinationSearch();
                    return true;
                }
                return false;
            }
        });
    }

    private void triggerDestinationSearch() {
        String query = etDestination.getText().toString().trim();
        if (query.isEmpty()) return;

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etDestination.getWindowToken(), 0);
        }

        if (query.contains(",")) {
            String[] parts = query.split(",");
            if (parts.length == 2) {
                try {
                    double lat = Double.parseDouble(parts[0].trim());
                    double lng = Double.parseDouble(parts[1].trim());
                    setDestinationOnMap(lat, lng, "Pinned Destination");
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }

        Toast.makeText(this, "Searching...", Toast.LENGTH_SHORT).show();
        searchPlaceByName(query);
    }

    private void searchPlaceByName(final String placeName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String urlStr = "https://nominatim.openstreetmap.org/search?q="
                            + URLEncoder.encode(placeName, "UTF-8") + "&format=json&limit=1";
                    conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestProperty("User-Agent", "ScooterCockpitApp/1.0");
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONArray results = new JSONArray(sb.toString());
                    if (results.length() > 0) {
                        JSONObject first = results.getJSONObject(0);
                        final double lat = first.getDouble("lat");
                        final double lon = first.getDouble("lon");
                        final String displayName = first.optString("display_name", placeName);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setDestinationOnMap(lat, lon, displayName);
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "Location not found", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "Search failed (check internet or enter lat,lng)", Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    private void setDestinationOnMap(double destLat, double destLng, String title) {
        GeoPoint destPoint = new GeoPoint(destLat, destLng);
        GeoPoint startPoint = getActiveMapPoint();

        if (destMarker != null) mapView.getOverlays().remove(destMarker);
        if (directLine != null) mapView.getOverlays().remove(directLine);

        destMarker = new Marker(mapView);
        destMarker.setPosition(destPoint);
        destMarker.setTitle(title);
        mapView.getOverlays().add(destMarker);

        List<GeoPoint> points = new ArrayList<GeoPoint>();
        points.add(startPoint);
        points.add(destPoint);
        directLine = new Polyline();
        directLine.setPoints(points);
        directLine.setColor(Color.parseColor("#00E5FF"));
        directLine.setWidth(6.0f);
        mapView.getOverlays().add(directLine);

        mapController.setZoom(14);
        mapController.animateTo(destPoint);
        mapView.postInvalidate();
    }

    private void centerMapOnScooter() {
        GeoPoint pos = getActiveMapPoint();
        if (scooterMarker != null) scooterMarker.setPosition(pos);
        if (mapController != null) mapController.animateTo(pos);
        if (mapView != null) mapView.postInvalidate();
    }
}