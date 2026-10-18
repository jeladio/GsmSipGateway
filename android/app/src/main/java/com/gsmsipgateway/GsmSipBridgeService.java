package com.gsmsipgateway;
import android.app.*;
import android.media.AudioManager;
import android.content.*;
import android.net.sip.SipErrorCode;
import android.os.*;
import android.telecom.TelecomManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class GsmSipBridgeService extends Service implements LinphoneEngine.BridgeCallback {
    private static final String TAG = "GsmSipBridgeService";
    private static final String CH = "gsm_sip_bridge";
    private static final int RING_INTERVAL_MS = 5000;
    private static final long RETRY_BASE_MS = 5000;
    private static final long RETRY_MAX_MS = 60000;

    public static final String STATUS_PREFS = "sip_status";
    public static final String STATE_NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String STATE_NO_PERMISSION = "NO_PERMISSION";
    public static final String STATE_UNSUPPORTED = "SIP_UNSUPPORTED";
    public static final String STATE_REGISTERING = "REGISTERING";
    public static final String STATE_REGISTERED = "REGISTERED";
    public static final String STATE_FAILED = "FAILED";

    private LinphoneEngine sip;
    private String host, user, pass, ext;
    private int port, answerRings;
    private volatile boolean isSipRegistered = false;
    private boolean bridgeInProgress = false;
    private int bridgeAttempts = 0;
    private int registrationFailures = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable bridgeRunnable = this::bridgeToExtension;
    private final Runnable answerRunnable = this::answerAndBridge;
    private final Runnable registerRetryRunnable = this::retryRegistration;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        audioManager = getSystemService(AudioManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":bridge");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60 * 1000L);
        }
        startForeground(1, note("Initializing..."));
        reload();
    }

    private void reload() {
        // Also drops callbacks already posted by the previous engine, so no stale retry survives.
        handler.removeCallbacksAndMessages(null);
        isSipRegistered = false;
        bridgeInProgress = false;
        bridgeAttempts = 0;
        registrationFailures = 0;
        if (sip != null) {
            sip.destroy();
            sip = null;
        }

        SharedPreferences p = getSharedPreferences("sip_config", MODE_PRIVATE);
        if (!p.contains("host")) {
            setStatus(STATE_NOT_CONFIGURED, "Open the app and save the SIP settings");
            return;
        }
        host = p.getString("host", "");
        port = p.getInt("port", 5060);
        user = p.getString("username", "");
        pass = p.getString("password", "");
        ext  = p.getString("bridge_ext", "");
        answerRings = Math.max(1, p.getInt("answer_rings", 1));

        if (!LinphoneEngine.isSipAvailable(this)) {
            setStatus(STATE_UNSUPPORTED, "Native SIP API not available: " + LinphoneEngine.describeSupport(this));
            return;
        }
        if (!LinphoneEngine.hasSipPermission(this)) {
            setStatus(STATE_NO_PERMISSION, "USE_SIP permission not granted");
            return;
        }
        sip = new LinphoneEngine(this, this);
        setStatus(STATE_REGISTERING, "Registering " + user + "@" + host + ":" + port);
        sip.register(host, port, user, pass);
    }

    private void retryRegistration() {
        if (sip == null) return;
        Log.d(TAG, "Retrying SIP registration (failure #" + registrationFailures + ")");
        setStatus(STATE_REGISTERING, "Retrying registration " + user + "@" + host + ":" + port);
        sip.register(host, port, user, pass);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_INCOMING_CALL":
                    String caller = intent.getStringExtra("caller_number");
                    if (bridgeInProgress) {
                        // PHONE_STATE is delivered twice on Android 9+ (with and without number).
                        Log.d(TAG, "Ignoring duplicate RINGING event");
                        break;
                    }
                    if (!isSipRegistered) {
                        Log.w(TAG, "Incoming GSM call from " + caller + " not answered: SIP not registered");
                        updateNote("Incoming: " + caller + " | not answered (SIP not registered)");
                        break;
                    }
                    bridgeInProgress = true;
                    bridgeAttempts = 0;
                    handler.removeCallbacks(answerRunnable);
                    handler.removeCallbacks(bridgeRunnable);
                    int answerDelayMs = Math.max(0, answerRings - 1) * RING_INTERVAL_MS;
                    updateNote("Incoming: " + caller + " | answering after ring " + answerRings);
                    handler.postDelayed(answerRunnable, answerDelayMs);
                    break;
                case "ACTION_CALL_ENDED":
                    handler.removeCallbacks(answerRunnable);
                    handler.removeCallbacks(bridgeRunnable);
                    boolean wasBridging = bridgeInProgress;
                    bridgeInProgress = false;
                    bridgeAttempts = 0;
                    if (sip != null) sip.hangup();
                    if (wasBridging) resetAudio();
                    refreshNote();
                    break;
                case "ACTION_RELOAD":
                    reload();
                    break;
            }
        }
        return START_STICKY;
    }

    private void autoAnswer() {
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) tm.acceptRingingCall();
            Log.d(TAG, "GSM auto-answered");
        } catch (SecurityException e) { Log.e(TAG, e.getMessage()); }
    }

    private void answerAndBridge() {
        if (!bridgeInProgress) {
            return;
        }
        prepareAudio();
        autoAnswer();
        handler.removeCallbacks(bridgeRunnable);
        handler.postDelayed(bridgeRunnable, 1200);
    }

    private void bridgeToExtension() {
        if (!bridgeInProgress) {
            return;
        }
        if (!isSipRegistered || sip == null) {
            scheduleBridgeRetry("SIP not registered yet");
            return;
        }

        Log.d(TAG, "SIP registered, bridging to extension " + ext);
        updateNote("Dialing SIP extension...");
        boolean success = sip.callSip(ext, host, port);
        if (!success) {
            scheduleBridgeRetry("Failed to dial " + ext);
        }
    }

    // SipManager delivers these callbacks on a binder thread; hop to the main thread.
    @Override public void onSipRegistering() {
        handler.post(() -> setStatus(STATE_REGISTERING, "Registering " + user + "@" + host + ":" + port));
    }

    @Override public void onSipRegistered(long expiryTime) {
        handler.post(() -> {
            handler.removeCallbacks(registerRetryRunnable);
            isSipRegistered = true;
            registrationFailures = 0;
            Log.d(TAG, "SIP Registration successful");
            setStatus(STATE_REGISTERED, user + "@" + host + ":" + port + " (expires " + expiryTime + "s)");
        });
    }

    @Override public void onSipRegistrationFailed(int errorCode, String reason) {
        handler.post(() -> {
            isSipRegistered = false;
            registrationFailures++;
            long delay;
            if (errorCode == SipErrorCode.INVALID_CREDENTIALS) {
                // Avoid hammering Asterisk (and tripping fail2ban) with bad credentials.
                delay = RETRY_MAX_MS;
            } else {
                delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(registrationFailures - 1, 4));
            }
            String detail = SipErrorCode.toString(errorCode) + ": " + reason + " | retry in " + (delay / 1000) + "s";
            Log.e(TAG, "SIP Registration failed: " + detail);
            setStatus(STATE_FAILED, detail);
            // Single pending retry: replace any previously scheduled one.
            handler.removeCallbacks(registerRetryRunnable);
            handler.postDelayed(registerRetryRunnable, delay);
        });
    }

    @Override public void onSipCallConnected() { handler.post(() -> updateNote("Bridge Active")); }
    @Override public void onSipCallEnded()     {
        handler.post(() -> {
            bridgeInProgress = false;
            bridgeAttempts = 0;
            resetAudio();
            refreshNote();
        });
    }

    private void scheduleBridgeRetry(String reason) {
        bridgeAttempts++;
        if (bridgeAttempts > 10) {
            bridgeInProgress = false;
            updateNote("Bridge failed");
            Log.e(TAG, reason + ", giving up after " + bridgeAttempts + " attempts");
            return;
        }
        Log.w(TAG, reason + ", retrying in 500ms");
        handler.postDelayed(bridgeRunnable, 500);
    }

    private void setStatus(String state, String detail) {
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit()
            .putString("state", state)
            .putString("detail", detail)
            .putLong("updated_at", System.currentTimeMillis())
            .apply();
        updateNote(state + " - " + detail);
    }

    private void refreshNote() {
        SharedPreferences s = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE);
        updateNote(s.getString("state", "") + " - " + s.getString("detail", ""));
    }

    private void prepareAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    private void resetAudio() {
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.abandonAudioFocus(null);
        }
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CH, "GSM-SIP Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    private Notification note(String t) {
        return new NotificationCompat.Builder(this, CH)
            .setContentTitle("GSM-SIP Gateway").setContentText(t)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(t))
            .setSmallIcon(android.R.drawable.ic_menu_call).setOngoing(true).build();
    }
    private void updateNote(String t) { getSystemService(NotificationManager.class).notify(1, note(t)); }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        resetAudio();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (sip != null) sip.destroy();
        super.onDestroy();
    }
}
