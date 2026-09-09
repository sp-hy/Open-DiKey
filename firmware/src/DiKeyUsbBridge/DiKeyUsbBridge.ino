// DiKey USB↔BLE bridge for DiLink 5.
//
// Target: ESP32-C3 Super Mini (native USB CDC).
// Merged image: dikey-usb-bridge-c3.bin — flash at 0x0 via https://esp.huhn.me/
//
// Arduino IDE:
//   Board: ESP32C3 Dev Module
//   USB CDC On Boot: Enabled
//   Library: NimBLE-Arduino (h2zero)
//
// Native USB → car. C3 is BLE central to DiKey (FF10 / FF11 / FF12).
//
// USB lines (LF). Hex uppercase, spaces optional.
//   Host→bridge: PING | ID | STATUS | SCAN | TX <hex>
//   Bridge→host: HELLO ODK-BRIDGE | PONG | ID ODK-BRIDGE
//            STATE SCANNING|CONNECTING|READY|DISCONNECTED …
//            RX <hex> | TXOK | TXERR <reason> | HB <state> | LOG …
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <NimBLEScan.h>
#include <NimBLEAdvertisedDevice.h>
#include <string.h>

namespace {
const NimBLEUUID kSvcUuid((uint16_t)0xFF10);
const NimBLEUUID kWriteUuid((uint16_t)0xFF11);
const NimBLEUUID kNotifyUuid((uint16_t)0xFF12);

enum class BridgeState : uint8_t { Idle, Scanning, Connecting, Ready };

BridgeState state = BridgeState::Idle;
NimBLEClient *client = nullptr;
NimBLERemoteCharacteristic *writeChr = nullptr;
NimBLEAddress pendingAddr;
bool pendingConnect = false;
char pendingName[32] = {};
bool usbAnnounced = false;
String lineBuf;
unsigned long lastHbMs = 0;

const char *stateName(BridgeState s) {
  switch (s) {
    case BridgeState::Scanning: return "SCANNING";
    case BridgeState::Connecting: return "CONNECTING";
    case BridgeState::Ready: return "READY";
    default: return "IDLE";
  }
}

void usbPrint(const char *line) { Serial.println(line); }

void printHexPrefixed(const char *prefix, const uint8_t *data, size_t len) {
  Serial.print(prefix);
  Serial.print(' ');
  for (size_t i = 0; i < len; i++) {
    if (data[i] < 16) Serial.print('0');
    Serial.print(data[i], HEX);
  }
  Serial.println();
}

void printState() {
  Serial.print("STATE ");
  Serial.print(stateName(state));
  if (client != nullptr && client->isConnected()) {
    Serial.print(' ');
    Serial.print(client->getPeerAddress().toString().c_str());
  } else if (pendingName[0] != 0 && state == BridgeState::Connecting) {
    Serial.print(' ');
    Serial.print(pendingName);
  }
  Serial.println();
}

void setState(BridgeState next) {
  state = next;
  printState();
}

void startScan();

class ClientCallbacks : public NimBLEClientCallbacks {
  void onConnect(NimBLEClient *c) override {
    (void)c;
    usbPrint("LOG GATT connected");
  }
  void onConnectFail(NimBLEClient *c, int reason) override {
    (void)c;
    Serial.printf("LOG connect fail %d\n", reason);
    writeChr = nullptr;
    if (client != nullptr) {
      NimBLEDevice::deleteClient(client);
      client = nullptr;
    }
    setState(BridgeState::Idle);
    startScan();
  }
  void onDisconnect(NimBLEClient *c, int reason) override {
    (void)c;
    Serial.printf("STATE DISCONNECTED %d\n", reason);
    writeChr = nullptr;
    if (client != nullptr) {
      NimBLEDevice::deleteClient(client);
      client = nullptr;
    }
    state = BridgeState::Idle;
    startScan();
  }
};

ClientCallbacks clientCallbacks;

bool nameLooksLikeDiKey(const std::string &name) {
  if (name.empty()) return false;
  String n(name.c_str());
  n.toLowerCase();
  return n.indexOf("dikey") >= 0;
}

class ScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice *dev) override {
    if (pendingConnect || state == BridgeState::Connecting || state == BridgeState::Ready) {
      return;
    }
    if (!nameLooksLikeDiKey(dev->getName()) && !dev->isAdvertisingService(kSvcUuid)) {
      return;
    }
    pendingAddr = dev->getAddress();
    strncpy(pendingName, dev->getName().c_str(), sizeof(pendingName) - 1);
    pendingName[sizeof(pendingName) - 1] = 0;
    pendingConnect = true;
    NimBLEDevice::getScan()->stop();
    Serial.printf("LOG found %s %s\n", pendingAddr.toString().c_str(),
                  pendingName[0] ? pendingName : "(no name)");
  }
};

ScanCallbacks scanCallbacks;

