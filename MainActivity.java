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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Experimental BLE peripheral/GATT server for a privately owned ZKC26B E4.
 * NO firmware erase/flashing, NO device pairing, NO cloud access.
 * User must explicitly press a send button to transmit image data.
 * This reproduces the service structure in AgentMing/zkc42v-ai-usage,
 * but image encoding for the ZKC26B has NOT been confirmed.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_PERMS = 4, REQUEST_FILE = 5;
    private static final UUID SVC = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb7");
    private static final UUID TX = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb8");
    private static final UUID ACK = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cb9");
    private static final UUID AUX = UUID.fromString("0783b03e-8535-b5a0-7140-a304d2495cba");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int COMPANY_ID = 0x0689;
    private static final int MAX_CHUNK = 230;
    private static final int EXPECTED_FRAME_BYTES = 11248; // 152*296/8*2 (hypothesis, NOT validated)

    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothDevice connectedDevice;
    private BluetoothGattCharacteristic txChar;
    private boolean subscribed = false, advertising = false;
    private int mtu = 23, cursor = 0, frameSendCount = 0;
    private byte[] frameBytes;
    private TextView logText, stateText;
    private EditText macField;
    private CheckBox ackAdvance;
    private final ArrayList<String> lines = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private boolean stopped = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
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
        button(body, "Send one 00 notification (sanity check)", v -> notifyBytes(new byte[] {0}));
        button(body, "2. Load 11,248-byte raw two-plane candidate", v -> pickFile());
        button(body, "3. Send FIRST raw chunk ONLY (up to 230 bytes)", v -> confirmFirstChunk());
        button(body, "Send next raw chunk manually", v -> sendNextChunk());
        ackAdvance = new CheckBox(this);
        ackAdvance.setText("Automatically send next chunk on ACK >=35 bytes (UNVERIFIED on ZKC26B)");
        ackAdvance.setChecked(false);
        body.addView(ackAdvance);
        TextView note = new TextView(this);
        note.setText("No image data is sent automatically on connection. The first chunk requires explicit confirmation. Log writes on 5CB9/5CBA and check the tag screen.");
        body.addView(note);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextIsSelectable(true);
        body.addView(logText);
        updateState();
    }

    private void button(LinearLayout parent, String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(listener); parent.addView(b);
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            String time = clock.format(new Date());
            lines.add(time + " " + msg);
            if (lines.size() > 160) lines.remove(0);
            if (logText != null) logText.setText(android.text.TextUtils.join("\n", lines));
            updateState();
        });
    }
    private void updateState() {
        if (stateText != null) stateText.setText("Server " + (server != null ? "ON" : "OFF")
            + " | Advertiser " + (advertising ? "ON" : "OFF")
            + " | Tag " + (connectedDevice != null ? "connected" : "not connected")
            + " | Subscribed " + subscribed + " | MTU " + mtu
            + " | Frame " + (frameBytes == null ? "not loaded" : frameBytes.length + " bytes")
            + " | Sent " + frameSendCount + " chunks");
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
        String mac = macField.getText().toString().trim().toUpperCase(Locale.US);
        byte[] macBytes = parseMac(mac);
        if (macBytes == null) { log("ERROR: MAC must be six bytes (from NFC)"); return; }
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
        byte[] macBytes = parseMac(macField.getText().toString().trim());
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device; subscribed = false; mtu=23;
                try { log("Remote connected: " + device.getAddress() + " status="+status); }
                catch (SecurityException se) { log("Connected but BLUETOOTH_CONNECT permission denied"); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Remote disconnected: " + status);
                if (connectedDevice != null && connectedDevice.equals(device)) connectedDevice = null;
                subscribed = false;
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
            log("Remote WRITE " + c.getUuid().toString().substring(32) + " len=" +
                  (value==null ? 0:value.length) + " data=" + (value == null ? "null" : hex(value)));
            if (responseNeeded && server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
            if (value != null && value.length >= 35) {
                // Heuristic from AgentMing ZKC42V experimental server.
                runOnUiThread(() -> {
                    if (ackAdvance.isChecked() && frameBytes != null && cursor > 0) sendNextChunk();
                });
            }
        }
        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            log("Notification callback status="+status+" (does not prove tag processed bytes)");
        }
    };
    private void notifyBytes(byte[] value) {
        if (server == null || connectedDevice == null || !subscribed || txChar == null) {
            log("Not sent: device not connected or 5CB8 notifications not enabled"); return;
        }
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 33) {
                result = server.notifyCharacteristicChanged(connectedDevice, txChar, false, value);
                log("notifyCharacteristicChanged result="+result+" data="+(value.length < 40 ? hex(value) : "len="+value.length));
            } else {
                txChar.setValue(value);
                boolean ok = server.notifyCharacteristicChanged(connectedDevice, txChar, false);
                log("notifyCharacteristicChanged accepted="+ok+" data="+(value.length < 40 ? hex(value) : "len="+value.length));
            }
        } catch (Exception e) { log("ERROR notifying: "+e.getMessage()); }
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
                frameBytes = bytes; cursor=0; frameSendCount=0;
                log("Loaded 11248-byte raw TWO-PLANE candidate, not yet sent");
            }
        } catch (Exception e) { log("ERROR loading frame: "+e.getMessage()); }
    }
    private void confirmFirstChunk() {
        if (frameBytes == null) { log("Load a candidate file first"); return; }
        new AlertDialog.Builder(this)
           .setTitle("Send experimental image chunk?")
           .setMessage("This uses the ZKC42V experimental raw two-plane format. ZKC26B encoding is UNVERIFIED. It may overwrite display data or cause an unexpected refresh. Send ONLY the first chunk?")
           .setNegativeButton("Cancel",(d,w)->{})
           .setPositiveButton("Send first chunk",(d,w)->sendNextChunk())
           .show();
    }
    private void sendNextChunk() {
        if (frameBytes == null || cursor >= frameBytes.length) {
            log("No pending image bytes"); return;
        }
        if (connectedDevice == null || !subscribed) { log("Not sent: not subscribed"); return; }
        int len = Math.min(MAX_CHUNK, frameBytes.length-cursor);
        // Require ATT payload fits MTU; with negotiated MTU247, 230 is safe.
        if (len > mtu - 3) { log("Not sent: MTU="+mtu+" insufficient for planned len="+len); return; }
        byte[] chunk = Arrays.copyOfRange(frameBytes,cursor,cursor+len);
        notifyBytes(chunk);
        cursor += len; frameSendCount++;
        log("Sent candidate chunk="+frameSendCount+" offset="+(cursor-len)+" len="+len+" remaining="+(frameBytes.length-cursor));
    }
    private void stop() {
        stopped=true;
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
