package mkg20001.net.samremote;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import java.util.List;

import mkg20001.net.samremotecommon.RemoteController;

/** The tile dialog owns a session reference independently of the tile's listening lifecycle. */
final class QSDialog {
    static Dialog create(Context context, AppRuntime runtime, Runnable openApp) {
        View layout = LayoutInflater.from(context).inflate(R.layout.activity_remote_dialog, null);
        List<View> buttons = PhoneButtons.bind(layout, runtime.controller, true);
        RemoteController.Observer observer = (state, device) -> {
            for (View button : buttons) button.setEnabled(state == RemoteController.State.READY);
        };
        Dialog dialog = new AlertDialog.Builder(context).setView(layout).create();
        View close = layout.findViewById(R.id.dialog_close);
        close.setOnClickListener(view -> dialog.dismiss());
        close.setOnLongClickListener(view -> { dialog.dismiss(); openApp.run(); return true; });
        runtime.acquire(observer);
        dialog.setOnDismissListener(ignored -> runtime.release(observer));
        return dialog;
    }
}
