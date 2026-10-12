package mkg20001.net.samremote;

import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mkg20001.net.samremotecommon.DeviceDiscovery;
import mkg20001.net.samremotecommon.RemoteController;
import mkg20001.net.samremotecommon.RemoteKey;
import mkg20001.net.samremotecommon.TvDevice;

public class Remote extends AppCompatActivity implements RemoteController.Observer {
    private AppRuntime runtime;
    private TextView status;
    private FloatingActionButton statusIcon;
    private List<View> buttons;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private DeviceDiscovery discovery;
    private long searchGeneration;
    private boolean active, searching;
    private RemoteKey shortcut;
    private AlertDialog chooser;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_remote);
        View root = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        runtime = AppRuntime.get(this);
        status = findViewById(R.id.state);
        statusIcon = findViewById(R.id.stateFAB);
        buttons = PhoneButtons.bind(root, runtime.controller, false);
        statusIcon.setOnClickListener(view -> showOptions());
        status.setOnClickListener(view -> showOptions());
        statusIcon.setOnLongClickListener(view -> {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        });
        readShortcut(getIntent());
        if (savedInstanceState != null && savedInstanceState.getBoolean("shortcut_consumed")) shortcut = null;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readShortcut(intent);
        if (runtime.controller.getState() == RemoteController.State.READY) sendShortcut();
    }

    private void readShortcut(Intent intent) {
        String action = intent.getAction();
        if (action != null && action.startsWith("samremote.")) {
            try { shortcut = RemoteKey.fromAction(action.substring("samremote.".length())); }
            catch (IllegalArgumentException ignored) { shortcut = null; }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("shortcut_consumed", shortcut == null);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        try {
            runtime.acquire(this);
            if (runtime.controller.getState() == RemoteController.State.DISCONNECTED) search(false);
        } catch (SecurityException e) { showStatus(R.string.network_denied, R.drawable.error); }
    }

    @Override protected void onStop() {
        active = false;
        cancelSearch();
        if (chooser != null) chooser.dismiss();
        runtime.release(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showOptions() {
        chooser = new AlertDialog.Builder(this).setTitle(R.string.select_tv)
                .setItems(new String[] { getString(R.string.search), getString(R.string.manual_ip), getString(R.string.about) },
                        (dialog, which) -> {
                            if (which == 0) search(true);
                            else if (which == 1) manualAddress();
                            else startActivity(new Intent(this, AboutActivity.class));
                        }).show();
    }

    private void search(boolean chooseAnother) {
        cancelSearch();
        runtime.controller.disconnect();
        DeviceDiscovery.Subnet subnet;
        try { subnet = runtime.wifiSubnet(); }
        catch (SecurityException e) { showStatus(R.string.network_denied, R.drawable.error); return; }
        if (subnet == null) { showStatus(R.string.no_wifi, R.drawable.error); return; }
        showStatus(R.string.searching, R.drawable.loading);
        searching = true;
        setButtonsEnabled(false);
        long attempt = ++searchGeneration;
        DeviceDiscovery scanner = new DeviceDiscovery();
        discovery = scanner;
        TvDevice remembered = chooseAnother ? null : runtime.controller.getDevice();
        searchExecutor.execute(() -> {
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                lock = wifi.createMulticastLock("SamRemote discovery");
                lock.acquire();
                List<TvDevice> devices = scanner.search(subnet, remembered);
                runtime.main.post(() -> {
                    if (!active || searchGeneration != attempt) return;
                    searching = false;
                    discovery = null;
                    DeviceDiscovery.Subnet current = runtime.wifiSubnet();
                    if (current == null || !current.address.equals(subnet.address)) {
                        showStatus(R.string.network_changed, R.drawable.error);
                        return;
                    }
                    showDevices(devices, remembered);
                });
            } catch (SecurityException e) { searchError(attempt, R.string.network_denied); }
            catch (Exception e) { searchError(attempt, R.string.search_failed); }
            finally {
                scanner.close();
                if (lock != null && lock.isHeld()) lock.release();
            }
        });
    }

    private void showDevices(List<TvDevice> devices, TvDevice remembered) {
        if (devices.isEmpty()) {
            showStatus(R.string.not_found_title, R.drawable.error);
            chooser = new AlertDialog.Builder(this).setMessage(R.string.discovery_empty)
                    .setPositiveButton(R.string.manual_ip, (dialog, which) -> manualAddress())
                    .setNegativeButton(R.string.search, (dialog, which) -> search(false)).show();
            return;
        }
        if (devices.size() == 1 && (devices.get(0).protocol == TvDevice.Protocol.TIZEN
                || remembered != null && remembered.id.equals(devices.get(0).id))) {
            runtime.connect(devices.get(0));
            return;
        }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < labels.length; i++) {
            TvDevice device = devices.get(i);
            labels[i] = device.protocol == TvDevice.Protocol.LEGACY
                    ? getString(R.string.legacy_candidate, device.host)
                    : getString(R.string.tv_address, device.name, device.host);
        }
        showStatus(R.string.select_tv, R.drawable.ic_remote_svg);
        chooser = new AlertDialog.Builder(this).setTitle(R.string.select_tv)
                .setItems(labels, (dialog, which) -> runtime.connect(devices.get(which)))
                .setNegativeButton(R.string.manual_ip, (dialog, which) -> manualAddress()).show();
    }

    private void manualAddress() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint(R.string.ip_hint);
        TvDevice selected = runtime.controller.getDevice();
        if (selected != null) input.setText(selected.host);
        chooser = new AlertDialog.Builder(this).setTitle(R.string.manual_ip).setView(input)
                .setPositiveButton(R.string.connect, null).setNegativeButton(R.string.cancel, null).create();
        chooser.setOnShowListener(dialog -> chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String host = input.getText().toString().trim();
            if (!DeviceDiscovery.isIpv4(host)) { input.setError(getString(R.string.invalid_ip)); return; }
            chooser.dismiss();
            probeAddress(host);
        }));
        chooser.show();
    }

    private void probeAddress(String host) {
        cancelSearch();
        runtime.controller.disconnect();
        DeviceDiscovery.Subnet subnet;
        try { subnet = runtime.wifiSubnet(); }
        catch (SecurityException e) { showStatus(R.string.network_denied, R.drawable.error); return; }
        if (subnet == null) { showStatus(R.string.no_wifi, R.drawable.error); return; }
        showStatus(R.string.searching, R.drawable.loading);
        searching = true;
        long attempt = ++searchGeneration;
        DeviceDiscovery scanner = new DeviceDiscovery();
        discovery = scanner;
        searchExecutor.execute(() -> {
            try {
                TvDevice device = scanner.probe(host);
                runtime.main.post(() -> {
                    if (!active || searchGeneration != attempt) return;
                    searching = false;
                    discovery = null;
                    DeviceDiscovery.Subnet current = runtime.wifiSubnet();
                    if (current == null || !current.address.equals(subnet.address)) {
                        showStatus(R.string.network_changed, R.drawable.error);
                        return;
                    }
                    if (device == null) { showStatus(R.string.not_found_title, R.drawable.error); return; }
                    TvDevice previous = runtime.controller.getDevice();
                    runtime.connect(previous != null && previous.id.equals(device.id)
                            ? device.withToken(previous.token) : device);
                });
            } catch (SecurityException e) { searchError(attempt, R.string.network_denied); }
            catch (Exception e) { searchError(attempt, R.string.search_failed); }
            finally { scanner.close(); }
        });
    }

    private void searchError(long attempt, int message) {
        runtime.main.post(() -> {
            if (!active || searchGeneration != attempt) return;
            searching = false;
            discovery = null;
            showStatus(message, R.drawable.error);
        });
    }

    private void cancelSearch() {
        ++searchGeneration;
        searching = false;
        if (discovery != null) discovery.close();
        discovery = null;
    }

    @Override public void onState(RemoteController.State state, TvDevice device) {
        if (!active || searching) return;
        int text;
        switch (state) {
            case READY: text = R.string.remote_online; break;
            case CONNECTING: text = R.string.connecting; break;
            case AWAITING_APPROVAL: text = R.string.approve_on_tv; break;
            case DENIED: text = R.string.access_denied; break;
            case TIMED_OUT: text = R.string.connection_timeout; break;
            case ERROR: text = R.string.connection_failed; break;
            default: text = R.string.offline;
        }
        showStatus(text, state == RemoteController.State.READY ? R.drawable.ic_remote_svg
                : state == RemoteController.State.CONNECTING || state == RemoteController.State.AWAITING_APPROVAL
                ? R.drawable.loading : R.drawable.error);
        setButtonsEnabled(state == RemoteController.State.READY);
        findViewById(R.id.key_play_pause).setContentDescription(getString(
                device != null && device.protocol == TvDevice.Protocol.LEGACY
                        ? R.string.legacy_play_pause_hint : R.string.control_play_pause));
        if (state == RemoteController.State.READY) sendShortcut();
    }

    private void sendShortcut() {
        if (shortcut == null) return;
        RemoteKey key = shortcut;
        shortcut = null;
        runtime.controller.sendKey(key);
    }

    private void showStatus(int text, int icon) {
        status.setText(text);
        statusIcon.setImageResource(icon);
        statusIcon.setContentDescription(getString(R.string.select_tv));
    }

    private void setButtonsEnabled(boolean enabled) {
        for (View button : buttons) button.setEnabled(enabled);
    }
}