void notifyCb(NimBLERemoteCharacteristic *c, uint8_t *data, size_t len, bool isNotify) {
  (void)c;
  (void)isNotify;
  if (data == nullptr || len == 0) return;
  printHexPrefixed("RX", data, len);
}

void startScan() {
  writeChr = nullptr;
  pendingConnect = false;
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setScanCallbacks(&scanCallbacks, false);
  scan->setActiveScan(true);
  scan->setInterval(80);
  scan->setWindow(40);
  scan->start(0, false, true);
  setState(BridgeState::Scanning);
}

bool setupGatt(NimBLEClient *c) {
  NimBLERemoteService *svc = c->getService(kSvcUuid);
  if (svc == nullptr) {
    usbPrint("LOG no FF10 service");
    return false;
  }
  writeChr = svc->getCharacteristic(kWriteUuid);
  NimBLERemoteCharacteristic *notify = svc->getCharacteristic(kNotifyUuid);
  if (writeChr == nullptr || notify == nullptr) {
    usbPrint("LOG missing FF11/FF12");
    writeChr = nullptr;
    return false;
  }
  if (!notify->subscribe(true, notifyCb, true)) {
    usbPrint("LOG subscribe FF12 failed");
    writeChr = nullptr;
    return false;
  }
  return true;
}

void doConnect() {
  setState(BridgeState::Connecting);
  if (client != nullptr) {
    NimBLEDevice::deleteClient(client);
    client = nullptr;
  }
  client = NimBLEDevice::createClient();
  client->setClientCallbacks(&clientCallbacks, false);
  client->setConnectTimeout(10000);
  if (!client->connect(pendingAddr)) {
    usbPrint("LOG connect() false");
    NimBLEDevice::deleteClient(client);
    client = nullptr;
    writeChr = nullptr;
    setState(BridgeState::Idle);
    startScan();
    return;
  }
  if (!setupGatt(client)) {
    client->disconnect();
    return;
  }
  setState(BridgeState::Ready);
}

int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

bool parseHex(const String &s, uint8_t *out, size_t maxLen, size_t *outLen) {
  *outLen = 0;
  int hi = -1;
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == ' ' || c == '\t') continue;
    int v = hexVal(c);
    if (v < 0) return false;
    if (hi < 0) {
      hi = v;
    } else {
      if (*outLen >= maxLen) return false;
      out[(*outLen)++] = static_cast<uint8_t>((hi << 4) | v);
      hi = -1;
    }
  }
  return hi < 0 && *outLen > 0;
}

void handleTx(const String &hexPart) {
  if (state != BridgeState::Ready || writeChr == nullptr || client == nullptr ||
      !client->isConnected()) {
    usbPrint("TXERR not ready");
    return;
  }
  uint8_t buf[64];
  size_t n = 0;
  if (!parseHex(hexPart, buf, sizeof(buf), &n)) {
    usbPrint("TXERR bad hex");
    return;
  }
  const bool withResp = writeChr->canWrite();
  if (!writeChr->writeValue(buf, n, withResp)) {
    usbPrint("TXERR gatt write");
    return;
  }
  usbPrint("TXOK");
}

void announceUsb() {
  usbPrint("HELLO ODK-BRIDGE");
  printState();
}

void handleLine(String line) {
  line.trim();
  if (line.isEmpty()) return;
  if (line.equalsIgnoreCase("PING")) {
    usbPrint("PONG");
    return;
  }
  if (line.equalsIgnoreCase("ID")) {
    usbPrint("ID ODK-BRIDGE");
    printState();
    return;
  }
  if (line.equalsIgnoreCase("STATUS")) {
    printState();
    return;
  }
  if (line.equalsIgnoreCase("SCAN")) {
    if (client != nullptr && client->isConnected()) {
      client->disconnect();
    } else {
      startScan();
    }
    return;
  }
  if (line.startsWith("TX ") || line.startsWith("tx ")) {
    handleTx(line.substring(3));
    return;
  }
  usbPrint("ERR unknown");
}
} // namespace

void setup() {
  Serial.begin(115200);
  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 2000) {
    delay(10);
  }
  NimBLEDevice::init("ODK-Bridge");
  startScan();
  if (Serial) {
    announceUsb();
    usbAnnounced = true;
  }
}

void loop() {
  const bool usbUp = static_cast<bool>(Serial);
  if (usbUp && !usbAnnounced) {
    announceUsb();
    usbAnnounced = true;
  } else if (!usbUp) {
    usbAnnounced = false;
  }

  while (Serial.available() > 0) {
    char c = static_cast<char>(Serial.read());
    if (c == '\n') {
      handleLine(lineBuf);
      lineBuf = "";
    } else if (c != '\r' && lineBuf.length() < 200) {
      lineBuf += c;
    }
  }

  if (pendingConnect) {
    pendingConnect = false;
    doConnect();
  }

  unsigned long now = millis();
  if (usbUp && (now - lastHbMs) >= 8000) {
    lastHbMs = now;
    Serial.printf("HB %s\n", stateName(state));
  }
  delay(5);
}
