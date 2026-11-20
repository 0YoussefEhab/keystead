package com.myvault.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.service.autofill.Dataset;
import android.util.Base64;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Opened when you tap "Fill with Keystead". Unlocks with your fingerprint (the same key the app's
 * fingerprint unlock uses), decrypts the vault in memory, lets you pick an account and hands the
 * username and password back to Android to fill. Nothing is written to disk.
 */
public class AutofillPickActivity extends AppCompatActivity {
    static final String EXTRA_USER_ID = "keystead.user_id", EXTRA_PASS_ID = "keystead.pass_id",
            EXTRA_DOMAIN = "keystead.domain", EXTRA_PACKAGE = "keystead.package";
    private static final String KEY_ALIAS = "myvault_bio";
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.microsoft.emmx", "com.opera.browser", "com.opera.gx",
            "com.brave.browser", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.opera.mini.native"));
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "com", "org", "net", "android", "app", "apps", "mobile", "client", "the", "game", "games", "inc", "ltd", "official", "beta"));

    private AutofillId userId, passId;
    private String domain, pkg;

    static final class Entry {
        String name, user, pass, url, via, group;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        Intent in = getIntent();
        userId = in.getParcelableExtra(EXTRA_USER_ID);
        passId = in.getParcelableExtra(EXTRA_PASS_ID);
        domain = in.getStringExtra(EXTRA_DOMAIN);
        pkg = in.getStringExtra(EXTRA_PACKAGE);
        setResult(RESULT_CANCELED);

        SharedPreferences prefs = getSharedPreferences("myvault", MODE_PRIVATE);
        String ct = prefs.getString("bio_ct", null), iv = prefs.getString("bio_iv", null);
        if (ct == null || iv == null || BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            message("Turn on fingerprint unlock first", "Open Keystead → Settings → Fingerprint unlock. Autofill uses it to open your vault safely.");
            return;
        }
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (key == null) throw new KeyPermanentlyInvalidatedException();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            BiometricPrompt bp = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    byte[] dek = null;
                    try {
                        dek = result.getCryptoObject().getCipher().doFinal(Base64.decode(ct, Base64.NO_WRAP));
                        showPicker(loadEntries(dek));
                    } catch (Exception e) {
                        message("Couldn't open your vault", "Open Keystead once with your password, then try again.");
                    } finally {
                        if (dek != null) Arrays.fill(dek, (byte) 0);
                    }
                }

                @Override
                public void onAuthenticationError(int code, @NonNull CharSequence err) {
                    finish();
                }
            });
            bp.authenticate(new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Fill with Keystead")
                    .setSubtitle(domain != null ? domain : appLabel())
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(), new BiometricPrompt.CryptoObject(c));
        } catch (KeyPermanentlyInvalidatedException e) {
            message("Fingerprints changed", "Open Keystead with your password and turn fingerprint unlock on again.");
        } catch (Exception e) {
            message("Couldn't start autofill", String.valueOf(e.getMessage()));
        }
    }

    private String appLabel() {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private List<Entry> loadEntries(byte[] dek) throws Exception {
        File f = new File(getFilesDir(), "myvault.vault");
        JSONObject o = new JSONObject(read(f));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.DEFAULT)));
        byte[] plain = c.doFinal(Base64.decode(o.getString("ct"), Base64.DEFAULT));
        JSONObject v = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        Arrays.fill(plain, (byte) 0);

        JSONObject groupNames = new JSONObject();
        JSONArray groups = v.optJSONArray("groups");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.getJSONObject(i);
            groupNames.put(g.optString("id"), g.optString("name"));
        }
        JSONArray arr = v.optJSONArray("entries");
        JSONObject byId = new JSONObject();
        List<Entry> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) byId.put(arr.getJSONObject(i).optString("id"), arr.getJSONObject(i).optString("name"));
        boolean multi = groups != null && groups.length() > 1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            Entry x = new Entry();
            x.name = e.optString("name");
            x.user = e.optString("user");
            x.pass = e.optString("pass");
            x.url = e.optString("url");
            String via = e.optString("via");
            x.via = via.isEmpty() || !x.pass.isEmpty() ? "" : byId.optString(via, "another account");
            x.group = multi ? groupNames.optString(e.optString("group"), "") : "";
            out.add(x);
        }
        return out;
    }

    static String regDomain(String h) {
        if (h == null) return "";
        h = h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        String[] p = h.split("\\.");
        if (p.length > 2) {
            String last2 = p[p.length - 2] + "." + p[p.length - 1];
            if (Arrays.asList("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "co.jp", "com.br", "co.in", "com.eg",
                    "com.sa", "com.tr", "co.za", "com.mx", "co.nz", "com.ar").contains(last2))
                return p[p.length - 3] + "." + last2;
        }
        return p.length >= 2 ? p[p.length - 2] + "." + p[p.length - 1] : h;
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = new URI(url.contains("://") ? url : "https://" + url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** Websites match by exact site; apps match by words in their package name, e.g. com.riotgames.league → "riot". */
    private boolean matches(Entry e) {
        if (domain != null && !domain.isEmpty()) return regDomain(hostOf(e.url)).equals(regDomain(domain));
        if (pkg == null || BROWSERS.contains(pkg)) return false;
        String hay = (e.name + " " + hostOf(e.url)).toLowerCase(Locale.ROOT);
        for (String w : pkg.toLowerCase(Locale.ROOT).split("[._]")) {
            if (w.length() >= 3 && !SKIP.contains(w) && (hay.contains(w) || (w.length() > 5 && hay.contains(w.substring(0, 4))))) return true;
        }
        return false;
    }

    private void showPicker(List<Entry> all) {
        List<Entry> hits = new ArrayList<>();
        for (Entry e : all) if (matches(e)) hits.add(e);
        boolean showingAll = hits.isEmpty();
        List<Entry> list = showingAll ? all : hits;
        if (list.isEmpty()) {
            message("No accounts yet", "Add accounts in Keystead first.");
            return;
        }
        list.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            labels[i] = e.name + "\n" + (e.user.isEmpty() ? "no username" : e.user) + (e.group.isEmpty() ? "" : " · " + e.group);
        }
        String where = domain != null ? domain : appLabel();
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(showingAll ? "No saved login for " + where + ". Pick one:" : "Fill " + where)
                .setItems(labels, (dlg, which) -> fill(list.get(which)))
                .setNegativeButton("Cancel", (dlg, w) -> finish())
                .setOnCancelListener(dlg -> finish());
        if (!showingAll) {
            d.setNeutralButton("All accounts", (dlg, w) -> {
                List<Entry> rest = new ArrayList<>(all);
                hits.clear();
                showAll(rest, where);
            });
        }
        d.show();
    }

    private void showAll(List<Entry> all, String where) {
        all.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        String[] labels = new String[all.size()];
        for (int i = 0; i < all.size(); i++) labels[i] = all.get(i).name + "\n" + all.get(i).user;
        new AlertDialog.Builder(this)
                .setTitle("Not saved for " + where + ". Only pick one you're sure belongs here.")
                .setItems(labels, (dlg, which) -> fill(all.get(which)))
                .setNegativeButton("Cancel", (dlg, w) -> finish())
                .setOnCancelListener(dlg -> finish())
                .show();
    }

    private void fill(Entry e) {
        RemoteViews row = new RemoteViews(getPackageName(), R.layout.autofill_item);
        row.setTextViewText(R.id.af_title, e.name);
        row.setTextViewText(R.id.af_sub, e.user);
        Dataset.Builder ds = new Dataset.Builder(row);
        boolean any = false;
        if (userId != null && !e.user.isEmpty()) { ds.setValue(userId, AutofillValue.forText(e.user)); any = true; }
        if (passId != null && !e.pass.isEmpty()) { ds.setValue(passId, AutofillValue.forText(e.pass)); any = true; }
        if (!any) {
            message(e.via.isEmpty() ? "Nothing to fill" : "Signs in with " + e.via,
                    e.via.isEmpty() ? "This account has no username or password saved." : "Use the \"Continue with Google/Microsoft\" button on the login screen.");
            return;
        }
        Intent reply = new Intent();
        reply.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds.build());
        setResult(RESULT_OK, reply);
        finish();
    }

    private void message(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body)
                .setPositiveButton("OK", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
