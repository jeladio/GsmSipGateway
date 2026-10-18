package com.gsmsipgateway;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.sip.*;
import android.util.Log;

// NOTE: despite the name, this engine uses the platform android.net.sip stack, not Linphone.
public class LinphoneEngine {
    private static final String TAG = "SipEngine";
    private final Context appContext;
    private SipManager sipManager;
    private SipProfile localProfile;
    private SipAudioCall currentCall;
    private BridgeCallback callback;
    private String host, user;

    public interface BridgeCallback {
        void onSipRegistering();
        void onSipRegistered(long expiryTime);
        void onSipRegistrationFailed(int errorCode, String reason);
        void onSipCallConnected();
        void onSipCallEnded();
    }

    public LinphoneEngine(Context ctx, BridgeCallback cb) {
        this.appContext = ctx.getApplicationContext();
        this.callback = cb;
        if (SipManager.isVoipSupported(appContext) && SipManager.isApiSupported(appContext)) {
            sipManager = SipManager.newInstance(appContext);
        }
        Log.i(TAG, "SIP support: " + describeSupport(appContext));
    }

    public static boolean isSipAvailable(Context ctx) {
        return SipManager.isApiSupported(ctx) && SipManager.isVoipSupported(ctx);
    }

    public static boolean hasSipPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.USE_SIP) == PackageManager.PERMISSION_GRANTED;
    }

    public static String describeSupport(Context ctx) {
        return "apiSupported=" + SipManager.isApiSupported(ctx)
            + " voipSupported=" + SipManager.isVoipSupported(ctx)
            + " wifiOnly=" + SipManager.isSipWifiOnly(ctx)
            + " useSipGranted=" + hasSipPermission(ctx);
    }

    public void register(String host, int port, String user, String pass) {
        this.host = host;
        this.user = user;
        if (sipManager == null) {
            Log.e(TAG, "register skipped: native SIP API unavailable (" + describeSupport(appContext) + ")");
            if (callback != null) callback.onSipRegistrationFailed(SipErrorCode.CLIENT_ERROR,
                "Native SIP API not available on this device");
            return;
        }
        if (!hasSipPermission(appContext)) {
            Log.e(TAG, "register skipped: USE_SIP permission not granted");
            if (callback != null) callback.onSipRegistrationFailed(SipErrorCode.CLIENT_ERROR,
                "USE_SIP permission not granted");
            return;
        }
        try {
            if (localProfile != null) {
                try { sipManager.close(localProfile.getUriString()); } catch (Exception ignored) {}
                localProfile = null;
            }
            SipProfile.Builder builder = new SipProfile.Builder(user, host);
            builder.setAuthUserName(user);
            builder.setPassword(pass);
            builder.setPort(port);
            builder.setProtocol("UDP");
            builder.setSendKeepAlive(true);
            builder.setAutoRegistration(true);
            localProfile = builder.build();

            Intent intent = new Intent("com.gsmsipgateway.INCOMING_SIP_CALL");
            intent.setPackage(appContext.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(
                appContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            Log.d(TAG, "Opening profile " + localProfile.getUriString() + " via " + host + ":" + port + "/UDP");
            // Pass the listener to open() so the first auto-registration attempt is not missed.
            sipManager.open(localProfile, pi, new SipRegistrationListener() {
                @Override public void onRegistering(String localProfileUri) {
                    Log.d(TAG, "Registering " + localProfileUri);
                    if (callback != null) callback.onSipRegistering();
                }
                @Override public void onRegistrationDone(String localProfileUri, long expiryTime) {
                    Log.d(TAG, "Registered OK | uri=" + localProfileUri + " | expires=" + expiryTime);
                    if (callback != null) callback.onSipRegistered(expiryTime);
                }
                @Override public void onRegistrationFailed(String localProfileUri, int errorCode, String errorMessage) {
                    Log.e(TAG, "Registration failed: code=" + SipErrorCode.toString(errorCode) + " msg=" + errorMessage);
                    if (callback != null) callback.onSipRegistrationFailed(errorCode, errorMessage);
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "register security error: " + e.getMessage());
            if (callback != null) callback.onSipRegistrationFailed(SipErrorCode.CLIENT_ERROR,
                "Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "register error: " + e, e);
            if (callback != null) callback.onSipRegistrationFailed(SipErrorCode.CLIENT_ERROR, String.valueOf(e.getMessage()));
        }
    }

    public boolean callSip(String ext, String remoteHost, int remotePort) {
        if (sipManager == null || localProfile == null) {
            Log.e(TAG, "callSip skipped: SIP stack not ready");
            return false;
        }
        if (ext == null || ext.trim().isEmpty()) {
            Log.e(TAG, "callSip skipped: empty bridge extension");
            return false;
        }
        try {
            String cleanExt = ext.trim();
            String peerUri;
            if (cleanExt.startsWith("sip:")) {
                peerUri = cleanExt;
            } else if (cleanExt.contains("@")) {
                peerUri = "sip:" + cleanExt;
            } else {
                peerUri = "sip:" + cleanExt + "@" + remoteHost + ":" + remotePort;
            }
            Log.d(TAG, "Dialing peerUri=" + peerUri + " from " + localProfile.getUriString());
            currentCall = sipManager.makeAudioCall(
                localProfile.getUriString(),
                peerUri,
                new SipAudioCall.Listener() {
                    @Override public void onCallEstablished(SipAudioCall call) {
                        call.startAudio();
                        if (callback != null) callback.onSipCallConnected();
                    }
                    @Override public void onCallEnded(SipAudioCall call) {
                        if (callback != null) callback.onSipCallEnded();
                    }
                    @Override public void onError(SipAudioCall call, int errorCode, String errorMessage) {
                        Log.e(TAG, "Call error: code=" + SipErrorCode.toString(errorCode) + " msg=" + errorMessage);
                        if (callback != null) callback.onSipCallEnded();
                    }
                }, 30
            );
            return currentCall != null;
        } catch (Exception e) {
            Log.e(TAG, "callSip error: " + e.getMessage());
            return false;
        }
    }

    public void hangup() {
        try {
            if (currentCall != null) {
                currentCall.endCall();
                currentCall.close();
                currentCall = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "hangup error: " + e.getMessage());
        }
    }

    public void destroy() {
        callback = null;
        hangup();
        try {
            if (localProfile != null && sipManager != null) {
                sipManager.close(localProfile.getUriString());
            }
        } catch (Exception e) {
            Log.e(TAG, "destroy error: " + e.getMessage());
        }
        localProfile = null;
    }
}
