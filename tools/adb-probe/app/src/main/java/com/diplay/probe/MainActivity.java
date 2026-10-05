package com.diplay.probe;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        String report;
        try {
            super.onCreate(savedInstanceState);
            report =
                "PROBE OK - 系统能启动新装应用\n\n" +
                "显示版本: " + Build.VERSION.RELEASE + "\n" +
                "真实 SDK: " + Build.VERSION.SDK_INT + "\n" +
                "ROM build: " + Build.DISPLAY + "\n" +
                "设备: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "硬件: " + Build.HARDWARE + "\n" +
                "内核: " + System.getProperty("os.version") + "\n" +
                "ABI: " + abi() + "\n" +
                "指纹: " + Build.FINGERPRINT + "\n";
        } catch (Throwable error) {
            report = "PROBE 自身崩溃 (SDK " + Build.VERSION.SDK_INT + ")\n\n" + trace(error);
            writeCrash(this, report);
        }
        TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setText(report);
        try {
            setContentView(tv);
        } catch (Throwable error) {
            writeCrash(this, trace(error));
            throw error;
        }
    }

    private static String abi() {
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null) {
            StringBuilder sb = new StringBuilder();
            for (String abi : Build.SUPPORTED_ABIS) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(abi);
            }
            return sb.toString();
        }
        return Build.CPU_ABI + " / " + Build.CPU_ABI2;
    }

    private static String trace(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static void writeCrash(Context context, String report) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                dir.mkdirs();
                FileOutputStream out = new FileOutputStream(new File(dir, "probe-crash.txt"), true);
                out.write(report.getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable ignored) { }
    }
}
