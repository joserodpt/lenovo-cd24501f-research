# Turning a Lenovo Smart Clock (CD-24501F) into a Custom Android Display

## ⚠️ Read this first

This guide documents unlocking and modifying a **Lenovo Smart Clock gen 1 (CD-24501F, code name "cube", MediaTek MT8167, Android Things 8.1)**. It is destructive and irreversible in places. You can brick the device, and Lenovo no longer publishes factory firmware for it.

**Do this only if:**
- You have made a **full partition backup** (this is the only way back).
- You accept that stock Google/Nest setup may stop working.
- You understand you are working with an obsolete, unsupported device.

**Do not** use a 3.3 V serial adapter on the UART pins (1.8 V only — 3.3 V will kill the SoC).

---

## 1. What you can achieve

- Boot the clock into **your own Android app** as the home screen.
- Get **adb over Wi-Fi** (no USB adb possible — see §4).
- Build a landscape kiosk WebView showing a Home Assistant dashboard (or any web UI).
- Stop the device from auto-updating and from a `mediashell` crash loop.

There are two big routes; this guide uses the second, more reliable one:

| Route | Mechanism | Pros / cons |
| --- | --- | --- |
| **A) Patch boot + root** | Magisk-patched boot, adbd-on-TCP, root shell | Requires AVB bypass; fragile boot with Android Things |
| **B) Offline `userdata` injection** ← *used here* | Edit the ext4 `userdata` image with `debugfs`, flash it back | No root needed; stock boot; app becomes home; also enables adb-over-Wi-Fi |

> A complete, working first app is included: [`app/`](app/) — **Device Diag**, a read-only device-info dashboard (plus a multi-touch test and input/USB device inspector). Build it with [`app/build.sh`](app/build.sh), register it per §6, and flash. It's an ideal "does the whole pipeline work?" check before writing your real app.

---

## 2. Device facts you need

| Thing | Value |
| --- | --- |
| Model | Lenovo Smart Clock gen 1, **CD-24501F** ("cube") |
| SoC | MediaTek **MT8167** (`HW code 0x8167`) |
| OS | **Android Things 8.1.0**, userspace **armv7a (32-bit)** |
| eMMC | ~7.3 GiB user area, A/B slots |
| Stock home launcher | `com.google.assistant.launcher` (in `/oem/app`) + `com.android.iotlauncher` |
| Also registers HOME | `com.google.assistant.core` (remember this — see §10) |
| Stock WebView | Chromium **87.0.4280.101** (good enough for a modern web UI) |
| USB port | **Host mode only** under Android (OTG ID pin pulled low) — no USB adb |
| BootROM access | Works (Kamakiri exploit) |

### Partition layout (relevant parts)

| Partition | Size | Purpose |
| --- | --- | --- |
| `boot_a` / `boot_b` | 32 MB | kernel + ramdisk (A/B) |
| `system_a` / `system_b` | 512 MB | plain ext4 |
| `vbmeta_a` / `vbmeta_b` | 64 KB | AVB (ATX AVB on Android Things) |
| `seccfg` | 256 KB | MediaTek lock flag |
| `userdata` | ~5 GB | plain ext4 — **this is what we edit** |

`userdata` and `system` dumps are **plaintext ext4**, not encrypted. That is the key enabler.

---

## 3. Tools

**Linux host (Ubuntu/Zorin/Debian-based):**
- `mtkclient` (BootROM exploit + flashing): `github.com/bkerler/mtkclient`
- `e2fsprogs` (`debugfs`, `e2fsck`)
- `android-tools-adb`, `build-essential`, `python3-venv`
- Android platform-tools (`fastboot`/`adb`)

**Build toolchain for the app:**
- JDK 17 (Temurin), Android platform API 27+ (`android.jar`), build-tools r34 (`aapt2`, `d8`, `zipalign`, `apksigner`)

**Hardware (optional):**
- A **1.8 V** USB-UART adapter (only if you want a hardware console; 3.3 V damages the SoC)

**Upstream references:**
- `bkerler/mtkclient`
- `Informatic/xiaomi-x04g-research`
- `untocodes/lenovo-cube-hacking` (authoritative notes: AVB.md, USB.md, Flashing.md)
- `deadman96385/android_kernel_lenovo_mt8167s` + `things_mt8167s_som_dump`

---

## 4. Why the obvious approaches fail

