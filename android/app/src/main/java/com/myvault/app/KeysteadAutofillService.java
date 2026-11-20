package com.myvault.app;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.content.IntentSender;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.ViewStructure;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keystead autofill: when an app or browser shows a login form, Android asks this service what it
 * can fill. It answers with one "Fill with Keystead" suggestion. Tapping it opens
 * {@link AutofillPickActivity}, which unlocks with your fingerprint and fills the account you pick.
 * Nothing is decrypted here: this service only finds the username and password boxes.
 */
public class KeysteadAutofillService extends AutofillService {
    private static final Pattern USER_HINT = Pattern.compile("user|email|e-mail|login|account|identifier|phone", Pattern.CASE_INSENSITIVE);

    static final class Fields {
        AutofillId user, pass;
        String webDomain;
    }

    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancel, FillCallback callback) {
        List<FillContext> contexts = request.getFillContexts();
        AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
        String pkg = structure.getActivityComponent().getPackageName();
        if (getPackageName().equals(pkg)) { // never fill into Keystead itself
            callback.onSuccess(null);
            return;
        }
        Fields f = new Fields();
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            walk(structure.getWindowNodeAt(i).getRootViewNode(), f);
        }
        if (f.user == null && f.pass == null) {
            callback.onSuccess(null);
            return;
        }

        Intent intent = new Intent(this, AutofillPickActivity.class);
        intent.putExtra(AutofillPickActivity.EXTRA_USER_ID, f.user);
        intent.putExtra(AutofillPickActivity.EXTRA_PASS_ID, f.pass);
        intent.putExtra(AutofillPickActivity.EXTRA_DOMAIN, f.webDomain);
        intent.putExtra(AutofillPickActivity.EXTRA_PACKAGE, pkg);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT
                | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0); // Android adds the form to the intent
        IntentSender sender = PendingIntent.getActivity(this, (int) (System.nanoTime() & 0x7fffffff), intent, flags).getIntentSender();

        RemoteViews row = new RemoteViews(getPackageName(), R.layout.autofill_item);
        row.setTextViewText(R.id.af_title, "Fill with Keystead");
        row.setTextViewText(R.id.af_sub, f.webDomain != null ? f.webDomain : "Unlock with your fingerprint");

        Dataset.Builder ds = new Dataset.Builder(row);
        if (f.user != null) ds.setValue(f.user, null);
        if (f.pass != null) ds.setValue(f.pass, null);
        ds.setAuthentication(sender);
        callback.onSuccess(new FillResponse.Builder().addDataset(ds.build()).build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess(); // Keystead doesn't save from other apps; add accounts in Keystead itself
    }

    private static void walk(AssistStructure.ViewNode node, Fields f) {
        if (node == null) return;
        if (f.webDomain == null && node.getWebDomain() != null && !node.getWebDomain().isEmpty()) {
            f.webDomain = node.getWebDomain().toLowerCase(Locale.ROOT);
        }
        AutofillId id = node.getAutofillId();
        if (id != null && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT && node.getVisibility() == View.VISIBLE) {
            if (isPassword(node)) {
                if (f.pass == null) f.pass = id;
            } else if (f.pass == null && isUser(node)) {
                f.user = id; // keep the last username-like box before the first password box
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChildAt(i), f);
    }

    private static boolean hasHint(AssistStructure.ViewNode n, String... want) {
        String[] hints = n.getAutofillHints();
        if (hints == null) return false;
        for (String h : hints) for (String w : want) if (h != null && h.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String htmlType(AssistStructure.ViewNode n) {
        ViewStructure.HtmlInfo html = n.getHtmlInfo();
        if (html == null || html.getAttributes() == null) return null;
        for (Pair<String, String> a : html.getAttributes()) if ("type".equalsIgnoreCase(a.first)) return a.second;
        return null;
    }

    private static boolean isPassword(AssistStructure.ViewNode n) {
        if (hasHint(n, View.AUTOFILL_HINT_PASSWORD, "password")) return true;
        if ("password".equalsIgnoreCase(htmlType(n))) return true;
        int t = n.getInputType();
        int cls = t & InputType.TYPE_MASK_CLASS, var = t & InputType.TYPE_MASK_VARIATION;
        return (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static boolean isUser(AssistStructure.ViewNode n) {
        if (hasHint(n, View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS, View.AUTOFILL_HINT_PHONE, "username", "email")) return true;
        String ht = htmlType(n);
        if ("email".equalsIgnoreCase(ht)) return true;
        int t = n.getInputType();
        int var = t & InputType.TYPE_MASK_VARIATION;
        if ((t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) return true;
        StringBuilder words = new StringBuilder();
        if (n.getIdEntry() != null) words.append(n.getIdEntry()).append(' ');
        if (n.getHint() != null) words.append(n.getHint()).append(' ');
        if (n.getText() != null && n.getText().length() < 40) words.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) words.append(n.getContentDescription());
        boolean textBox = ht == null ? (t & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT || t == 0 : "text".equalsIgnoreCase(ht) || "tel".equalsIgnoreCase(ht);
        return textBox && USER_HINT.matcher(words).find();
    }
}
