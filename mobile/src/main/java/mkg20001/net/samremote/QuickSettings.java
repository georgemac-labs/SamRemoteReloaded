package mkg20001.net.samremote;

import android.annotation.TargetApi;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import mkg20001.net.samremotecommon.RemoteController;
import mkg20001.net.samremotecommon.TvDevice;

@TargetApi(24)
public class QuickSettings extends TileService implements RemoteController.Observer {
    private AppRuntime runtime;
    private Dialog dialog;
    private boolean listening;
    private boolean pendingClick;

    @Override public void onStartListening() {
        runtime = AppRuntime.get(this);
        listening = true;
        runtime.acquire(this);
    }

    @Override public void onStopListening() {
        listening = false;
        if (runtime != null && !pendingClick) runtime.release(this);
    }

    @Override public void onDestroy() {
        if (dialog != null) dialog.dismiss();
        if (runtime != null) runtime.release(this);
        super.onDestroy();
    }

    @Override public void onClick() {
        if (runtime == null) runtime = AppRuntime.get(this);
        if (isLocked()) { unlockAndRun(this::openApp); return; }
        if (runtime.isRemoteVisible()) { openApp(); return; }
        if (runtime.controller.getState() != RemoteController.State.READY) {
            if (runtime.controller.getDevice() == null || runtime.wifiSubnet() == null) { openApp(); return; }
            pendingClick = true;
            runtime.acquire(this);
            if (runtime.controller.getState() != RemoteController.State.CONNECTING
                    && runtime.controller.getState() != RemoteController.State.AWAITING_APPROVAL) {
                runtime.connect(runtime.controller.getDevice());
            }
            return;
        }
        showControls();
    }

    private void showControls() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = QSDialog.create(this, runtime, this::openApp);
        showDialog(dialog);
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent overload is required below API 34.
    private void openApp() {
        Intent intent = new Intent(this, Remote.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    @Override public void onState(RemoteController.State state, TvDevice device) {
        if (listening && getQsTile() != null) {
            Tile tile = getQsTile();
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_remote_svg));
            tile.setLabel(getString(state == RemoteController.State.READY ? R.string.remote_online : R.string.app_name));
            tile.setState(state == RemoteController.State.READY ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        }
        // Pairing and selection require the foreground phone UI; the tile never waits invisibly for approval.
        if (pendingClick && state == RemoteController.State.READY) {
            pendingClick = false;
            showControls();
            if (!listening) runtime.release(this);
        } else if (pendingClick && state != RemoteController.State.CONNECTING
                && state != RemoteController.State.DISCONNECTED) {
            pendingClick = false;
            openApp();
            if (!listening) runtime.release(this);
        }
    }
}
