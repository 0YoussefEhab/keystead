# Security

Keystead is a personal project. It uses standard, well-reviewed cryptography, but it has **not** had an independent security audit. Keep an encrypted backup of anything important.

## How your data is protected

| Layer | What it does |
|---|---|
| **AES-256-GCM** | Encrypts the whole vault and detects any tampering. |
| **Random data key** | The vault is encrypted with a random 256-bit key. Each unlock password (and the recovery key) separately wraps that key, so you can have several passwords without weakening any of them. |
| **Argon2id** (19 MiB, 2 passes) | Turns your password into a key. Memory-hard, so guessing on GPUs is slow and expensive. Older PBKDF2 vaults still open and upgrade when a password changes. |
| **Windows DPAPI** (desktop) | The vault file on disk is also locked to your Windows account, so a copied file won't open on another PC. Backups are left portable. |
| **Android Keystore** | Fingerprint unlock keeps its key in the phone's secure hardware. |
| **No cloud** | Nothing is uploaded. Sync goes directly between your devices on your Wi-Fi. |

## Sync

- Data sent between devices is encrypted with a key derived from the pairing code.
- **Tap-to-pair** uses an ECDH (P-256) key exchange; the key never crosses the network. Both screens show a 6-digit code that must match, which stops a device in the middle from pairing.
- Devices only answer discovery while the *Find nearby devices* screen is open.

## Other protections

- Auto-lock after inactivity; locks when the phone app goes to the background.
- Copied passwords are cleared from the clipboard after a set time.
- The desktop window is hidden from screen sharing and recording by default.
- Screenshots are blocked in the Android app.
- Browser import never reads the browser's password database directly; you export from the browser yourself, which requires your Windows login.

## Reporting a problem

If you find a security issue, please open a private security advisory on this repository (Security → Advisories → *Report a vulnerability*) instead of a public issue.
