package com.scooter.cockpit;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BluetoothSpeakerPanel {

    private static final String TAG = "BtSpeakerPanel";

    public interface PanelCloseListener {
        void onCloseRequested();
    }

    private final Context context;
    private final BluetoothAdapter btAdapter;
    private final PanelCloseListener closeListener;

    private LinearLayout rootCard;
    private Button btnPowerToggle;
    private Button btnScan;
    private TextView tvBtStatus;
    private ListView lvDevices;

    // Now-playing music views
    private TextView tvTrackTitle;
    private TextView tvTrackArtist;
    private TextView tvTrackAlbum;
    private TextView tvPlayState;

    private final List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>();
    private final List<String> deviceLabels = new ArrayList<String>();
    private ArrayAdapter<String> listAdapter;

    private BluetoothDevice selectedDevice;
    private boolean isRegistered = false;

    public BluetoothSpeakerPanel(Context context, PanelCloseListener closeListener) {
        this.context = context;
        this.closeListener = closeListener;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        buildView();
    }

    public View getView() {
        return rootCard;
    }

    private void buildView() {
        rootCard = new LinearLayout(context);
        rootCard.setOrientation(LinearLayout.VERTICAL);
        rootCard.setBackgroundColor(Color.parseColor("#121821"));
        rootCard.setPadding(20, 16, 20, 16);

        // ---------------------------------------------------------------------
        // 1. HEADER ROW: TITLE + BLUETOOTH ON/OFF BUTTON + CLOSE BUTTON
        // ---------------------------------------------------------------------
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("BLUETOOTH SPEAKER");
        tvTitle.setTextColor(Color.parseColor("#00E5FF"));
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(tvTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        btnPowerToggle = new Button(context);
        btnPowerToggle.setTextSize(13f);
        btnPowerToggle.setTypeface(Typeface.DEFAULT_BOLD);
        btnPowerToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBluetoothPower();
            }
        });
        headerRow.addView(btnPowerToggle);

        Button btnClose = new Button(context);
        btnClose.setText("X");
        btnClose.setTextSize(13f);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.leftMargin = 10;
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (closeListener != null) {
                    closeListener.onCloseRequested();
                }
            }
        });
        headerRow.addView(btnClose, closeParams);

        rootCard.addView(headerRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------------------------------------------------------------------
        // 2. SCAN / DISCOVERABLE BAR + CONNECTION STATUS
        // ---------------------------------------------------------------------
        LinearLayout controlRow = new LinearLayout(context);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        controlParams.topMargin = 10;

        btnScan = new Button(context);
        btnScan.setText("SCAN & MAKE PAIRABLE");
        btnScan.setTextSize(12f);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScanAndDiscoverable();
            }
        });
        controlRow.addView(btnScan);

        tvBtStatus = new TextView(context);
        tvBtStatus.setTextColor(Color.LTGRAY);
        tvBtStatus.setTextSize(13f);
        tvBtStatus.setPadding(14, 0, 0, 0);
        tvBtStatus.setText("Ready");
        controlRow.addView(tvBtStatus, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        rootCard.addView(controlRow, controlParams);

        // ---------------------------------------------------------------------
        // 3. AVAILABLE & PAIRED DEVICES LIST
        // ---------------------------------------------------------------------
        TextView tvListLabel = new TextView(context);
        tvListLabel.setText("TAP A DEVICE TO PAIR (THEN SELECT TABLET ON LAPTOP/PHONE):");
        tvListLabel.setTextColor(Color.parseColor("#80CBC4"));
        tvListLabel.setTextSize(12f);
        tvListLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lblParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lblParams.topMargin = 12;
        rootCard.addView(tvListLabel, lblParams);

        lvDevices = new ListView(context);
        lvDevices.setBackgroundColor(Color.parseColor("#0B0F14"));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listParams.topMargin = 6;
        rootCard.addView(lvDevices, listParams);

        listAdapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, deviceLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(Color.WHITE);
                row.setTextSize(14f);
                row.setPadding(14, 10, 14, 10);
                return row;
            }
        };
        lvDevices.setAdapter(listAdapter);

        lvDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < deviceList.size()) {
                    pairDeviceAsReceiver(deviceList.get(position));
                }
            }
        });

        // ---------------------------------------------------------------------
        // 4. NOW PLAYING MUSIC METADATA CARD
        // ---------------------------------------------------------------------
        LinearLayout musicCard = new LinearLayout(context);
        musicCard.setOrientation(LinearLayout.VERTICAL);
        musicCard.setBackgroundColor(Color.parseColor("#1A2332"));
        musicCard.setPadding(14, 10, 14, 10);
        LinearLayout.LayoutParams musicParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        musicParams.topMargin = 12;

        LinearLayout musicHeader = new LinearLayout(context);
        musicHeader.setOrientation(LinearLayout.HORIZONTAL);

        TextView tvNowPlayingLbl = new TextView(context);
        tvNowPlayingLbl.setText("NOW PLAYING (BLUETOOTH AUDIO)");
        tvNowPlayingLbl.setTextColor(Color.parseColor("#00E5FF"));
        tvNowPlayingLbl.setTextSize(12f);
        tvNowPlayingLbl.setTypeface(Typeface.DEFAULT_BOLD);
        musicHeader.addView(tvNowPlayingLbl, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        tvPlayState = new TextView(context);
        tvPlayState.setText("IDLE");
        tvPlayState.setTextColor(Color.YELLOW);
        tvPlayState.setTextSize(12f);
        tvPlayState.setTypeface(Typeface.DEFAULT_BOLD);
        musicHeader.addView(tvPlayState);

        musicCard.addView(musicHeader);

        tvTrackTitle = new TextView(context);
        tvTrackTitle.setText("Track: No song playing");
        tvTrackTitle.setTextColor(Color.WHITE);
        tvTrackTitle.setTextSize(15f);
        tvTrackTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTrackTitle.setPadding(0, 4, 0, 2);
        musicCard.addView(tvTrackTitle);

        tvTrackArtist = new TextView(context);
        tvTrackArtist.setText("Artist: --");
        tvTrackArtist.setTextColor(Color.LTGRAY);
        tvTrackArtist.setTextSize(13f);
        musicCard.addView(tvTrackArtist);

        tvTrackAlbum = new TextView(context);
        tvTrackAlbum.setText("Album: --");
        tvTrackAlbum.setTextColor(Color.GRAY);
        tvTrackAlbum.setTextSize(12f);
        musicCard.addView(tvTrackAlbum);

        rootCard.addView(musicCard, musicParams);

        updatePowerUiState();
        loadPairedDevices();
    }

    public void register() {
        if (isRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        // AVRCP / system media track info broadcasts
        filter.addAction("com.android.music.metachanged");
        filter.addAction("com.android.music.playstatechanged");
        filter.addAction("com.android.bluetooth.avrcp.metachanged");
        filter.addAction("com.android.bluetooth.avrcp.playstatechanged");
        context.registerReceiver(btReceiver, filter);
        isRegistered = true;

        updatePowerUiState();
        loadPairedDevices();
    }

    public void unregister() {
        if (!isRegistered) return;
        try {
            context.unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        isRegistered = false;
    }

    public void release() {
        unregister();
    }

    private void toggleBluetoothPower() {
        if (btAdapter == null) {
            tvBtStatus.setText("Bluetooth hardware not supported");
            return;
        }
        if (btAdapter.isEnabled()) {
            btAdapter.disable();
            tvBtStatus.setText("Turning Bluetooth OFF...");
        } else {
            btAdapter.enable();
            tvBtStatus.setText("Turning Bluetooth ON...");
        }
    }

    private void updatePowerUiState() {
        if (btAdapter == null) {
            btnPowerToggle.setText("NO BT");
            btnPowerToggle.setEnabled(false);
            btnScan.setEnabled(false);
            return;
        }
        boolean enabled = btAdapter.isEnabled();
        btnPowerToggle.setText(enabled ? "BT: ON" : "BT: OFF");
        btnPowerToggle.setTextColor(enabled ? Color.parseColor("#76FF03") : Color.parseColor("#FF5252"));
        btnScan.setEnabled(enabled);
    }

    private void loadPairedDevices() {
        deviceList.clear();
        deviceLabels.clear();
        if (btAdapter != null && btAdapter.isEnabled()) {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    addOrUpdateDeviceRow(d, true);
                }
            }
        }
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void startScanAndDiscoverable() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            tvBtStatus.setText("Turn Bluetooth ON first");
            return;
        }

        // 1. Make tablet discoverable so the laptop/phone can connect to it as an audio sink
        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(discoverableIntent);
        } catch (Exception e) {
            Log.w(TAG, "Discoverable prompt skipped: " + e.getMessage());
        }

        // 2. Refresh paired list and start scanning for nearby devices
        loadPairedDevices();
        if (btAdapter.isDiscovering()) {
            btAdapter.cancelDiscovery();
        }
        btAdapter.startDiscovery();
        tvBtStatus.setText("Scanning for devices (300s discoverable)...");
    }

    private void addOrUpdateDeviceRow(BluetoothDevice device, boolean isPaired) {
        if (device == null) return;
        String name = device.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "Unknown Device";
        }
        String tag = isPaired ? "[PAIRED] " : "[AVAILABLE] ";
        String label = tag + name + " (" + device.getAddress() + ")";

        for (int i = 0; i < deviceList.size(); i++) {
            if (deviceList.get(i).getAddress().equals(device.getAddress())) {
                deviceLabels.set(i, label);
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
                return;
            }
        }
        deviceList.add(device);
        deviceLabels.add(label);
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void pairDeviceAsReceiver(BluetoothDevice device) {
        selectedDevice = device;
        if (btAdapter != null && btAdapter.isDiscovering()) {
            btAdapter.cancelDiscovery();
        }

        String devName = (device.getName() != null) ? device.getName() : device.getAddress();

        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            tvBtStatus.setText("Pairing with " + devName + "...");
            try {
                Method createBondMethod = device.getClass().getMethod("createBond");
                createBondMethod.invoke(device);
            } catch (Exception e) {
                tvBtStatus.setText("Pairing error: " + e.getMessage());
            }
        } else {
            // Do NOT initiate an outgoing A2DP connection—let the laptop/phone send audio to this tablet
            tvBtStatus.setText("Paired with " + devName + " — select this tablet as Speaker on your laptop/phone");
            tvPlayState.setText("WAITING FOR AUDIO");
        }
    }

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                updatePowerUiState();
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    tvBtStatus.setText("Bluetooth ON");
                    loadPairedDevices();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    tvBtStatus.setText("Bluetooth OFF");
                    deviceList.clear();
                    deviceLabels.clear();
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                }
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    boolean paired = (device.getBondState() == BluetoothDevice.BOND_BONDED);
                    addOrUpdateDeviceRow(device, paired);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (selectedDevice == null) {
                    tvBtStatus.setText("Scan complete (" + deviceList.size() + " devices)");
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (device != null && bondState == BluetoothDevice.BOND_BONDED) {
                    addOrUpdateDeviceRow(device, true);
                    String devName = (device.getName() != null) ? device.getName() : device.getAddress();
                    tvBtStatus.setText("Paired with " + devName + " — connect from laptop/phone audio settings");
                    tvPlayState.setText("WAITING FOR AUDIO");
                }
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                String devName = (device != null && device.getName() != null) ? device.getName() : "Device";
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    tvBtStatus.setText("Speaker Connected: " + devName);
                    tvPlayState.setText("CONNECTED");
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    tvBtStatus.setText("Disconnected from " + devName);
                    tvPlayState.setText("IDLE");
                }
            } else if ("com.android.music.metachanged".equals(action)
                    || "com.android.bluetooth.avrcp.metachanged".equals(action)) {
                String track = intent.getStringExtra("track");
                String artist = intent.getStringExtra("artist");
                String album = intent.getStringExtra("album");

                tvTrackTitle.setText("Track: " + (track != null ? track : "Unknown Track"));
                tvTrackArtist.setText("Artist: " + (artist != null ? artist : "Unknown Artist"));
                tvTrackAlbum.setText("Album: " + (album != null ? album : "--"));
                tvPlayState.setText("PLAYING");
            } else if ("com.android.music.playstatechanged".equals(action)
                    || "com.android.bluetooth.avrcp.playstatechanged".equals(action)) {
                boolean playing = intent.getBooleanExtra("playing", false);
                tvPlayState.setText(playing ? "PLAYING" : "PAUSED");
            }
        }
    };
}