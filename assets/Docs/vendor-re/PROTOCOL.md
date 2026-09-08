# DiKey BLE protocol notes

Reverse-engineered from vendor APK **迪铠智联** `com.easytech.link` **v1.1.4**, then validated on hardware (Open DiKey probe).

| Artifact | Location |
|----------|----------|
| APK (local only) | `assets/Vendor APK/迪铠智联1.1.4.apk` |
| JADX sources (gitignored) | `assets/Docs/vendor-re/jadx-out/sources/` |
| Primary service | `BluetoothDeviceService` |
| Frame builder | `h6.b` (`BleProtocolFrame`) |
| Inbound parsers | `y6.he`, `y6.r3` |

**Scope:** BLE control of the aftermarket DiKey center-console smart key (buttons, dials/LCDs, ambient strip, key backlight).  
**Out of scope:** vehicle APIs — the key only reports inputs; the host app drives BYD climate/etc. via `bydauto` / ADB.

---

## 1. Connection model

| Topic | Fact |
|-------|------|
| Transport | BLE GATT central only (`TRANSPORT_LE`) |
| Pairing | **None** for control — no `createBond` / `setPin` / pairing-request handler |
| Connect API | `device.connectGatt(ctx, false /* autoConnect */, callback, TRANSPORT_LE)` |
| Identity | Host stores MAC in its own DB/prefs (“bound” ≠ Android Paired devices) |
| Typical name | Advertises as **`DiKey`** (exact / contains; case-insensitive) |
| OS Pair menu | Often shows “pairing…” then fails — expected; use in-app GATT |

**Platform caveat:** Some DiLink 5 head units have a broken / limited BLE LE observer (0 ads). Classic BT can work while LE scan/connect fails. A phone or ESP bridge can still talk to the key.

**Session recipe:**

1. Scan → select DiKey (or `getRemoteDevice(savedMac)`)
2. GATT connect → discover services
3. Enable notify on FF12 (write CCCD `00002902` = `0x01 0x00`)
4. Send setup: **`0x08` → `0x06` + `0x07` → optional dial/LED**
5. Serialize writes: **one GATT write in flight**; advance on `onCharacteristicWrite` (device ACK on FF12 is optional evidence)

---

## 2. GATT layout

### Control plane (required)

| Role | UUID |
|------|------|
| Service | `0000FF10-0000-1000-8000-00805F9B34FB` |
| Write (host → device) | `0000FF11-0000-1000-8000-00805F9B34FB` |
| Notify (device → host) | `0000FF12-0000-1000-8000-00805F9B34FB` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

### JieLi OTA (firmware only — ignore for control)

| Role | UUID |
|------|------|
| Service | `0000AE00-0000-1000-8000-00805F9B34FB` |
| Write | `0000AE01-0000-1000-8000-00805F9B34FB` |
| Notify | `0000AE02-0000-1000-8000-00805F9B34FB` |

---

## 3. Framing

### Host → device (write **FF11**)

```
AA 55 | LEN | CMD | PAYLOAD… | CHK
```

### Device → host (notify **FF12**)

```
AA 66 | LEN | CMD | PAYLOAD… | CHK
```

| Field | Rule |
|-------|------|
| Magic | Host `AA 55`; device `AA 66` |
| `LEN` | `payload.length + 4` |
| `CHK` | `(sum of all bytes before CHK) & 0xFF` |

Builder equivalent (`h6.b`): start with magic + LEN + CMD + payload, then append checksum of those bytes.

**Worked checksum** — left dial switch to driver temp 24:

```
AA 55 07 02 02 18 01 | CHK
0xAA+0x55+0x07+0x02+0x02+0x18+0x01 = 0x123 → CHK = 0x23
Frame: AA 55 07 02 02 18 01 23
```

Inbound long frames are fixed size (`y6.he.a`): button notify **7** bytes, encoder notify **9** bytes (including CHK). Parser rejects bad magic/checksum.

---

## 4. Host → device commands

