package mkg20001.net.samremotecommon;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Tools {
    public static ColorFilter filter() {
        int iColor = Color.parseColor("#FFFFFF");

        int red   = (iColor & 0xFF0000) / 0xFFFF;
        int green = (iColor & 0xFF00) / 0xFF;
        int blue  = iColor & 0xFF;

        float[] matrix = { 0, 0, 0, 0, red,
                0, 0, 0, 0, green,
                0, 0, 0, 0, blue,
                0, 0, 0, 1, 0 };

        return new ColorMatrixColorFilter(matrix);
    }
    static String base64(String s) {
        if (s==null) s=""; //eq to null
        return Base64.encodeToString(s.getBytes(), Base64.DEFAULT);
    }
    static int base64len(String s) {
        return base64(s).length();
    }
    static String chr(int c) {
        return Character.toString((char)c);
    }
    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    public static void log(String s) {
        // do something for a debug build
        //if (BuildConfig.DEBUG)
        Log.d("SamRemote",s);
    }

    public static void log2(String s) {
        // always print - like error or key send
        System.out.println(s);
    }


    private static String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

}
