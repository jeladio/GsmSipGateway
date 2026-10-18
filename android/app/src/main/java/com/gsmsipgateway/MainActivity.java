package com.gsmsipgateway;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import com.facebook.react.ReactActivity;
import com.facebook.react.ReactActivityDelegate;
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint;
import com.facebook.react.defaults.DefaultReactActivityDelegate;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends ReactActivity {
    private static final int PERM_REQUEST = 100;
    private static final String[] PERMS = {
        Manifest.permission.USE_SIP,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG
    };
    @Override protected String getMainComponentName() { return "GsmSipGateway"; }
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<String> missing = new ArrayList<>();
        for (String perm : PERMS) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) missing.add(perm);
        }
        if (missing.isEmpty()) {
            startForegroundService(new Intent(this, GsmSipBridgeService.class));
        } else {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERM_REQUEST);
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERM_REQUEST) return;
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                Log.w("MainActivity", "Permission denied: " + permissions[i]);
            }
        }
        // Reload so the service re-checks USE_SIP and registers with the saved config.
        Intent i = new Intent(this, GsmSipBridgeService.class);
        i.setAction("ACTION_RELOAD");
        startForegroundService(i);
    }
    @Override protected ReactActivityDelegate createReactActivityDelegate() {
        return new DefaultReactActivityDelegate(this, getMainComponentName(),
            DefaultNewArchitectureEntryPoint.getFabricEnabled());
    }
}
