package au.example.zkc26ble;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelUuid;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental BLE peripheral/GATT server for a privately owned ZKC26B E4.
 * NO firmware erase/flashing, NO device pairing, NO cloud access.
 * User must explicitly arm the opt-in protocol sweep; then reconnections can resume it.
 * This reproduces the service structure in AgentMing/zkc42v-ai-usage,
 * but image encoding for the ZKC26B has NOT been confirmed.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_PERMS = 4, REQUEST_FILE = 5, REQUEST_EXPORT = 6;
    private static final UUID SVC = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb7");
    private static final UUID TX = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb8");
    private static final UUID ACK = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb9");
    private static final UUID AUX = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cba");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int COMPANY_ID = 0x0689;
    private static final int MAX_CHUNK = 230;
    // These conservative limits apply only to opt-in experimental auto-advance.
    private static final int MAX_AUTO_CHUNKS_PER_CONNECTION = 3;
    private static final long AUTO_REPLY_TIMEOUT_MS = 4000;
    private static final int MAX_SAVED_LOG_LINES = 2500;
    private static final int MAX_VISIBLE_LOG_LINES = 350;
    private static final long SWEEP_PACE_MS = 1000; // 1 packet/sec after success callback
    private static final long SWEEP_NOTIFY_TIMEOUT_MS = 3000;
    private static final long SWEEP_FINAL_REPLY_GRACE_MS = 4000;
    private static final long SWEEP_RECONNECT_SETTLE_MS = 250;
    private static final String PREFS_NAME = "zkc26_sweep_state";
    private static final int EXPECTED_FRAME_BYTES = 11248; // 152*296/8*2 (hypothesis, NOT validated)

    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothDevice connectedDevice;
    private volatile String targetMac = "A0:A3:10:56:CC:0E";
    private BluetoothGattCharacteristic txChar;
    private boolean subscribed = false, advertising = false;
    private int mtu = 23, cursor = 0, frameSendCount = 0;
    private byte[] frameBytes;
    private TextView logText, stateText, sweepStatusText;
    private EditText sweepStartField;
    private SharedPreferences sweepPrefs;
    private volatile int sweepNextIndex = 0;
    private volatile int sweepPendingIndex = -1;
    private volatile int sweepPendingEpoch = -1;
    private volatile int sweepLastSentIndex = -1;
    private volatile long sweepLastSentAtMs = 0L;
    private final AtomicBoolean sweepArmed = new AtomicBoolean(false);
    private volatile boolean sweepStoppedOnReply = false;
    private volatile boolean sweepCompleted = false;
    private final Object sweepFileLock = new Object();
    // Prevent a race where an already-delivered reply could be followed by one more send.
    private final Object sweepSendGuard = new Object();
    private File sweepLogFile;
    private final Runnable sweepNotifyTimeout = () -> {
        if (!sweepArmed.get() || sweepPendingIndex < 0) return;
        int pending = sweepPendingIndex;
        sweepPendingIndex = -1;
        pauseSweep("Missing Android notification callback for #" + pending + "; no blind retry");
    };
    private final Runnable sweepNextRunnable = this::sendSweepNext;

    private EditText macField;
    private CheckBox ackAdvance;
    private final ArrayList<String> lines = new ArrayList<>();
    private boolean stopped = false;
    private boolean autoAwaitingReply = false;
    private int autoChunksInConnection = 0;
    private long expectedReplyCounter = 0;
    private long lastChunkSentTimeMs = 0;
    private boolean transferStartedInThisConnection = false;
    // Distinguish fast reconnects from callbacks posted by an older connection.
    private volatile int connectionEpoch = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoTimeout = () -> {
        if (autoAwaitingReply) {
            autoAwaitingReply = false;
            disarmAuto("Timed out waiting for 30-byte reply after guarded auto chunk");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sweepPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sweepNextIndex = Math.max(0, Math.min(SweepPlan.TOTAL,
                sweepPrefs.getInt("next_index", 0)));
        String lastPath = sweepPrefs.getString("log_path", "");
        if (lastPath != null && !lastPath.isEmpty()) sweepLogFile = new File(lastPath);
        // A process restart never arms the sweep without a fresh user action.
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        // Android 15+/targetSdk35 draws behind system bars unless insets are handled.
        // Scroll padding protects the status/navigation bars; body padding adds selectable
        // space above the title and below the log so selection handles remain accessible.
        body.setPadding(pad, dp(24), pad, dp(72));
        scroll.addView(body);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            scroll.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(scroll);
        TextView heading = new TextView(this);
        heading.setText("ZKC26B BLE protocol probe (experimental)");
        heading.setTextSize(21);
        body.addView(heading);
        TextView caution = new TextView(this);
        caution.setText("Local BLE only. Never bonds or flashes. Image payload encoding remains UNKNOWN; sending may alter the display. Never transmit any firmware or OTA data.");
        body.addView(caution);
        macField = new EditText(this);
        macField.setSingleLine(true);
        macField.setHint("Tag MAC from NFC");
        macField.setText("A0:A3:10:56:CC:0E");
        body.addView(macField);
        stateText = new TextView(this); body.addView(stateText);
        button(body, "1. Start GATT server + advertiser", v -> start());
        button(body, "Stop and close", v -> stop());
        TextView sweepHeader = new TextView(this);
        sweepHeader.setText("Five-stage 1,280-packet sweep");
        sweepHeader.setTextSize(18);
        body.addView(sweepHeader);
        TextView sweepNote = new TextView(this);
        sweepNote.setText("Order: 00–FF; XX00; XX01; XX0000; XX0001. One packet per second. "
            + "STOP IMMEDIATELY on the first application write to 5CB9/5CBA, "
            + "regardless of reply length/content. Reconnects continue from the saved position. "
            + "Logs are saved automatically on-device after every event. Keep app open and screen on.");
        body.addView(sweepNote);
        sweepStartField = new EditText(this);
        sweepStartField.setSingleLine(true);
        sweepStartField.setHint("Start HEX: 00, A500, A501, A50000, A50001, or #index");
        sweepStartField.setText(SweepPlan.hexAt(Math.min(sweepNextIndex, SweepPlan.TOTAL - 1)));
        body.addView(sweepStartField);
        sweepStatusText = new TextView(this);
        body.addView(sweepStatusText);
        button(body, "START SWEEP from entered packet (new saved log)", v -> confirmStartSweep());
        button(body, "Resume from last saved position", v -> confirmResumeSweep());
        button(body, "Pause sweep (keep GATT server running)", v -> pauseSweep("Paused manually"));
        button(body, "Export last saved sweep log (.txt)", v -> exportSweepLog());
        button(body, "Copy log to clipboard", v -> copyLog());
        TextView imageHeader = new TextView(this);
        imageHeader.setText("Legacy image experiments (manual)");
        imageHeader.setTextSize(16);
        body.addView(imageHeader);
        button(body, "Send one 00 notification (sanity check)", v -> {
            if (sweepArmed.get()) log("Manual notification blocked while sweep armed");
            else notifyBytes(new byte[] {0});
        });
        button(body, "2. Load 11,248-byte raw two-plane candidate", v -> pickFile());
        button(body, "3. Send FIRST raw chunk ONLY (up to 230 bytes)", v -> confirmFirstChunk());
        button(body, "Send next raw chunk manually", v -> {
            if (ackAdvance.isChecked()) disarmAuto("Manual send overrides guarded auto mode");
            sendNextChunk(false);
        });
        ackAdvance = new CheckBox(this);
        ackAdvance.setText("Guarded auto on 30-byte replies (at most 3 extra chunks / connection; experimental)");
        ackAdvance.setChecked(false);
        ackAdvance.setOnCheckedChangeListener((b, checked) -> {
            if (checked) log("Guarded auto ARMED. Only a matching next counter and field 00000000 can advance; unknown fields STOP transfer. No auto start on connection.");
            else if (autoAwaitingReply) { autoAwaitingReply = false; mainHandler.removeCallbacks(autoTimeout); }
        });
        body.addView(ackAdvance);
        TextView note = new TextView(this);
        note.setText("No image data is sent on connection. First packet is manual. A 30-byte 5CBA reply is NOT a proven success ACK; guarded auto requires an explicit opt-in and stops on unusual replies, disconnect, or timeout. Display encoding is unverified.");
        body.addView(note);
        TextView logHeading = new TextView(this);
        logHeading.setText("Event log (selectable)");
        logHeading.setTextSize(16);
        body.addView(logHeading);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setPadding(0, dp(10), 0, dp(40));
        body.addView(logText);
        updateState();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void copyLog() {
        String all = android.text.TextUtils.join("\n", lines);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("ZKC26B BLE probe log", all));
            Toast.makeText(this, "Copied " + lines.size() + " log lines", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean createNewSweepLog() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "sweep_logs");
        if (!dir.exists() && !dir.mkdirs()) {
            log("ERROR: could not create log directory");
            return false;
        }
        String name = "ZKC26B_sweep_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".txt";
        File target = new File(dir, name);
        try {
            if (!target.createNewFile()) throw new IOException("Log filename already exists");
        } catch (IOException e) {
            log("ERROR creating sweep log: " + e);
            return false;
        }
        synchronized (sweepFileLock) { sweepLogFile = target; }
        sweepPrefs.edit().putString("log_path", target.getAbsolutePath()).commit();
        return true;
    }

    private void saveSweepProgress(String status) {
        sweepPrefs.edit().putInt("next_index", sweepNextIndex)
            .putString("sweep_status", status).commit();
    }

    private void confirmStartSweep() {
        final int index;
        try { index = SweepPlan.parseStart(sweepStartField.getText().toString()); }
        catch (IllegalArgumentException e) {
            sweepStartField.setError(e.getMessage());
            return;
        }
        if (server == null) {
            log("Start GATT server + advertiser before arming the sweep");
            return;
        }
        if (sweepArmed.get()) { log("Pause the running sweep first"); return; }
        new AlertDialog.Builder(this)
            .setTitle("Start controlled BLE sweep?")
            .setMessage("Will send " + (SweepPlan.TOTAL - index) + " 1–3 byte notifications at most once/second, "
                + "starting with " + SweepPlan.hexAt(index) + ". The first write to 5CB9 or 5CBA stops further tests immediately. "
                + "Unknown opcodes could change the tag's state. Keep this app in the foreground.")
            .setNegativeButton("Cancel", (d,w) -> {})
            .setPositiveButton("Start", (d,w) -> startSweep(index, true))
            .show();
    }

    private void confirmResumeSweep() {
        if (sweepArmed.get()) { log("Sweep already running"); return; }
        if (server == null) { log("Start GATT server + advertiser first"); return; }
        int index = sweepPrefs.getInt("next_index", 0);
        if (index >= SweepPlan.TOTAL) { log("Sweep already completed"); return; }
        String prevStatus = sweepPrefs.getString("sweep_status", "");
        if ("hit".equals(prevStatus)) {
            log("Previous run stopped on a response: resume BLOCKED. Enter a new start point explicitly if you wish to test again.");
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Resume saved sweep?")
            .setMessage("Continue at #" + index + " (" + SweepPlan.hexAt(index) + ")? "
                + "No packets will repeat unless an unconfirmed send was interrupted.")
            .setNegativeButton("Cancel", (d,w) -> {})
            .setPositiveButton("Resume", (d,w) -> startSweep(index, false))
            .show();
    }

    private void startSweep(int index, boolean newLog) {
        if (newLog || sweepLogFile == null || !sweepLogFile.exists()) {
            if (!createNewSweepLog()) return;
        }
        mainHandler.removeCallbacks(sweepNextRunnable);
        mainHandler.removeCallbacks(sweepNotifyTimeout);
        if (ackAdvance != null) ackAdvance.setChecked(false);
        sweepNextIndex = index;
        sweepPendingIndex = -1;
        sweepPendingEpoch = -1;
        sweepLastSentIndex = -1;
        sweepStoppedOnReply = false;
        sweepCompleted = false;
        saveSweepProgress("armed");
        sweepArmed.set(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        log("SWEEP START index=" + index + " payload=" + SweepPlan.hexAt(index)
            + " totalRemaining=" + (SweepPlan.TOTAL-index)
            + " paceMs=" + SWEEP_PACE_MS + " stopOnAny5CB9or5CBAWrite=true"
            + " destination=" + sweepLogFile.getAbsolutePath());
        scheduleSweepNext(SWEEP_RECONNECT_SETTLE_MS);
        updateState();
    }

    private void pauseSweep(String reason) {
        boolean wasActive = sweepArmed.getAndSet(false);
        mainHandler.removeCallbacks(sweepNextRunnable);
        mainHandler.removeCallbacks(sweepNotifyTimeout);
        sweepPendingIndex = -1;
        sweepPendingEpoch = -1;
        if (wasActive) {
            saveSweepProgress("paused");
            log("SWEEP PAUSED reason=" + reason + " nextIndex=" + sweepNextIndex);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateState();
    }

    private void scheduleSweepNext(long delayMs) {
        mainHandler.removeCallbacks(sweepNextRunnable);
        if (sweepArmed.get() && connectedDevice != null && subscribed && sweepPendingIndex < 0)
            mainHandler.postDelayed(sweepNextRunnable, delayMs);
    }

    private void sendSweepNext() {
        synchronized (sweepSendGuard) {
        if (!sweepArmed.get() || sweepPendingIndex >= 0 || !subscribed || connectedDevice == null) return;
        if (sweepNextIndex >= SweepPlan.TOTAL) {
            sweepCompleted = true;
            sweepArmed.set(false);
            saveSweepProgress("complete");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            log("SWEEP COMPLETE 1280/1280; no 5CB9/5CBA application write observed");
            return;
        }
        final int index = sweepNextIndex;
        final int sweepPendingEpochAtSend = connectionEpoch;
        final byte[] probe = SweepPlan.payload(index);
        sweepPendingIndex = index;
        sweepPendingEpoch = sweepPendingEpochAtSend;
        sweepLastSentIndex = index;
        sweepLastSentAtMs = SystemClock.elapsedRealtime();
        log("SWEEP SEND_ATTEMPT index=" + index + " hex=" + hex(probe) + " len=" + probe.length
            + " connEpoch=" + sweepPendingEpoch);
        mainHandler.removeCallbacks(sweepNotifyTimeout);
        if (!notifyBytes(probe)) {
            sweepPendingIndex = -1;
            sweepPendingEpoch = -1;
            if (connectedDevice == null || !subscribed || connectionEpoch != sweepPendingEpochAtSend) {
                log("SWEEP transient disconnect during send; waiting for reconnect at index=" + index);
            } else {
                pauseSweep("Android rejected notification index=" + index + "; no cursor advance");
            }
            return;
        }
        if (sweepArmed.get() && sweepPendingIndex == index)
            mainHandler.postDelayed(sweepNotifyTimeout, SWEEP_NOTIFY_TIMEOUT_MS);
        }
    }

    private void handleSweepNotificationCallback(int index, int epoch, int status) {
        if (index < 0 || epoch != connectionEpoch || index != sweepPendingIndex) return;
        mainHandler.removeCallbacks(sweepNotifyTimeout);
        sweepPendingIndex = -1;
        sweepPendingEpoch = -1;
        if (!sweepArmed.get()) return; // first reply already stopped sweep on callback thread
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (connectedDevice == null || !subscribed) {
                log("SWEEP notification failed during disconnect; retry after reconnect index=" + index);
            } else {
                pauseSweep("Notification failed, status=" + status + ", index=" + index);
            }
            return;
        }
        // Android callback is transport-level completion, not proof the tag understood it.
        sweepNextIndex = index + 1;
        saveSweepProgress("armed");
        log("SWEEP NOTIFY_CALLBACK_OK index=" + index + " hex=" + SweepPlan.hexAt(index)
            + " nextIndex=" + sweepNextIndex);
        scheduleSweepNext(sweepNextIndex == SweepPlan.TOTAL
            ? SWEEP_FINAL_REPLY_GRACE_MS : SWEEP_PACE_MS);
    }

    private void exportSweepLog() {
        if (sweepLogFile == null || !sweepLogFile.exists()) {
            log("No saved sweep log found"); return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, sweepLogFile.getName());
        startActivityForResult(i, REQUEST_EXPORT);
    }

    private void exportToUri(Uri uri) {
        File source = sweepLogFile;
        if (source == null || !source.exists()) { log("ERROR: saved sweep log missing"); return; }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Output stream unavailable");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
            Toast.makeText(this, "Sweep log exported", Toast.LENGTH_LONG).show();
            log("SWEEP LOG EXPORTED bytes=" + source.length());
        } catch (IOException e) { log("ERROR exporting sweep log: " + e); }
    }

    private void button(LinearLayout parent, String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(listener); parent.addView(b);
    }

    private void log(String msg) {
        String stamped = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(new Date()) + " " + msg;
        // Log to disk at the point of occurrence, not after a UI callback.
        // This preserves replies even if the Activity subsequently crashes.
        synchronized (sweepFileLock) {
            if (sweepLogFile != null) {
                try (FileOutputStream out = new FileOutputStream(sweepLogFile, true)) {
                    out.write((stamped + "\n").getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                } catch (IOException e) {
                    android.util.Log.e("ZKC26B", "Cannot persist sweep log", e);
                }
            }
        }
        runOnUiThread(() -> {
            lines.add(stamped);
            if (lines.size() > MAX_SAVED_LOG_LINES) lines.remove(0);
            if (logText != null) {
                int from = Math.max(0, lines.size() - MAX_VISIBLE_LOG_LINES);
                String visible = android.text.TextUtils.join("\n", lines.subList(from, lines.size()));
                if (from > 0) visible = "(Showing newest " + MAX_VISIBLE_LOG_LINES + " lines; Copy log includes " + lines.size() + ")\n" + visible;
                logText.setText(visible);
            }
            updateState();
        });
    }
    private void updateState() {
        if (stateText != null) stateText.setText("Server " + (server != null ? "ON" : "OFF")
            + " | Advertiser " + (advertising ? "ON" : "OFF")
            + " | Tag " + (connectedDevice != null ? "connected" : "not connected")
            + " | Subscribed " + subscribed + " | MTU " + mtu
            + " | Frame " + (frameBytes == null ? "not loaded" : frameBytes.length + " bytes")
            + " | Sent " + frameSendCount + " chunks"
            + " | Auto " + (ackAdvance != null && ackAdvance.isChecked() ? "ARMED" : "off")
            + " | Reply #" + expectedReplyCounter);
        if (sweepStatusText != null) {
            String label;
            if (sweepStoppedOnReply) label = "STOPPED: response captured";
            else if (sweepCompleted) label = "COMPLETED";
            else if (sweepArmed.get()) label = (connectedDevice != null && subscribed) ? "RUNNING" : "WAITING FOR TAG";
            else label = "PAUSED / IDLE";
            sweepStatusText.setText("Sweep " + label + " | next #" + sweepNextIndex
                + (sweepNextIndex < SweepPlan.TOTAL ? " " + SweepPlan.describe(sweepNextIndex) : " COMPLETE")
                + " | last sent " + (sweepLastSentIndex >= 0 ? SweepPlan.hexAt(sweepLastSentIndex) : "none")
                + " | log " + (sweepLogFile == null ? "not created" : sweepLogFile.getName()));
        }
    }
    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }
    private void start() {
        if (!hasBtPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE}, REQUEST_PERMS);
            }
            return;
        }
        stop();
        stopped = false;
        resetTransferPosition(false);
        String mac = macField.getText().toString().trim().toUpperCase(Locale.US);
        byte[] macBytes = parseMac(mac);
        if (macBytes == null) { log("ERROR: MAC must be six bytes (from NFC)"); return; }
        targetMac = mac;
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled() || !adapter.isMultipleAdvertisementSupported()) {
            log("ERROR: enable Bluetooth and verify peripheral advertising support"); return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) { log("ERROR: no BLE advertiser"); return; }
        server = manager.openGattServer(this, gattCallback);
        if (server == null) { log("ERROR: cannot open GATT server"); return; }
        BluetoothGattServiceHolder.add(server);
        log("GATT server opened, waiting for service registration BEFORE advertising");
    }
    // Wrapper avoids reliance on Activity lifecycle timing for service registration.
    private static class BluetoothGattServiceHolder {
        static void add(BluetoothGattServer server) {
            BluetoothGattCharacteristic tx = new BluetoothGattCharacteristic(TX,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0);
            tx.addDescriptor(new BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
            BluetoothGattCharacteristic ack = new BluetoothGattCharacteristic(ACK,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
            BluetoothGattCharacteristic aux = new BluetoothGattCharacteristic(AUX,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            android.bluetooth.BluetoothGattService svc = new android.bluetooth.BluetoothGattService(SVC,
                    android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(tx);
            svc.addCharacteristic(ack);
            svc.addCharacteristic(aux);
            server.addService(svc);
        }
    }
    private void startAdvertising() {
        if (stopped || server == null || advertiser == null) return;
        byte[] macBytes = parseMac(targetMac);
        if (macBytes == null) { log("ERROR: invalid MAC"); return; }
        byte[] body = new byte[7];
        body[0] = 0x08;
        System.arraycopy(macBytes, 0, body, 1, 6);
        // Company ID 0x0689 derives from full payload [89 06 08 A0 A3 10 56 CC 0E].
        // MUST recompute checksum for any other MAC; company-ID low byte is checksum.
        int checksum = 0x06 ^ 0x08;
        for (byte b : macBytes) checksum ^= (b & 0xff);
        int company = (0x06 << 8) | checksum;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setTimeout(0)
                .build();
        AdvertiseData primary = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(company, body)
                .build();
        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SVC))
                .build();
        advertiser.startAdvertising(settings, primary, scanResponse, advCallback);
        log("Starting legacy advertising, company ID=" + String.format(Locale.US,"0x%04X",company)
                + ", data=" + hex(body) + ", UUID=...5CB7 in scan response");
    }
    private byte[] parseMac(String input) {
        String s = input.replace(":", "").replace("-", "").replace(" ", "");
        if (!s.matches("(?i)[0-9a-f]{12}")) return null;
        byte[] v = new byte[6];
        for (int i = 0; i < 6; i++) v[i] = (byte) Integer.parseInt(s.substring(i*2,i*2+2),16);
        return v;
    }
    private static String hex(byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (byte b : v) sb.append(String.format(Locale.US, "%02X", b & 0xff));
        return sb.toString();
    }
    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true; log("Advertiser started");
        }
        @Override public void onStartFailure(int code) {
            advertising = false; log("ERROR: advertiser failed, code=" + code);
        }
    };
    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, android.bluetooth.BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("ERROR: service add failed status="+status); return; }
            txChar = service.getCharacteristic(TX);
            log("Registered 5CB7 service with 5CB8, 5CB9, 5CBA; now advertising");
            startAdvertising();
        }
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            final int currentEpoch = ++connectionEpoch;
            // Clear any unconfirmed send immediately on the callback thread. With rapid
            // reconnects, a delayed UI disconnect callback must not block resumed work.
            sweepPendingIndex = -1;
            sweepPendingEpoch = -1;
            mainHandler.removeCallbacks(sweepNextRunnable);
            mainHandler.removeCallbacks(sweepNotifyTimeout);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    if (!device.getAddress().equalsIgnoreCase(targetMac)) {
                        log("UNEXPECTED BLE CLIENT " + device.getAddress() + "; rejecting");
                        if (server != null) server.cancelConnection(device);
                        return;
                    }
                } catch (SecurityException se) {
                    log("ERROR: cannot verify BLE client identity");
                    if (server != null) server.cancelConnection(device);
                    return;
                }
                connectedDevice = device; subscribed = false; mtu=23;
                runOnUiThread(() -> {
                    if (currentEpoch != connectionEpoch) return;
                    resetTransferPosition(true);
                    disarmAuto("New connection: guarded auto requires manual rearming");
                });
                try { log("Remote connected: " + device.getAddress() + " status="+status); }
                catch (SecurityException se) { log("Connected but BLUETOOTH_CONNECT permission denied"); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Remote disconnected: " + status);
                if (connectedDevice != null && connectedDevice.equals(device)) connectedDevice = null;
                subscribed = false;
                runOnUiThread(() -> {
                    if (currentEpoch != connectionEpoch) return;
                    mainHandler.removeCallbacks(sweepNextRunnable);
                    mainHandler.removeCallbacks(sweepNotifyTimeout);
                    sweepPendingIndex = -1;
                    sweepPendingEpoch = -1;
                    if (sweepArmed.get()) log("SWEEP WAITING_RECONNECT nextIndex=" + sweepNextIndex);
                    disarmAuto("Disconnected: halted guarded transfer");
                    resetTransferPosition(true);
                });
            }
        }
        @Override public void onMtuChanged(BluetoothDevice device, int nextMtu) {
            mtu = nextMtu; log("MTU=" + nextMtu);
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor d) {
            byte[] v = subscribed ? new byte[] {1,0} : new byte[] {0,0};
            if (server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,offset,v);
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor desc,
            boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            boolean cccd = desc.getUuid().equals(CCCD);
            if (cccd && value != null && value.length >= 2) {
                subscribed = value[0] == 1 && value[1] == 0;
                log("Remote CCCD="+hex(value)+" (subscribed="+subscribed+")");
                final int thisEpoch = connectionEpoch;
                if (subscribed) runOnUiThread(() -> {
                    if (thisEpoch == connectionEpoch && sweepArmed.get()) {
                        log("SWEEP subscription ready: continuing at index=" + sweepNextIndex);
                        scheduleSweepNext(SWEEP_RECONNECT_SETTLE_MS);
                    }
                });
            } else log("Descriptor write uuid="+desc.getUuid()+" data="+hex(value));
            if (responseNeeded && server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
             BluetoothGattCharacteristic c) {
            log("Remote read "+c.getUuid());
            if (server != null) server.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,offset,new byte[] {0});
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device,int requestId,BluetoothGattCharacteristic c,
             boolean prepared,boolean responseNeeded,int offset,byte[] value) {
            final byte[] dataCopy = value == null ? new byte[0] : value.clone();
            final UUID which = c.getUuid();
            final boolean applicationReply = which.equals(AUX) || which.equals(ACK);
            // CRITICAL: atomically halt at the start of the Bluetooth callback,
            // BEFORE posting to the UI or completing the write response.
            boolean sweepHit = false;
            if (applicationReply) {
                synchronized (sweepSendGuard) { sweepHit = sweepArmed.getAndSet(false); }
            }
            if (sweepHit) {
                final int hitIndex = sweepLastSentIndex;
                final long age = SystemClock.elapsedRealtime() - sweepLastSentAtMs;
                sweepStoppedOnReply = true;
                log("*** SWEEP HIT STOP IMMEDIATE *** characteristic=" + which
                    + " replyLen=" + dataCopy.length + " replyHex=" + hex(dataCopy)
                    + " lastSentIndex=" + hitIndex
                    + " lastSentHex=" + (hitIndex >= 0 ? SweepPlan.hexAt(hitIndex) : "none")
                    + " ageMs=" + age + " nextSavedIndex=" + sweepNextIndex);
                sweepPrefs.edit().putString("sweep_status", "hit").commit();
                runOnUiThread(() -> {
                    mainHandler.removeCallbacks(sweepNextRunnable);
                    mainHandler.removeCallbacks(sweepNotifyTimeout);
                    sweepPendingIndex = -1;
                    sweepPendingEpoch = -1;
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    updateState();
                    Toast.makeText(MainActivity.this, "SWEEP STOPPED: tag replied! Export log.", Toast.LENGTH_LONG).show();
                });
            }
            log("Remote WRITE " + which.toString().substring(32) + " len=" +
                  dataCopy.length + " data=" + hex(dataCopy));
            if (responseNeeded && server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            if (applicationReply) {
                final int epochAtWrite = connectionEpoch;
                runOnUiThread(() -> {
                    if (epochAtWrite == connectionEpoch) handleTagWrite(c.getUuid(), dataCopy);
                    else log("Ignoring reply from prior connection epoch");
                });
            }
        }
        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            final int pending = sweepPendingIndex;
            final int epoch = sweepPendingEpoch;
            log("Notification callback status="+status+" (does not prove tag processed bytes)");
            if (pending >= 0) {
                runOnUiThread(() -> handleSweepNotificationCallback(pending, epoch, status));
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread(() -> disarmAuto("Android notification callback failed: " + status));
            }
        }
    };
    private boolean notifyBytes(byte[] value) {
        if (server == null || connectedDevice == null || !subscribed || txChar == null) {
            log("Not sent: device not connected or 5CB8 notifications not enabled"); return false;
        }
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                int result = server.notifyCharacteristicChanged(connectedDevice, txChar, false, value);
                accepted = result == 0;
                log("notifyCharacteristicChanged result="+result+" data="+(value.length < 40 ? hex(value) : "len="+value.length));
            } else {
                txChar.setValue(value);
                accepted = server.notifyCharacteristicChanged(connectedDevice, txChar, false);
                log("notifyCharacteristicChanged accepted="+accepted+" data="+(value.length < 40 ? hex(value) : "len="+value.length));
            }
            return accepted;
        } catch (Exception e) { log("ERROR notifying: "+e.getMessage()); return false; }
    }
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/octet-stream");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/octet-stream","application/x-binary", "*/*"});
        startActivityForResult(i, REQUEST_FILE);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                exportToUri(data.getData());
            return;
        }
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream stream = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[4096]; int n;
            while ((n = stream.read(tmp)) >= 0) { if (n > 0) out.write(tmp,0,n); if (out.size() > 2*EXPECTED_FRAME_BYTES) break; }
            byte[] bytes = out.toByteArray();
            if (bytes.length != EXPECTED_FRAME_BYTES) {
                log("REJECTED frame len=" + bytes.length + " (expected EXACTLY 11248 raw bytes, no header)");
            } else {
                frameBytes = bytes;
                resetTransferPosition(false);
                disarmAuto("New image loaded: auto disarmed");
                log("Loaded 11248-byte raw TWO-PLANE candidate, not yet sent");
            }
        } catch (Exception e) { log("ERROR loading frame: "+e.getMessage()); }
    }
    private void confirmFirstChunk() {
        if (sweepArmed.get()) { log("Pause sweep before image transfer"); return; }
        if (frameBytes == null) { log("Load a candidate file first"); return; }
        new AlertDialog.Builder(this)
           .setTitle("Send experimental image chunk?")
           .setMessage("This uses the ZKC42V experimental raw two-plane format. ZKC26B encoding is UNVERIFIED. It may overwrite display data or cause an unexpected refresh. Send ONLY the first chunk?")
           .setNegativeButton("Cancel",(d,w)->{})
           .setPositiveButton("Send first chunk",(d,w)->{
               if (cursor != 0) { log("First chunk already sent. Use next manual chunk, or reset on disconnect."); return; }
               sendNextChunk(false);
           })
           .show();
    }
    private boolean sendNextChunk(boolean fromAuto) {
        if (sweepArmed.get()) { log("Image transfer blocked while sweep armed"); return false; }
        if (frameBytes == null || cursor >= frameBytes.length) {
            log("No pending image bytes"); return false;
        }
        if (connectedDevice == null || !subscribed) { log("Not sent: not subscribed"); return false; }
        int len = Math.min(MAX_CHUNK, frameBytes.length-cursor);
        if (len > mtu - 3) { log("Not sent: MTU="+mtu+" insufficient for planned len="+len); return false; }
        byte[] chunk = Arrays.copyOfRange(frameBytes,cursor,cursor+len);
        if (!notifyBytes(chunk)) {
            log("Chunk rejected by Android; cursor NOT advanced");
            if (fromAuto) disarmAuto("Android rejected automatic notification");
            return false;
        }
        cursor += len; frameSendCount++;
        transferStartedInThisConnection = true;
        lastChunkSentTimeMs = SystemClock.elapsedRealtime();
        log("Sent " + (fromAuto ? "AUTO " : "manual ") + "candidate chunk="+frameSendCount+
            " offset="+(cursor-len)+" len="+len+" remaining="+(frameBytes.length-cursor));
        if (fromAuto) {
            autoChunksInConnection++;
            autoAwaitingReply = true;
            mainHandler.removeCallbacks(autoTimeout);
            mainHandler.postDelayed(autoTimeout, AUTO_REPLY_TIMEOUT_MS);
        }
        return true;
    }
    private void handleTagWrite(UUID which, byte[] value) {
        String suffix = which.equals(AUX) ? "5CBA" : "5CB9";
        Reply30 reply = Reply30.parse(value, parseMac(macField.getText().toString().trim()));
        if (reply == null) {
            log(suffix + " unrecognised reply (len=" + value.length + "); not treated as ACK");
            if (ackAdvance.isChecked()) disarmAuto("Unrecognised reply; stop automated transfer");
            return;
        }
        log("DECODE " + suffix + " counter=" + reply.counter
            + " field16-19=0x" + reply.field16to19
            + " trailer26-29=0x" + reply.trailer26to29
            + " (meaning unknown; NOT proven ACK)");

        // A connection-local counter was observed 0,1,2,3, and restarted on reconnect.
        if (reply.counter != expectedReplyCounter) {
            log("COUNTER MISMATCH: expected=" + expectedReplyCounter + " got=" + reply.counter);
            disarmAuto("Reply counter mismatch");
            return;
        }
        expectedReplyCounter++;
        if (autoAwaitingReply) {
            autoAwaitingReply = false;
            mainHandler.removeCallbacks(autoTimeout);
        }
        if (!ackAdvance.isChecked()) return;
        if (!which.equals(AUX)) { disarmAuto("Expected 5CBA, got " + suffix); return; }
        if (!transferStartedInThisConnection || frameBytes == null || cursor == 0) {
            disarmAuto("No image transfer started in this connection"); return;
        }
        if (SystemClock.elapsedRealtime() - lastChunkSentTimeMs > AUTO_REPLY_TIMEOUT_MS) {
            disarmAuto("Reply too late to correlate with last chunk"); return;
        }
        // The nonzero FF fields might signal an error; until decoded we only allow
        // a single observed all-zero variant to trigger an experimental next chunk.
        if (!reply.field16to19.equals("00000000")) {
            disarmAuto("Unknown field16-19=0x" + reply.field16to19 + "; no automatic advance");
            return;
        }
        if (autoChunksInConnection >= MAX_AUTO_CHUNKS_PER_CONNECTION) {
            disarmAuto("Reached 3 automatic chunks in this connection"); return;
        }
        if (!sendNextChunk(true)) disarmAuto("Cannot send next chunk");
    }

    private void resetTransferPosition(boolean connectionChange) {
        mainHandler.removeCallbacks(autoTimeout);
        autoAwaitingReply = false;
        autoChunksInConnection = 0;
        expectedReplyCounter = 0;
        lastChunkSentTimeMs = 0;
        transferStartedInThisConnection = false;
        if (frameSendCount > 0 || cursor > 0) {
            log("Image cursor reset to zero (" + (connectionChange ? "connection change" : "new image/session") + "). No automatic resume.");
        }
        cursor = 0;
        frameSendCount = 0;
    }

    private void disarmAuto(String reason) {
        mainHandler.removeCallbacks(autoTimeout);
        autoAwaitingReply = false;
        if (ackAdvance != null && ackAdvance.isChecked()) {
            ackAdvance.setChecked(false);
            log("AUTO DISARMED: " + reason);
        }
    }

    private void stop() {
        pauseSweep("GATT server stopped");
        stopped=true;
        connectionEpoch++;
        mainHandler.removeCallbacks(autoTimeout);
        autoAwaitingReply = false;
        if (ackAdvance != null) ackAdvance.setChecked(false);
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advCallback); } catch(Exception ignored) {}
        }
        advertising=false;
        if (server != null) {
            try { server.clearServices(); server.close(); } catch(Exception ignored) {}
            server=null;
        }
        connectedDevice=null;txChar=null;subscribed=false;
        if (logText != null) log("Stopped");
    }
    @Override protected void onDestroy() { stop(); super.onDestroy(); }
}