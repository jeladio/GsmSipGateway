package com.gsmsipgateway;
import android.content.*;
import android.net.sip.SipManager;
import com.facebook.react.bridge.*;

public class SipBridgeModule extends ReactContextBaseJavaModule {
    public SipBridgeModule(ReactApplicationContext ctx) { super(ctx); }
    @Override public String getName() { return "SipBridge"; }

    @ReactMethod
    public void saveConfig(ReadableMap cfg, Promise promise) {
        try {
            String host = cfg.getString("host");
            String username = cfg.getString("username");
            if (host == null || host.trim().isEmpty()) { promise.reject("INVALID", "SIP server is required"); return; }
            if (username == null || username.trim().isEmpty()) { promise.reject("INVALID", "SIP username is required"); return; }
            SharedPreferences.Editor p = getReactApplicationContext()
                .getSharedPreferences("sip_config", Context.MODE_PRIVATE).edit();
            p.putString("host", host.trim());
            p.putInt("port", cfg.getInt("port"));
            p.putString("username", username.trim());
            p.putString("password", cfg.getString("password"));
            p.putString("bridge_ext", cfg.getString("bridgeExtension"));
            p.putInt("answer_rings", cfg.hasKey("answerRings") ? cfg.getInt("answerRings") : 1);
            p.commit();
            Intent i = new Intent(getReactApplicationContext(), GsmSipBridgeService.class);
            i.setAction("ACTION_RELOAD");
            getReactApplicationContext().startForegroundService(i);
            promise.resolve("Config saved - registering");
        } catch (Exception e) { promise.reject("ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void getConfig(Promise promise) {
        try {
            SharedPreferences p = getReactApplicationContext()
                .getSharedPreferences("sip_config", Context.MODE_PRIVATE);
            WritableMap m = Arguments.createMap();
            m.putBoolean("configured", p.contains("host"));
            m.putString("host", p.getString("host", ""));
            m.putInt("port", p.getInt("port", 5060));
            m.putString("username", p.getString("username", ""));
            m.putString("password", p.getString("password", ""));
            m.putString("bridgeExtension", p.getString("bridge_ext", ""));
            m.putInt("answerRings", p.getInt("answer_rings", 1));
            promise.resolve(m);
        } catch (Exception e) { promise.reject("ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void getStatus(Promise promise) {
        try {
            Context ctx = getReactApplicationContext();
            SharedPreferences s = ctx.getSharedPreferences(GsmSipBridgeService.STATUS_PREFS, Context.MODE_PRIVATE);
            WritableMap m = Arguments.createMap();
            m.putString("state", s.getString("state", GsmSipBridgeService.STATE_NOT_CONFIGURED));
            m.putString("detail", s.getString("detail", ""));
            m.putDouble("updatedAt", s.getLong("updated_at", 0));
            m.putBoolean("sipApiSupported", SipManager.isApiSupported(ctx));
            m.putBoolean("sipVoipSupported", SipManager.isVoipSupported(ctx));
            m.putBoolean("sipWifiOnly", SipManager.isSipWifiOnly(ctx));
            m.putBoolean("useSipGranted", LinphoneEngine.hasSipPermission(ctx));
            promise.resolve(m);
        } catch (Exception e) { promise.reject("ERROR", e.getMessage()); }
    }
}
