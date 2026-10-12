package mkg20001.net.samremotecommon;

import java.util.Locale;

public enum RemoteKey {
    UP, DOWN, LEFT, RIGHT, ENTER, EXIT, RETURN, VOLUP, VOLDOWN, CHUP, CHDOWN,
    SOURCE, HDMI, MENU, CHLIST, POWEROFF, HOME, PLAY_PAUSE, PLAY, PAUSE;

    public String wireName(TvDevice.Protocol protocol) {
        if (this == HOME) return protocol == TvDevice.Protocol.TIZEN ? "KEY_HOME" : "KEY_CONTENTS";
        if (this == PLAY_PAUSE) {
            if (protocol == TvDevice.Protocol.LEGACY) {
                throw new UnsupportedOperationException("Legacy TVs have separate play and pause commands");
            }
            return "KEY_PLAY_BACK";
        }
        if (this == CHLIST) return "KEY_CH_LIST";
        if (this == POWEROFF && protocol == TvDevice.Protocol.TIZEN) return "KEY_POWER";
        return "KEY_" + name();
    }

    public static RemoteKey fromAction(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
