# Keystead browser extension

Fills your logins from the Keystead desktop app in Chrome, Edge, Opera GX, Brave and Firefox.

## Install (Chrome, Edge, Opera GX, Brave)
Use the `keystead-chrome` folder.

1. Unzip this folder somewhere it can stay (for example `Documents\Keystead\extension`). Don't delete it afterwards: the browser loads it from there.
2. Open your browser's extensions page:
   - Chrome: `chrome://extensions`
   - Edge: `edge://extensions`
   - Opera GX: `opera://extensions`
   - Brave: `brave://extensions`
3. Turn on **Developer mode** (a switch on that page).
4. Click **Load unpacked** and choose this folder.
5. Pin the Keystead button to the toolbar (the puzzle-piece icon → pin).

## Install (Firefox)
Firefox only installs add-ons permanently once Mozilla has signed them. Signing is free and automatic:

1. Make a free account at https://addons.mozilla.org and sign in.
2. Go to https://addons.mozilla.org/developers/addon/submit/distribution and choose **On your own** (unlisted: it won't appear in Mozilla's store).
3. Upload `keystead-firefox-upload.zip` and continue through the steps. For "Do you need to submit source code?" choose **No** (nothing in it is minified or generated).
4. When it's approved (usually a few minutes), download the signed `.xpi` from the add-on's page.
5. Open the `.xpi` in Firefox (drag it onto a Firefox window) and click **Add**.

To just try it first without signing: open `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → pick `manifest.json` in the `keystead-firefox` folder. It stays until you close Firefox.

If Firefox asks for permission to "access your data for 127.0.0.1", allow it: that's how the extension talks to the Keystead app on your computer.

## Connect it to Keystead
1. Open the Keystead app and unlock it.
2. Click the Keystead button in the browser toolbar → **Connect**.
3. Keystead shows a 6-digit code. Type it into the extension.

## Use it
- Click into a login box. A small Keystead list appears under it with the logins saved for that website. Pick one (or press Enter).
- Or click the Keystead button in the toolbar to fill, or search all your accounts.

## Safety
- The extension only talks to the Keystead app on this computer (127.0.0.1). Nothing goes over the internet.
- Logins are offered only on the exact website they're saved for, so look-alike scam sites get nothing.
- Web pages can't talk to Keystead or ask for logins; only the extension can, after you connect it with the code.
- If Keystead is locked or closed, nothing is filled.
