package mkg20001.net.samremote;

import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import mkg20001.net.samremotecommon.RemoteController;
import mkg20001.net.samremotecommon.RemoteKey;
import mkg20001.net.samremotecommon.TvDevice;

final class PhoneButtons {
    static List<View> bind(View root, RemoteController controller, boolean dialog) {
        int[] ids = dialog ? new int[] { R.id.dialog_poweroff, R.id.dialog_up, R.id.dialog_down,
                R.id.dialog_left, R.id.dialog_right, R.id.dialog_ok, R.id.dialog_enter,
                R.id.dialog_back, R.id.dialog_exit, R.id.dialog_volup, R.id.dialog_voldown,
                R.id.dialog_chup, R.id.dialog_chdown, R.id.dialog_source, R.id.dialog_hdmi,
                R.id.dialog_menu, R.id.imageButton3 }
                : new int[] { R.id.key_poweroff, R.id.key_up, R.id.key_down,
                R.id.key_left, R.id.key_right, R.id.key_ok, R.id.key_enter,
                R.id.key_back, R.id.key_exit, R.id.key_volup, R.id.key_voldown,
                R.id.key_chup, R.id.key_chdown, R.id.key_source, R.id.key_hdmi,
                R.id.key_menu, R.id.floatingActionButton2 };
        RemoteKey[] keys = { RemoteKey.POWEROFF, RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT,
                RemoteKey.RIGHT, RemoteKey.ENTER, RemoteKey.ENTER, RemoteKey.RETURN, RemoteKey.EXIT,
                RemoteKey.VOLUP, RemoteKey.VOLDOWN, RemoteKey.CHUP, RemoteKey.CHDOWN, RemoteKey.SOURCE,
                RemoteKey.HDMI, RemoteKey.MENU, RemoteKey.CHLIST };
        List<View> buttons = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            View button = root.findViewById(ids[i]);
            if (button == null) continue;
            RemoteKey key = keys[i];
            button.setOnClickListener(view -> controller.sendKey(key));
            buttons.add(button);
        }
        if (!dialog) {
            bind(root, R.id.key_back_circle, RemoteKey.RETURN, controller, buttons);
            bind(root, R.id.key_home, RemoteKey.HOME, controller, buttons);
            View playback = root.findViewById(R.id.key_play_pause);
            playback.setOnClickListener(view -> {
                TvDevice device = controller.getDevice();
                boolean legacy = device != null && device.protocol == TvDevice.Protocol.LEGACY;
                controller.sendKey(legacy ? RemoteKey.PAUSE : RemoteKey.PLAY_PAUSE);
                if (legacy) Toast.makeText(view.getContext(), R.string.legacy_play_pause_hint, Toast.LENGTH_SHORT).show();
            });
            playback.setOnLongClickListener(view -> {
                TvDevice device = controller.getDevice();
                if (device == null || device.protocol != TvDevice.Protocol.LEGACY) return false;
                controller.sendKey(RemoteKey.PLAY);
                return true;
            });
            buttons.add(playback);
        }
        return buttons;
    }

    private static void bind(View root, int id, RemoteKey key, RemoteController controller, List<View> buttons) {
        View button = root.findViewById(id);
        button.setOnClickListener(view -> controller.sendKey(key));
        buttons.add(button);
    }
}
