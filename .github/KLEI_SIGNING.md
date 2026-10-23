# Stable Android APK signing (Klei Gift Watcher)

The app uses one stable **PKCS#12** signing key, alias `kleigiftwatcher`.
**Never commit the `.p12`, its base64 representation, or passwords.**
The encrypted keystore backup is available to its owner outside this public GitHub repository.

## Configure GitHub Actions (one time)

Open this repository → **Settings → Secrets and variables → Actions → New repository secret**.
Create **four** repository secrets:

| Secret name | Value |
|---|---|
| `KLEI_KEYSTORE_B64` | Entire single-line content of `KLEI_KEYSTORE_B64_FOR_GITHUB_SECRET.txt` from the private signing backup |
| `KLEI_KEYSTORE_PASSWORD` | Keystore password from separate backup password file |
| `KLEI_KEY_ALIAS` | `kleigiftwatcher` |
| `KLEI_KEY_PASSWORD` | Key password from separate backup password file (same as keystore password in this key) |

Then choose **Actions → Build Klei Gift Watcher APK → Run workflow** on branch `klei-gift-watcher-android`.
The `KleiGiftWatcher-fixedsigned-apk` artifact is the installable APK signed with that stable key.
The `KleiGiftWatcher-debug-apk` artifact uses an ephemeral debug key and should **not** be installed for normal updates.

## Check the signature

The stable signer SHA-256 fingerprint must be:

```
72:0C:ED:D9:B5:97:87:3F:00:BE:62:82:69:A9:9A:98:F7:E0:BC:01:0F:46:E7:5B:18:3A:5A:99:F8:88:94:BB
```

Use `apksigner verify --verbose --print-certs app.apk` and compare `Signer #1 certificate SHA-256 digest`.

If a previous APK has a different signer, Android will require uninstalling that old APK once; future upgrades using the stable key retain app data as long as the app ID and versionCode are compatible.

**Backup guidance:** Keep the password in your password manager and the encrypted keystore in a different location. Losing either prevents signing future upgrades with this identity. GitHub Actions secrets cannot be read back after being set.