| CMD | Purpose |
|-----|---------|
| `0x01` | Right dial LCD |
| `0x02` | Left dial LCD |
| `0x03` | LED strip |
| `0x05` | Key backlight |
| `0x06` | Right dial mode allow-list |
| `0x07` | Left dial mode allow-list |
| `0x08` | Fan/media/nav ranges + temp numeral style |
| `0x09` | Version query |
| `0x11`–`0x13` | Whack-a-mole game enter / start / exit |
| `0x15` / `0x16` | Charging preview / colors |
| `0x17` | Batch dial digit values (types 2…16) |

### Mandatory setup (after notify enabled)

Without this, LCD may paint once then fail to rotate / drop mode (seen with passenger temp & volume):

1. **`0x08`** once  
2. **`0x06` and `0x07`** allow-lists including every `displayType` you will use  
3. Then **`0x01`/`0x02`** (and LED/`0x05` as needed)

**Reconnect note:** Device may boot dials to a default temperature (often ~21). Host must **re-push** last dial types/values after setup. Persist that state in the host app (not on the key).

---

## 5. Device → host events

### Buttons — notify `CMD 0x10` (7 bytes)

```
AA 66 | LEN | 10 | buttonId | eventType | CHK
```

| Field | Meaning |
|-------|---------|
| `buttonId` | Two banks for the **same physical key** |
| | `0…9` = **UP** face |
| | `16…25` = **DOWN** face → normalize `id - 16` |
| Logical name | `btn{10 − normalized}` (vendor `o7.d`) e.g. raw `9`/`25` → `btn1` |
| `eventType` | `0` CLICK, `1` DOUBLE_CLICK, `2` LONG_PRESS |

Do **not** collapse `16–25` → `0–9` before deciding direction — the bank **is** UP vs DOWN.  
(Some vendor automation strings invert labels for vehicle actions; **physical** faces are UP=`0…9`, DOWN=`16…25`, confirmed on hardware.)

### Dials — notify `CMD 0x11` (9 bytes)

```
AA 66 | LEN | 11 | position | eventType | displayType | currentValue | CHK
```

| Field | Values |
|-------|--------|
| `position` | `0x20` RIGHT, `0x21` LEFT |
| `eventType` | `0` SINGLE_CLICK, `1` MULTI_CLICK, `2` LONG_PRESS, `3` ROTATE_RIGHT, `4` ROTATE_LEFT |
| `displayType` | `1…16` (see table; includes media `0x0F`, nav `0x10`) |
| `currentValue` | uint8 currently shown |

Short `0x11` with only a status byte → game-enter ACK path (ignore for normal control).

---

## 6. Dial LCD — `CMD 0x01` (right) / `0x02` (left)

```
AA 55 07 | CMD | displayType | value | switchDisplay | CHK
```

| Field | Meaning |
|-------|---------|
| CMD | `0x01` right, `0x02` left |
| `switchDisplay` | `1` = change that dial’s mode; `0` = value-only refresh of **same** type |
| | Always sending `1` makes first write work then later updates stick |
| Mirroring | One write hits **one** dial. If both dials use the **same** `displayType`, they share that type’s value and look mirrored |
| Type switch | Re-assert the **other** dial’s last type/value (`switchDisplay=1`) so it keeps its mode |

Examples:

```
AA 55 07 02 02 18 01 23   # left → driver temp 24, switch=1
AA 55 07 02 02 19 00 …   # left → temp 25, same type, switch=0
```

### Display types (`r6.w1`)

| Type | Label | Typical range |
|------|-------|---------------|
| `0x01` | None / blank | — |
| `0x02` | Driver temp | 16–32 |
| `0x03` | Driver fan | 1–7 |
| `0x04` | Passenger temp | 16–32 |
| `0x05` | Passenger fan | 1–7 |
| `0x06`–`0x0D` | Rear zone temp/fan pairs | temp 16–32, fan 1–7 |
| `0x0F` | **Media volume** | 0–39 (common default max) |
| `0x10` | **Nav volume** | 0–10 (common default max) |

Temp numeral style (`r6.i1`): `0` digits, `1` type prefix, `2` degree symbol (default) — set only via **`0x08`**, not the LCD frame.

### Range / style — `CMD 0x08`

```
AA 55 0F 08 | 11 21 | fanMin fanMax | mediaMin mediaMax | navMin navMax | 01 06 | style | CHK
```

