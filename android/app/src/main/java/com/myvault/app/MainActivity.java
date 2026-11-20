package com.myvault.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.view.autofill.AutofillManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * MyVault for Android: shows the same MyVault.html app inside a WebView and gives it
 * native helpers (file storage, clipboard, Wi-Fi sync with the PC, fingerprint unlock).
 * The vault is only ever stored encrypted; the WebView page does all the encryption.
 */
public class MainActivity extends AppCompatActivity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 1, REQ_SAVE = 2;
    private static final int UDP_PORT = 47850;
    private static final int[] HTTP_PORTS = {47800, 47801, 47802, 47803, 47804, 47805, 47806, 47807, 47808, 47809};
    private static final String KEY_ALIAS = "myvault_bio";
    private static final String INSTANCE = UUID.randomUUID().toString().replace("-", "");

    private WebView web;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCb;
    private String pendingBackup;
    private int pendingBackupId = -1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep passwords out of screenshots and the recent-apps preview.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        prefs = getSharedPreferences("myvault", MODE_PRIVATE);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0A0C11"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HOST.equals(request.getUrl().getHost()); // never navigate away from the app
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript("window.__back?window.__back():false", v -> {
                    if (!"true".equals(v)) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
            }
        });

        web.loadUrl("https://" + HOST + "/assets/MyVault.html");
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (web != null) web.evaluateJavascript("window.__bg&&window.__bg()", null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) web.evaluateJavascript("window.__fg&&window.__fg()", null);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCb != null) {
                Uri[] res = (resultCode == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
                fileCb.onReceiveValue(res);
                fileCb = null;
            }
        } else if (requestCode == REQ_SAVE && pendingBackupId >= 0) {
            final int id = pendingBackupId;
            final String text = pendingBackup;
            pendingBackupId = -1;
            pendingBackup = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                done(id, true, "null");
                return;
            }
            final Uri uri = data.getData();
            pool.submit(() -> {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    if (os == null) throw new Exception("Could not open file");
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    done(id, true, JSONObject.quote(uri.toString()));
                } catch (Exception e) {
                    done(id, false, JSONObject.quote(msg(e)));
                }
            });
        }
    }

    // ------------------------------------------------------------------ bridge

    /** JavaScript calls AndroidBridge.call(id, method, argsJson); the answer comes back via __abCb(id, ok, result). */
    private class Bridge {
        @JavascriptInterface
        public void call(final int id, final String method, final String argsJson) {
            pool.submit(() -> {
                try {
                    handle(id, method, new JSONArray(argsJson));
                } catch (Exception e) {
                    done(id, false, JSONObject.quote(msg(e)));
                }
            });
        }
    }

    private void done(int id, boolean ok, String json) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.__abCb(" + id + "," + ok + "," + json + ")", null);
        });
    }

    private void handle(int id, String m, JSONArray a) throws Exception {
        switch (m) {
            case "load": {
                File f = vaultFile();
                done(id, true, f.exists() ? JSONObject.quote(readFile(f)) : "null");
                return;
            }
            case "save":
                writeAtomic(vaultFile(), a.getString(0));
                done(id, true, "true");
                return;
            case "copy": {
                final String text = a.optString(0, "");
                final boolean secret = a.optBoolean(1, false);
                runOnUiThread(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null) return;
                    if (text.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                        cm.clearPrimaryClip();
                        return;
                    }
                    ClipData clip = ClipData.newPlainText("MyVault", text);
                    if (secret && Build.VERSION.SDK_INT >= 24) {
                        PersistableBundle extras = new PersistableBundle();
                        // Hides the password in Android 13+'s clipboard preview bubble.
                        extras.putBoolean(Build.VERSION.SDK_INT >= 33 ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
                        clip.getDescription().setExtras(extras);
                    }
                    cm.setPrimaryClip(clip);
                });
                done(id, true, "true");
                return;
            }
            case "save_backup": {
                pendingBackup = a.getString(0);
                pendingBackupId = id;
                final String name = a.optString(1, "myvault-backup.vault");
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/octet-stream");
                    i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        pendingBackupId = -1;
                        done(id, false, JSONObject.quote("No app to save files"));
                    }
                });
                return;
            }
            // ---- Wi-Fi sync: the phone connects to the PC; it does not run a server itself.
            case "sync_info": {
                JSONObject o = new JSONObject();
                o.put("name", deviceName());
                JSONArray ips = new JSONArray();
                for (String ip : localIps()) ips.put(ip);
                o.put("ips", ips);
                o.put("port", JSONObject.NULL);
                o.put("running", true);
                o.put("mobile", true);
                done(id, true, o.toString());
                return;
            }
            case "sync_config":
                prefs.edit().putString("sid", a.optString(0, "")).apply();
                done(id, true, "true");
                return;
            case "sync_set_out":
                done(id, true, "true");
                return;
            case "sync_inbox":
                done(id, true, "[]");
                return;
            // ---- autofill service status and settings shortcut
            case "autofill_status": {
                AutofillManager am = getSystemService(AutofillManager.class);
                JSONObject o = new JSONObject();
                o.put("supported", am != null && am.isAutofillSupported());
                o.put("enabled", am != null && am.hasEnabledAutofillServices());
                o.put("bio", prefs.contains("bio_ct"));
                done(id, true, o.toString());
                return;
            }
            case "autofill_settings":
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
                    }
                });
                done(id, true, "true");
                return;
            // ---- tap-to-pair: the phone only initiates (finds the PC and sends it a request)
            case "sync_set_pairing":
            case "sync_approve_pair":
                done(id, true, "true");
                return;
            case "sync_pair_requests":
                done(id, true, "[]");
                return;
            case "sync_find":
                done(id, true, findPairing().toString());
                return;
            case "sync_pair_send": {
                String r;
                try {
                    r = new String(http("POST", a.getString(0), a.getInt(1), "/myvault/pair", "",
                            a.getJSONObject(2).toString().getBytes(StandardCharsets.UTF_8), 5000), StandardCharsets.UTF_8);
                    new JSONObject(r);
                } catch (Exception e) {
                    r = null;
                }
                done(id, true, r == null ? "null" : r);
                return;
            }
            case "sync_discover":
                done(id, true, discover(a.getString(0), a.optJSONArray(1)).toString());
                return;
            case "sync_fetch": {
                String r;
                try {
                    r = new String(http("GET", a.getString(0), a.getInt(1), "/myvault/blob", a.getString(2), null, 6000), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    r = null;
                }
                done(id, true, r == null ? "null" : JSONObject.quote(r));
                return;
            }
            case "sync_push": {
                boolean ok;
                try {
                    http("POST", a.getString(0), a.getInt(1), "/myvault/push", a.getString(2), a.getString(3).getBytes(StandardCharsets.UTF_8), 8000);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                done(id, true, ok ? "true" : "false");
                return;
            }
            // ---- fingerprint
            case "bio_status": {
                int can = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                JSONObject o = new JSONObject();
                o.put("available", can == BiometricManager.BIOMETRIC_SUCCESS);
                o.put("enabled", prefs.contains("bio_ct") && bioKey(false) != null);
                done(id, true, o.toString());
                return;
            }
            case "bio_enroll":
                bioEnroll(id, a.getString(0));
                return;
            case "bio_unlock":
                bioUnlock(id);
                return;
            case "bio_disable":
                bioClear();
                done(id, true, "true");
                return;
            default:
                done(id, false, JSONObject.quote("Not available on Android: " + m));
        }
    }

    // ------------------------------------------------------------------ fingerprint

    private SecretKey bioKey(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (!create) return null;
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private void bioClear() {
        prefs.edit().remove("bio_ct").remove("bio_iv").apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    /** Stores the vault's data key, encrypted by a fingerprint-protected key in the phone's secure hardware. */
    private void bioEnroll(final int id, final String dekB64) {
        runOnUiThread(() -> {
            try {
                bioClear();
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, bioKey(true));
                prompt("Turn on fingerprint unlock", "Confirm with your fingerprint", c, cipher -> {
                    try {
                        byte[] ct = cipher.doFinal(Base64.decode(dekB64, Base64.NO_WRAP));
                        prefs.edit()
                                .putString("bio_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                                .putString("bio_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                                .apply();
                        done(id, true, "true");
                    } catch (Exception e) {
                        done(id, false, JSONObject.quote(msg(e)));
                    }
                }, err -> done(id, false, JSONObject.quote(err)));
            } catch (Exception e) {
                done(id, false, JSONObject.quote(msg(e)));
            }
        });
    }

    private void bioUnlock(final int id) {
        runOnUiThread(() -> {
            try {
                String ct = prefs.getString("bio_ct", null), iv = prefs.getString("bio_iv", null);
                SecretKey k = bioKey(false);
                if (ct == null || iv == null || k == null) {
                    done(id, false, JSONObject.quote("Fingerprint unlock is not set up"));
                    return;
                }
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                try {
                    c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
                } catch (KeyPermanentlyInvalidatedException e) {
                    bioClear();
                    done(id, false, JSONObject.quote("Your fingerprints changed. Unlock with your password and turn fingerprint unlock on again."));
                    return;
                }
                prompt("Unlock MyVault", "Use your fingerprint", c, cipher -> {
                    try {
                        byte[] raw = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP));
                        done(id, true, JSONObject.quote(Base64.encodeToString(raw, Base64.NO_WRAP)));
                    } catch (Exception e) {
                        done(id, false, JSONObject.quote(msg(e)));
                    }
                }, err -> done(id, false, JSONObject.quote(err)));
            } catch (Exception e) {
                done(id, false, JSONObject.quote(msg(e)));
            }
        });
    }

    private void prompt(String title, String sub, Cipher c, Consumer<Cipher> ok, Consumer<String> fail) {
        BiometricPrompt bp = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                BiometricPrompt.CryptoObject co = result.getCryptoObject();
                if (co == null || co.getCipher() == null) fail.accept("Fingerprint failed");
                else ok.accept(co.getCipher());
            }

            @Override
            public void onAuthenticationError(int code, @NonNull CharSequence err) {
                fail.accept(code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_USER_CANCELED
                        || code == BiometricPrompt.ERROR_CANCELED ? "cancel" : err.toString());
            }
        });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(sub)
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        bp.authenticate(info, new BiometricPrompt.CryptoObject(c));
    }

    // ------------------------------------------------------------------ sync (client)

    /** Lists MyVault devices that currently have "Find nearby devices" open. */
    private JSONArray findPairing() {
        Map<String, JSONObject> found = new LinkedHashMap<>();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("myvault-find") : null;
        if (lock != null) lock.acquire();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(250);
            byte[] msg = new JSONObject().put("q", "myvault-find").put("from", INSTANCE).toString().getBytes(StandardCharsets.UTF_8);
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
            for (int round = 0; round < 2; round++) {
                for (InetAddress t : targets) {
                    try { s.send(new DatagramPacket(msg, msg.length, t, UDP_PORT)); } catch (Exception ignored) { }
                }
                long end = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
                        s.receive(p);
                        JSONObject r = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if ("myvault-find".equals(r.optString("a")) && !INSTANCE.equals(r.optString("from"))) {
                            found.put(r.optString("from"), new JSONObject().put("ip", p.getAddress().getHostAddress())
                                    .put("port", r.getInt("port")).put("name", r.optString("name", "Device"))
                                    .put("id", r.optString("from")).put("pub", r.optString("pub", "")));
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (lock != null) lock.release();
        }
        return new JSONArray(found.values());
    }

    private JSONArray discover(String sid, JSONArray known) {
        Map<String, JSONObject> found = new LinkedHashMap<>();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm != null ? wm.createMulticastLock("myvault") : null;
        if (lock != null) lock.acquire();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(250);
            byte[] msg = new JSONObject().put("q", "myvault").put("sid", sid).put("from", INSTANCE).toString().getBytes(StandardCharsets.UTF_8);
            List<InetAddress> targets = new ArrayList<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
            for (int round = 0; round < 2; round++) {
                for (InetAddress t : targets) {
                    try {
                        s.send(new DatagramPacket(msg, msg.length, t, UDP_PORT));
                    } catch (Exception ignored) {
                    }
                }
                long end = System.currentTimeMillis() + 800;
                while (System.currentTimeMillis() < end) {
                    try {
                        DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
                        s.receive(p);
                        JSONObject r = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if ("myvault".equals(r.optString("a")) && !INSTANCE.equals(r.optString("from"))) {
                            String ip = p.getAddress().getHostAddress();
                            found.put(r.optString("from", ip), new JSONObject().put("ip", ip).put("port", r.getInt("port"))
                                    .put("name", r.optString("name", ip)).put("id", r.optString("from")));
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (lock != null) lock.release();
        }
        // Devices we synced with before, tried directly (in case broadcast is blocked by the router).
        if (known != null) {
            for (int i = 0; i < known.length(); i++) {
                JSONObject k = known.optJSONObject(i);
                if (k == null) continue;
                String ip = k.optString("ip", "");
                if (ip.isEmpty() || alreadyHave(found, ip)) continue;
                List<Integer> ports = new ArrayList<>();
                if (k.optInt("port", 0) > 0) ports.add(k.optInt("port"));
                for (int p : HTTP_PORTS) ports.add(p);
                for (int port : ports) {
                    try {
                        JSONObject r = new JSONObject(new String(http("GET", ip, port, "/myvault/hello", sid, null, 600), StandardCharsets.UTF_8));
                        found.put(r.optString("from", ip), new JSONObject().put("ip", ip).put("port", port)
                                .put("name", r.optString("name", ip)).put("id", r.optString("from")));
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return new JSONArray(found.values());
    }

    private static boolean alreadyHave(Map<String, JSONObject> found, String ip) {
        for (JSONObject o : found.values()) if (ip.equals(o.optString("ip"))) return true;
        return false;
    }

    private static byte[] http(String method, String ip, int port, String path, String sid, byte[] body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + port + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("X-Sync-Id", sid);
            c.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress ad = ia.getAddress();
                    if (ad != null && ad.getAddress().length == 4) ips.add(ad.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }

    private static String deviceName() {
        String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Phone" : Build.MODEL;
        if (!man.isEmpty() && !model.toLowerCase().startsWith(man.toLowerCase()))
            model = Character.toUpperCase(man.charAt(0)) + man.substring(1) + " " + model;
        return model;
    }

    // ------------------------------------------------------------------ files

    private File vaultFile() {
        return new File(getFilesDir(), "myvault.vault");
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void writeAtomic(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f)) throw new Exception("Could not save vault");
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
