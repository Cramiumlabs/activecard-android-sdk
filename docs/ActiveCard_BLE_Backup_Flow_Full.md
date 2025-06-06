
# ActiveCard and Mobile App BLE Backup Flow (t‑of‑n MPC)

This specification adapts the “Backup (t‑of‑n)” workflow from the overall MPC architecture to the BLE ↔ gRPC transport used by Active Card (C), Mobile App (A), and Cloud Relay (B).

---

## 0. Transport Summary

| Link          | Transport                  | Security                                 |
|---------------|-----------------------------|-------------------------------------------|
| Pi (C) ⇄ Mi (A) | BLE GATT Write-With-Response | AES-GCM inside `TransportMessageWrapper` |
| Mi (A) ⇄ S (B)  | gRPC bidirectional stream    | TLS-1.3, per-message AES-GCM              |
| Pi (C) ⇄ S (B)  | Proxied via Mi              | Double-encrypted (BLE + gRPC)            |

All control frames and MPC rounds reuse the `ActiveCardEvent` ID space; backup adds a dedicated control preamble (IDs 1200–1203):

```kotlin
enum class ActiveCardEvent(val id: Int) {
    // ... pairing 1–11, key‑gen 1001–1011, wallet‑create 1100–1103 ...
    BK_BACKUP_REQUEST (1200),
    BK_SESSION_INFO   (1201),
    BK_JOIN_REQUEST   (1202),
    BK_SESSION_START  (1203)
}
```

---

## 1. High‑Level Sequence

### 1.1 Participants

- **Mi (A)** – Mobile App (initiator)
- **S (B)** – Cloud relay / orchestrator
- **Pi (C)** – Active Card hardware wallet

Threshold `t` and party count `n` are user‑selectable (default 2-of-3).

### 1.2 Sequence Diagram (Transport-Aware)

```mermaid
sequenceDiagram
    participant Mi as Mobile App (A)
    participant S as Cloud Relay (B)
    participant Pi as Active Card (C)

    %% 0 – Backup request
    Mi->>S: BK_BACKUP_REQUEST(walletID, t, n)
    S-->>Mi: BK_SESSION_INFO(sessionID)
    Mi-->>Pi: sessionID / QR

    %% 1 – Join phase
    Pi->>Mi: BK_JOIN_REQUEST(sessionID, idx=3)
    Mi->>S: BK_JOIN_REQUEST(sessionID, idx=3)
    S-->>Mi: BK_JOIN_REQUEST_ACK
    Mi-->>Pi: BK_JOIN_REQUEST_ACK

    %% 2 – Session start
    Mi->>S: BK_SESSION_START
    S-->>Mi: BK_SESSION_START_ACK
    Mi-->>Pi: BK_SESSION_START

    %% 3 – Shard generation & fan-out
    Note over Pi: Generate VSS polynomial & n shards
    Pi-->>Mi: mobileShard (ENC)
    Pi-->>Mi: shards[others] (ENC-for-cloud)

    Mi->>S: shards[others]
    S-->>S: store shard_B (HSM)
    S-->>Mi: shard_C → Pi (if other HW wallets)

    Mi-->>Mi: store shard_A (secure enclave)

    %% 4 – Done
    Note over Mi,S,Pi: All parties persist their encrypted backup shards
```

---

## 2. Control Message Definitions

```proto
message BKBackupRequest {
  string  wallet_id       = 1;
  uint32  threshold_t     = 2;
  uint32  num_parties_n   = 3;
}

message BKSessionInfo {
  bytes   session_id      = 1;
  uint32  assigned_index  = 2;
  repeated uint32 party_map = 3;
}

message BKJoinRequest {
  bytes   session_id      = 1;
  uint32  party_index     = 2;
}
```

ACK messages are empty wrappers reusing the same IDs. Error handling follows `*_ERROR` (ID 1210) with stage codes for Create / Join / Start.

---

## 3. Persisting Shards

| Party | Storage                        | Encryption Key           |
|--------|---------------------------------|---------------------------|
| Pi     | On‑card flash                  | Device master key (burned) |
| Mi     | Secure enclave + cloud backup | SEK_mobile               |
| S      | HSM‑backed DB                  | SEK_cloud                |

Cloud also mirrors each shard to its intended recipient via the relay.

---

## 4. Recovery Path (t‑of‑n)

Recovery reuses the shared MPC recovery workflow. Once Mi collects any `t` decrypted shards, it sends `BK_RECOVER_REQUEST` to Pi, which reconstructs its share and resumes MPC.

---

## 5. Security Notes

- **Minimal trust**: All shards encrypted at source; no plaintext leaves device.
- **Replay-safe**: Session IDs are UUID‑v4, cached and validated by the cloud.
- **Threshold control**: `t` and `n` are independent from the live MPC threshold.