Common defaults: fan **1–7**, media **0–39**, nav **0–10**, style **`2`** (°).

### Mode allow-list — `CMD 0x06` (right) / `0x07` (left)

```
AA 55 13 | 06|07 | [15 bytes: types 2…16] | CHK
```

For type `t` in 2…16, payload index `t - 2` = `t` if allowed, else `0`.  
“None” special-case: first payload byte `1`.

Example — allow driver/passenger temp+fan and media+nav on right (`0x06`):

```
slots for types 2..16:
02 03 04 05 00 00 00 00 00 00 00 00 00 0F 10
```

### Batch — `CMD 0x17`

15 values for types 2…16. Full UI dump; not required for single-dial updates.

---

## 7. LED strip — `CMD 0x03`

```
AA 55 09 03 | mode | position | G | R | B | CHK
```

Colors on the wire are **GRB** (app memory is often RGB).

| mode | Meaning |
|------|---------|
| `0` | Off |
| `1` | Blink |
| `2` | Flow |
| `3` | Solid on |
| `4` | Breath |

### Strip targets (`j7.m` + turn/hazard)

| Pos | Name | Meaning |
|-----|------|---------|
| `1` | LEFT | Left bar only |
| `2` | CENTER | Middle bar only |
| `3` | RIGHT | Right bar only |
| `4` | ALL | **All bars** (1+2+3) — ambient/scene default |
| `5` | LEFT_RIGHT | **Both outer bars** (1+3) — turn/hazard |

Positions 4 and 5 are multi-zone broadcasts, not extra physical strips. Strip state usually persists on the device across app reconnect; host need only restore UI prefs, not re-apply LED, unless desired.

---

## 8. Key backlight — `CMD 0x05`

```
AA 55 07 05 | G | R | B | CHK
```

Drives the **soft glow on the physical buttons** (separate from strip `0x03`).

- Vendor default prefs often dim blue RGB `(0,0,80)` on the wire as GRB; other scenes/firmware residual can look pink/red.
- **Clear:** `0x05` with **G=R=B=0**.
- Strip commands do **not** clear key backlight.

---

## 9. Notify ACKs / other

| CMD | Direction | Purpose |
|-----|-----------|---------|
| `0x00`–`0x08` | notify | ACK for matching host CMD (`status==1` success) |
| `0x09` | notify | Version `maj.min` |
| `0x12`–`0x14` | notify | Game state |

Queueing: advance on **GATT write callback**; treat FF12 ACK as optional confirmation.

---

## 10. Implementer checklist

1. GATT LE connect to FF10; CCCD on FF12; write FF11.  
2. No OS pairing.  
3. Frame with `AA 55` / `AA 66` + LEN + CMD + payload + sum checksum.  
4. After Ready: `0x08`, then `0x06`+`0x07`, then dial/LED.  
5. Dial: `switchDisplay=1` only on type change; re-assert other dial; persist & restore host-side on reconnect.  
6. Buttons: keep raw id banks for UP/DOWN; logical `btn{10-n}`.  
7. LED: GRB; pos 1–3 physical, 4=all, 5=both sides; key glow = `0x05`.  
8. Map button/dial events to vehicle APIs in the host — not via BLE opcodes to the key.

---

## 11. Key source pointers (jadx)

| Topic | File |
|-------|------|
| UUIDs | `BluetoothDeviceService` ctor (~L842), `y6.he` |
| Frame build | `h6/b.java` |
| Inbound button/encoder | `y6/he.java`, `y6/r3.java` |
| Dial LCD / `switchDisplay` | `BluetoothDeviceService` ~L9552, `y6/xb.java` |
| Mode allow-list | `y6/eb.java` `g()` |
| Range `0x08` | `BluetoothDeviceService` ~L26760 |
| LED strip / positions | ~L30909, `j7/l.java`, `j7/m.java`, `j7/n.java` |
| Button backlight | ~L29377, `j7/k.java` |
| Display types | `r6/w1.java`, `r6/i1.java` |
| Knob ids | `y6/vi.java` |
| Encoder gestures | `y6/ih.java` |
| GATT connect | `p7/ae.java` `connectGatt(…, false, …, 2)` |
