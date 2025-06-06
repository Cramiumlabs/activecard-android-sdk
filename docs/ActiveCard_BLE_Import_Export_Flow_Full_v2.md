
# Active Card and Mobile App BLE Import / Export Flow (2‑of‑3 MPC)

This specification adapts the Key Import & Export procedure to the BLE ↔ gRPC transport stack shared by Active Card (C), Mobile App (A), and Cloud Relay (B). It covers both exporting a wallet’s secret (to mnemonic form) and importing that mnemonic back into a fresh 2‑of‑3 MPC group.

---

## 0. Transport Summary

| Link         | Transport                  | Security                                 |
|--------------|-----------------------------|-------------------------------------------|
| Pi (C) ⇄ Mi (A) | BLE GATT Write‑With‑Response | AES‑GCM in `TransportMessageWrapper`     |
| Mi (A) ⇄ S (B)  | gRPC bidirectional stream    | TLS 1.3 + per‑message AES‑GCM             |
| Pi (C) ⇄ S (B)  | Proxied via Mi              | Double‑encrypted (BLE + gRPC)             |

Two new control ID ranges in `ActiveCardEvent`:

```kotlin
// Export control
IE_EXPORT_REQUEST  (1400)
IE_EXPORT_SHARE    (1401)
IE_EXPORT_RESULT   (1402)

// Import control
IE_IMPORT_REQUEST  (1500)
IE_IMPORT_DISTRIB  (1501)
IE_IMPORT_CONFIRM  (1502)
```

---

## 1. Export Flow (Get Mnemonic)

### 1.1 Participants & Threshold

- **A / Mobile** – secret holder and UI owner
- **C / Card** – provides its VSS share
- **B / Cloud** – optional third share

Threshold: `t = 2`, Parties: `n = 3`

### 1.2 Sequence Diagram

```mermaid
sequenceDiagram
    participant Mi as Mobile App (A)
    participant S as Cloud Relay (B)
    participant Pi as Active Card (C)

    %% 0 – Initiate
    Mi->>S: IE_EXPORT_REQUEST(walletID)
    S-->>Mi: OK / sessionID

    %% 1 – Collect shares
    Mi-->>Pi: Request share (QR or BLE cmd)
    Pi-->>Mi: IE_EXPORT_SHARE(Xi_C, ShareID_C)
    Mi->>S:  Request share_B
    S-->>Mi: IE_EXPORT_SHARE(Xi_B, ShareID_B)

    %% 2 – Reconstruct secret
    Note over Mi: ReConstruct() with (Xs, Xi) → secret
    Note over Mi: Derive BIP‑39 mnemonic

    %% 3 – Deliver mnemonic
    Mi-->>User: Display/Save 24‑word mnemonic
    Mi->>S: IE_EXPORT_RESULT(OK)
```

### 1.3 Control Messages (Protobuf)

```proto
message IEExportShare {
  bytes  session_id = 1;
  bytes  share_id   = 2;
  bytes  xi         = 3;
  string ec_type    = 4;
}
```

### 1.4 Cryptographic Details

- Share integrity: Verified via `Xi * G == Public_i`
- Secret reconstructed with `ReConstruct()`
- Encoded using **BIP‑39** mnemonic
- Secret wiped after delivery

---

## 2. Import Flow (Mnemonic → Create New 2‑of‑3 Wallet)

### 2.1 Preconditions

- User holds a valid BIP‑39 mnemonic
- At least one prior shard exists (Mobile or Cloud)
- Card is factory-reset with no active wallet

### 2.2 High-Level Steps

| # | Stage             | Actor(s)        | Description |
|---|-------------------|-----------------|-------------|
| 1 | Import Init       | Mobile          | User inputs mnemonic, Mobile derives secret |
| 2 | Key Derivation    | Mobile          | Derive SLIP‑10 key node, root key |
| 3 | Share Splitting   | Mobile          | Run Split(secret, t=2, n=3), generate VSS |
| 4 | Session Start     | Mobile → Cloud  | Send IE_IMPORT_REQUEST |
| 5 | Distribute Xi_C   | Mobile → Card   | Encrypt, send shard to Card, await confirm |
| 6 | Persist Xi_B      | Mobile → Cloud  | Encrypt and send to Cloud (HSM) |
| 7 | Persist Xi_A      | Mobile          | Store shard A in secure enclave |
| 8 | Finalize          | Mobile → All    | IE_IMPORT_RESULT(OK), mark success |

### 2.3 Sequence Diagram

```mermaid
sequenceDiagram
    participant Mi as Mobile App (A)
    participant S as Cloud Relay (B)
    participant Pi as New Active Card (C')

    %% 0 – User inputs mnemonic
    User-->>Mi: 24‑word mnemonic
    Note over Mi: Derive secret → Split(t=2,n=3)

    %% 1 – Session setup
    Mi->>S: IE_IMPORT_REQUEST(walletID=?, t=2, n=3)
    S-->>Mi: sessionID

    %% 2 – Card share distribution
    Mi-->>Pi: IE_IMPORT_DISTRIB(sessionID, Xi_C', ShareID_C', commitments)
    Pi-->>Mi: IE_IMPORT_CONFIRM(OK)

    %% 3 – Cloud share distribution
    Mi->>S:  IE_IMPORT_DISTRIB(sessionID, Xi_B', ShareID_B', commitments)
    S-->>Mi: ACK (stored)

    %% 4 – Local persist & finish
    Mi-->>Mi: Store Xi_A', ShareID_A'
    Mi->>S:  IE_IMPORT_RESULT(OK)
```

### 2.4 Share & Commitment Format

```proto
message IEImportDistrib {
  bytes  session_id   = 1;
  uint32 idx          = 2;
  bytes  share_id     = 3;
  bytes  xi_enc       = 4;
  repeated bytes vss_commit = 5;
}
```

- Verifies Feldman VSS locally
- Degree must be `t - 1`
- Invalid points are rejected

### 2.5 Security Enhancements

- Local verification: Card verifies commitments without Cloud
- No transient secret: Secret cleared after Split()
- Replay-safe: Card rejects import if wallet exists

---

## 3. Error Codes (Shared)

| Code | Meaning              | Action                                 |
|------|----------------------|----------------------------------------|
| 1    | SHARE_NOT_FOUND      | Suggest 2‑of‑2 fallback                |
| 2    | BAD_SHARE            | Abort and show invalid party           |
| 3    | TIMEOUT              | Offer retry                            |
| 4    | PERSIST_FAIL         | Prompt user to re-seat or reset card   |

---

## 4. Security Notes

- **End‑to‑end encryption**: Xi values encrypted before wrapper
- **One‑time secrets**: RAM wiped after use
- **HSM isolation**: Cloud never sees mnemonic
- **User education**: Warn users that export is sensitive and one-time