- **No USB adb.** The rear USB-A port is wired as USB *host* (OTG ID pin pulled low). Under Android it never enumerates as a USB device. BootROM/fastboot still work because the bootloader forces device mode.
- **No settings UI.** Android Things has no Settings app, no developer options.
- **No "Allow debugging?" prompt.** That dialog lives in SystemUI, which Android Things lacks. An unknown adb key just stays `unauthorized`.
- **A normal app can't start `adbd`** (runs as uid 10050) or write system settings.
- **The bootloader rejects modified boot images.** `seccfg unlock` allows *flashing* but **does not disable ATX AVB** verification. Proper AVB bypass needs `at_auth_unlock.py` + the leaked `cube_unlock_credentials_v2.zip` (deadman96385's XDA thread). That "breaks stock setup" (RPMB keys) — acceptable only if you're replacing the UI.

**Therefore:** we enabled everything **offline**, by editing the `userdata` ext4 image on a PC and flashing it back. Boot stays **stock** — no root, no Magisk, no AVB fight.

---

## 5. Step 0 — Backup everything (non-negotiable)

This is the **only way back to stock**. Lenovo no longer releases firmware.

### Enter BootROM mode
1. Unplug clock power.
2. Start the mtkclient command (it waits for a device).
3. Hold **Vol +**, plug in power, release when detected. The BootROM USB device (`0e8d:0003`) appears for a few seconds only.

### Run mtkclient
```sh
cd mtkclient && . .venv/bin/activate
python mtk.py printgpt                 # save to gpt.txt
python mtk.py rf flash.bin             # full raw eMMC image (~7.3 GiB)
# Dump every partition (or at minimum: boot_a/b, vbmeta_a/b, userdata, misc)
python mtk.py r dump
sha256sum dump/* flash.bin > dump.sha256
```

- Verify later with `sha256sum -c dump.sha256`.
- **Copy `dump/`, `flash.bin`, `dump.sha256` off the machine** before any write step.
- If a read dies mid-way with a USB I/O error, power-cycle and retry; use a direct USB port and a **data** cable (not charge-only).

### Optional unlocks
```sh
python mtk.py da seccfg unlock         # allow writing partitions (V4 lockstate)
```
Relock later with `python mtk.py da seccfg lock`.

> **ATX AVB unlock** (`at_auth_unlock.py creds.zip`) is only needed if you go the **patched-boot route (§ route A)**. It is **not** needed for the offline userdata injection used here.

---

## 6. The core trick — offline `userdata` provisioning with `debugfs`

We edit a **copy** of the stock `userdata` image (plaintext ext4) using `debugfs`, which can write files, set modes/owners, and set SELinux xattrs **without mounting and without sudo**.

### 6.1 Always replay the journal first
`userdata` dumps carry an unreplayed journal. If you skip this, `debugfs` reads zeros.

```sh
cp dump/userdata.bin userdata.custom.bin
e2fsck -fy userdata.custom.bin
```

### 6.2 What we inject (all paths are relative to the `userdata` root = `/data` on device)

| Goal | File on device | Contents / change | Owner / mode | SELinux label |
| --- | --- | --- | --- | --- |
| Enable adb | `/data/system/users/0/settings_global.xml` | `adb_enabled` `0`→`1` (setting id 55) | 1000:1000 `0600` | `u:object_r:system_data_file:s0` |
| adbd over TCP | `/data/property/persist.adb.tcp.port` | `5555` | 0:0 `0600` | `u:object_r:property_data_file:s0` |
| Start adbd | `/data/property/persist.sys.usb.config` | `adb` | 0:0 `0600` | `u:object_r:property_data_file:s0` |
| Trust our key | `/data/misc/adb/adb_keys` | host `adbkey.pub` | 1000:2000 `0640` | `u:object_r:adb_keys_file:s0` |
| Install our app | `/data/app/<pkg>-1/base.apk` | the APK | 0644 system:system | `u:object_r:apk_data_file:s0` |
| Pre-register app | `/data/system/packages.xml` | `<package>` + `<perms>` + `<sigs>` | 1000:1000 `0660` | `u:object_r:system_data_file:s0` |
| Match fingerprint | `packages.xml` `<version>` entries | same as `system` build (see §6.5) | — | — |
| Disable stock launchers | `/data/system/users/0/package-restrictions.xml` | set their `enabled="2"` | 1000:1000 `0660` | `u:object_r:system_data_file:s0` |
| Stop OTA | later, via adb (§8) | — | — | — |

> Persistent properties on this platform are **one file per property** in `/data/property/` — that's why a file containing `5555` is the whole setting.

### 6.3 A `debugfs` inject script pattern

```sh
# Prepare SELinux context strings (one per file, NO trailing newline)
printf 'u:object_r:system_data_file:s0'   > ctx_sys
printf 'u:object_r:property_data_file:s0' > ctx_prop
printf 'u:object_r:adb_keys_file:s0'      > ctx_adbkeys
printf 'u:object_r:apk_data_file:s0'      > ctx_apk

# Property files
printf '5555' > persist.adb.tcp.port
printf 'adb'  > persist.sys.usb.config

# Our key
cp ~/.android/adbkey.pub adb_keys

# Apply
debugfs -w -f inject.debugfs userdata.custom.bin
```

See [`examples/inject.debugfs`](examples/inject.debugfs) for a full script.

Then check the image is sane and flash it:

```sh
e2fsck -fn userdata.custom.bin
cd mtkclient && .venv/bin/python mtk.py w userdata ../userdata.custom.bin
```

**Why pre-registration in `packages.xml` is essential (the big gotcha):** on an **already-provisioned** device, PackageManagerService scans `/data/app` with `SCAN_REQUIRE_KNOWN` — any app **not already listed in `packages.xml` is treated as "orphaned" and deleted**. Dropping an APK into `/data/app` only works on a factory-fresh first boot. You must add the `<package>` entry (with a real signing cert in `<sigs>`) so the app is "known".

### 6.4 The `packages.xml` entry (what "known" means)

Add a `<package>` element with:
- `name="com.example.kiosk"`
- `codePath="/data/app/com.example.kiosk-1"`
- `userId="10050"` (pick a free uid)
- `nativeLibraryPath`, `primaryCpuAbi="armeabi-v7a"`, etc.
- `<sigs><cert index="11" key="<DER hex of your signing cert>"/></sigs>` — a real cert so the signature check passes.
- A `<perms>` block **listing every permission you need** (see §6.6).

See [`examples/packages-entry.xml`](examples/packages-entry.xml).

### 6.5 Match the build fingerprint

`packages.xml` records a build fingerprint in its `<version>` entries. If it doesn't match the booted system, PMS runs an upgrade/cleanup pass. Look at what the stock userdata recorded (e.g. `OIMK.211005.001/...`) and make sure it matches the `system` you boot. If you boot the other slot with a different build, fix the fingerprint string — otherwise you get "Upgrading from X to Y" and apps may be destroyed.

> Real-world note: one slot held `OIMK`, the other `OIMJ`. Booting the mismatched slot caused an upgrade cleanup that killed a not-yet-registered app. **Match the slot you'll boot, or update the fingerprint.**

### 6.6 Grant permissions yourself

When you pre-register a package, PMS trusts the stored `<perms/>` and does **not** auto-grant install-time permissions. So list them explicitly:

```xml
<perms>
  <item name="android.permission.INTERNET" granted="true" flags="0" />
  <item name="android.permission.ACCESS_NETWORK_STATE" granted="true" flags="0" />
  <item name="android.permission.CHANGE_WIFI_STATE" granted="true" flags="0" />
  <item name="android.permission.CHANGE_NETWORK_STATE" granted="true" flags="0" />
  <item name="android.permission.WAKE_LOCK" granted="true" flags="0" />
</perms>
```

Without this you get e.g. `SecurityException: ... neither user 10050 nor current process has ACCESS_WIFI_STATE`.

### 6.7 Disable stock launchers

In `/data/system/users/0/package-restrictions.xml`, set the stock launchers' `enabled="2"` so your app is the only `IOT_LAUNCHER` candidate. (In practice the "Select a home app" dialog also appears; see §7.)

---

## 7. The app (home screen + Wi-Fi + WebView)

### 7.1 App requirements for the offline route

- **`package="com.example.kiosk"`** — must match `packages.xml`.
- **`screenOrientation="landscape"`**.
- An activity with the **`IOT_LAUNCHER` intent category** (that's how Android Things resolves Home at runtime; `<preferred-activities>` and `<default-apps>` are empty on this device).
- **Signed with a stable key** (e.g. a `debug.jks`). Reinstalling in place via `adb install -r` requires the same key.

See [`examples/AndroidManifest.xml`](examples/AndroidManifest.xml).

### 7.2 Build pipeline (no Gradle needed)

`aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`. See [`examples/build.sh`](examples/build.sh). The included [`app/`](app/) builds this way with [`app/build.sh`](app/build.sh).

### 7.3 Wi-Fi is configured **in-app**, not by injection

**Key lesson:** injecting `/misc/wifi/WifiConfigStore.xml` alone **does not work** — the framework ignores it (supplicant disconnects, no config). What works is the app itself calling:

```java
WifiManager.addNetwork(config);   // open network or WPA2 config
WifiManager.enableNetwork(netId, true);
WifiManager.reconnect();
```

This needs `CHANGE_WIFI_STATE` + `CHANGE_NETWORK_STATE` + `ACCESS_NETWORK_STATE` **granted via `packages.xml`** (§6.6). Add a `WifiLock` and keep the screen on to avoid dozing.

### 7.4 WebView kiosk

```java
WebSettings s = webView.getSettings();
s.setJavaScriptEnabled(true);
s.setDomStorageEnabled(true);     // persists HA login
webView.setWebViewClient(new WebViewClient());  // handle self-signed certs if needed
webView.loadUrl("http://<SERVER_IP>:8123/lovelace/0");
```

The stock **Chromium 87** is modern enough for a modern dashboard. If the UI fails to render, have the app log errors/user-agent and consider a newer WebView or a dedicated kiosk browser.

**Decide up front:**
- Dashboard **URL** (http/https) — accept self-signed certs if needed.
- **Login:** sign in once on the touchscreen (DOM storage persists it) **or** bake in a long-lived access token.

---

## 8. Flashing and first boot

Flash **only** what you changed (everything else is restorable):

```sh
cd mtkclient && .venv/bin/python mtk.py w userdata ../userdata.custom.bin
```

- Keep `boot_a` **stock** (from your dump). `vbmeta_a` is already stock.
- Boot can take **5–10 minutes** on the first boot after a userdata change.
- Power-cycle (no buttons).

### Expected signals
- **"Select a home app"** dialog listing **Assistant Core** + **your app** → your app installed correctly. Pick yours, choose **Always**.
- Your app renders full-screen landscape. ✅
- Router DHCP client list shows the clock (e.g. under a new IP) → Wi-Fi works.

### If you get a black screen / stuck boot
- Reflash `dump/userdata.bin` to recover (device stays safe).
- Check `uiderrors.txt` and logs on the next dump read if needed.

---

## 9. Connect over Wi-Fi and iterate without flashing

Once adb-over-Wi-Fi is up (pre-injected key, no prompt), **flash cycles are over**:

```sh
adb connect <CLOCK_IP>:5555
adb devices                                # expect "... device", not "unauthorized"
adb -s <CLOCK_IP>:5555 shell id            # uid shell
adb -s <CLOCK_IP>:5555 install -r app.apk  # seconds
adb -s <CLOCK_IP>:5555 logcat
```

- The IP is DHCP and may change; put the current IP on your app's screen (e.g. a "NETWORK" tab) so you can always read it.
- If you get `offline`/no route: tap the screen (device may have dozed), read the IP, reconnect.

### Using a second computer (e.g. a Mac) as your dev machine

The clock trusts **only** the key you injected into `adb_keys`. Give adb that key as an **extra** key — do **not** replace the dev machine's own key:

```sh
# ~/.zshrc
export ADB_VENDOR_KEYS="$HOME/path/to/injected/adbkey"

adb kill-server        # the server reads ADB_VENDOR_KEYS only at startup
adb connect <CLOCK_IP>:5555
adb devices
```

adb offers the machine's own key first, then the vendor key. Other devices keep using the machine key. Alternatively, copy the whole `adbkey`/`adbkey.pub` pair to the machine's `~/.android/` (but don't clobber existing keys if you use other devices).

---

## 10. Post-setup: stop updates and the `mediashell` crash loop

Run once from the dev machine. Both persist across reboots.

```sh
# Stop the Cast/Assistant shell crash loop (crashes every ~3 s after ATX unlock)
adb shell pm disable-user --user 0 com.google.android.apps.mediashell

# Point the OTA updater at a non-existent channel.
# --nopowerwash is REQUIRED: without it, a channel change can factory-reset the device.
adb shell update_engine_client --channel=none-channel --nopowerwash
```

Verify:

```sh
adb shell pm list packages -d | grep mediashell        # -> package:com.google.android.apps.mediashell
adb shell update_engine_client --show_channel           # -> ... none-channel
adb shell update_engine_client --status                # -> CURRENT_OP=UPDATE_STATUS_IDLE
```

Undo:

```sh
adb shell pm enable com.google.android.apps.mediashell
adb shell update_engine_client --channel=gb-stable-channel --nopowerwash
```

**Why `mediashell` crashes:** on start it loads factory Cast credentials from the hardware keystore. The ATX unlock changed the boot state the keystore checks, so it refuses (`Failed to load factory credentials!` ← `KeyStoreException: Invalid argument`). It can't be fixed without relocking the bootloader; if you don't use Cast/Assistant, disable it. A lingering process (it's persistent) goes away after the next reboot.

**Why stop updates:** `update_engine` defaults to `POLICY_APPLY_AND_REBOOT` on channel `gb-stable-channel`, checks shortly after each boot, and writes an update to the inactive slot. An untested update could break your setup.

**Why not disable the update manager package?** `com.google.android.things.internal.devicemanagement` is a persistent, system-uid core package that also provides `FallbackHome`. Disabling it risks a boot hang, and it may not stop the native `update_engine` anyway. The channel change only touches the updater.

**Optional extra prompt:** `com.google.assistant.core` also registers a HOME activity. Disable it too if you want a zero-prompt boot without relying on the "Always" default:

```sh
adb shell pm disable-user --user 0 com.google.assistant.core
```

---

## 11. Recovery and restore stock

All raw-partition flashing uses **mtkclient in BootROM mode** on the Linux box, not adb.

### Full restore to working stock
Enter fastboot (unplug, hold **Vol +**, plug power, hold ~10 s), then:

```sh
fastboot flash boot_a dump/boot_a.bin
fastboot flash boot_b dump/boot_b.bin
fastboot flash vbmeta_a dump/vbmeta_a.bin
fastboot flash vbmeta_b dump/vbmeta_b.bin
fastboot flash userdata dump/userdata.bin
fastboot flash misc dump/misc.bin
fastboot set_active a
fastboot reboot
```

Optionally relock: `fastboot oem at-lock-vboot!` and `mtk.py da seccfg lock`.

### Other symptoms
- **Boot loop:** restore stock boots; consider `mtk.py da vbmeta 3` (note: on this device editing a *signed* vbmeta invalidates it — it did not work here).
- **"Decrypting" loop:** `mtk.py e metadata,userdata` (factory reset — you lose your injected data).

---

## 12. Lessons learned (short list)

1. **Backup first, verify, and copy off-machine.** There is no factory firmware to download.
2. **USB adb is impossible** without a hardware mod (host-mode OTG ID pin). Plan for Wi-Fi adb or UART.
3. **`seccfg unlock` ≠ AVB unlock.** Android Things uses ATX AVB; `seccfg` only allows flashing. Bypassing AVB needs credentials and breaks stock setup — avoid it by using the offline userdata route.
4. **`userdata` is plaintext ext4.** Replay the journal (`e2fsck -fy`) or `debugfs` reads zeros.
5. **Dropping an APK in `/data/app` does not install it** on a provisioned device. Pre-register in `packages.xml` with a real cert.
6. **Match the build fingerprint** in `packages.xml` to the booted system, or PMS runs an upgrade cleanup.
7. **PMS won't auto-grant permissions** to a pre-registered app. List them in `<perms>`.
8. **Wi-Fi config injection doesn't work** — configure Wi-Fi in-app (`WifiManager`) with permissions granted via `packages.xml`.
9. **Disable `mediashell` and stop OTA** after first boot. Use `--nopowerwash` when changing the update channel.
10. **Once adb-over-Wi-Fi works, never flash for app changes again.** `adb install -r` + `logcat`.
11. **Keep the signing key stable** — in-place updates require it.
12. **UART is the reliable fallback** if you ever need a shell independent of the framework: `fastboot p2u on` + a **1.8 V** adapter on the SuperSpeed pins (921600 8N1). 3.3 V will kill the SoC.

---

## 13. Placeholder reference

Replace these throughout with your own values:

| Placeholder | Meaning |
| --- | --- |
| `<CLOCK_IP>` | The clock's current DHCP address |
| `<WIFI_SSID>` / `<WIFI_PASSWORD>` | Your 2.4 GHz network |
| `<ROUTER_IP>` | Your router's admin address |
| `<SERVER_IP>` | Host running your web dashboard |
| `com.example.kiosk` | Your app's package name |
| `<DER hex>` | Hex of your signing certificate (for `<sigs>`) |
| `OIMJ.201212.001` / `OIMK.211005.001` | Example build fingerprints (match your slot) |
| `user@linux-host` | The machine whose adb key was injected |

---

*End of tutorial. Keep `dump/`, `flash.bin`, and `dump.sha256` safe — they are your only way back to stock.*
