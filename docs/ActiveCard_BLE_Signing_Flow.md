# Active Card and Mobile App BLE Signing Flow (ECDSA 2‑of‑3)

This revision aligns the on‑device workflow with the **three‑phase / nine‑round ECDSA signature protocol** shown in the canonical overview diagrams (Setup → Computation → Signature). The tables below map each protocol round to a concrete `ActiveCardEvent` ID and clarify how messages travel across the **BLE ⇆ gRPC proxy**.

---

## 0  Transport Links & Security

| Link | Transport | Security |
|------|-----------|----------|
| **Pi (C) ⇄ Mi (A)** | BLE GATT Write‑With‑Response | AES‑GCM in `TransportMessageWrapper` |
| **Mi (A) ⇄ S (B)** | gRPC bidirectional stream | TLS‑1.3 + message‑level AES‑GCM |
| **Pi (C) ⇄ S (B)** | *Via Mi* (`Pi → Mi → S`, `S → Mi → Pi`) | Double encryption (BLE + gRPC) |

---

## 1  Event ID Allocation

```kotlin
enum class ActiveCardEvent(val id: Int) {
    /* pairing 1‑11, key‑gen 1001‑1011, wallet 1100‑1111 */

    // Control plane
    SGN_CREATE_REQUEST (1200),
    SGN_SESSION_INFO   (1201),
    SGN_JOIN_REQUEST   (1202),
    SGN_SESSION_START  (1203),

    // === Setup Phase ===
    SGN_R1_PART1 (1204),
    SGN_R1_PART2 (1205),
    SGN_R2       (1206),

    // === Computation Phase ===
    SGN_R3 (1207),
    SGN_R4 (1208),
    SGN_R5 (1209),
    SGN_R6 (1210),

    // === Signature Phase ===
    SGN_R7 (1211),
    SGN_R8 (1212),
    SGN_R9 (1213),

    SGN_FINAL_SIG (1214),
    SGN_ERROR     (1215)
}
```

---

## 2 Sequence Overview (high‑level)

A concise diagram shows only the control‑plane handshake and the three collapsed phases. The detailed round diagrams remain unchanged below.

```mermaid
sequenceDiagram
    participant Mi as Mobile App (A)
    participant S  as Cloud Relay (B)
    participant Pi as Active Card (C)

    %% 0. Create wallet request
    Mi->>S: WC_CREATE_REQUEST(walletSpec)
    S-->>Mi: WC_SESSION_INFO(sessionID)
    Mi-->>Pi: sessionID / QR

    %% 1. Join phase
    Pi->>Mi: WC_JOIN_REQUEST(sessionID, idx=3)
    Mi->>S: WC_JOIN_REQUEST(sessionID, idx=3)
    S-->>Mi: WC_JOIN_REQUEST_ACK
    Mi-->>Pi: WC_JOIN_REQUEST_ACK

    %% 2. Session start
    Mi->>S: WC_SESSION_START
    S-->>Mi: WC_SESSION_START_ACK
    Mi-->>Pi: WC_SESSION_START

    %% Phase blocks (collapsed)
    rect rgb(224,255,224)
        Note over Mi,Pi: **Setup Phase** (Rounds 1–2)
    end
    rect rgb(224,240,255)
        Note over Mi,Pi: **Computation Phase** (Rounds 3–6)
    end
    rect rgb(255,240,224)
        Note over Mi,Pi: **Signature Phase** (Rounds 7–9) + FINAL_SIG
    end

```

## 3  Sequence Diagrams by Phase

### 3.1 Setup Phase (Rounds 1‑2)

```mermaid
sequenceDiagram
    %% Participants
    participant Mi as Mobile App (A)
    participant S  as Cloud Relay (B)
    participant Pi as Active Card (C)

    %% R1‑P1 (Generate k_i, γ_i) – P2P commitments
    Mi->>S: SGN_R1_PART1 (A→B)  <<gRPC>>
    Mi->>Pi: SGN_R1_PART1 (A→C)  <<BLE>>
    S-->>Mi: SGN_R1_PART1 (B→A)  <<gRPC>>
    S-->>Mi: SGN_R1_PART1 (B→C)  <<gRPC>> 
Mi->>Pi:   …forward…           <<BLE>>
    Pi-->>Mi: SGN_R1_PART1 (C→A) <<BLE>>
    Pi-->>Mi: SGN_R1_PART1 (C→B) <<BLE>> 
Mi->>S:   …forward…            <<gRPC>>

    %% R1‑P2 – Broadcast commitments
    Mi->>S: SGN_R1_PART2(commit_A) <<gRPC>>
    Mi->>Pi: SGN_R1_PART2(commit_A) <<BLE>>
    S-->>Mi: SGN_R1_PART2(commit_B) <<gRPC>>
    Mi->>Pi: SGN_R1_PART2(commit_B) <<BLE>>
    Pi-->>Mi: SGN_R1_PART2(commit_C) <<BLE>>
    Mi->>S:  SGN_R1_PART2(commit_C) <<gRPC>>

    %% R2 – MTA Exchange P2P (green)
    Mi->>S: SGN_R2(A→B)
    Mi->>Pi: SGN_R2(A→C)
    S-->>Mi: SGN_R2(B→A)
    S-->>Mi: SGN_R2(B→C) 
Mi->>Pi: …forward…
    Pi-->>Mi: SGN_R2(C→A)
    Pi-->>Mi: SGN_R2(C→B) 
Mi->>S: …forward…
```

### 3.2 Computation Phase (Rounds 3‑6)

```mermaid
sequenceDiagram
    participant Mi as Mobile App (A)
    participant S  as Cloud Relay (B)
    participant Pi as Active Card (C)

    %% R3 proofs
    Mi->>S: R3(proof_A)
    Mi->>Pi: R3(proof_A)
    S-->>Mi: R3(proof_B)
    Mi->>Pi: R3(proof_B)
    Pi-->>Mi: R3(proof_C)
    Mi->>S: R3(proof_C)

    %% R4 reveal gamma
    Mi->>S: R4(reveal_A)
    Mi->>Pi: R4(reveal_A)
    S-->>Mi: R4(reveal_B)
    Mi->>Pi: R4(reveal_B)
    Pi-->>Mi: R4(reveal_C)
    Mi->>S: R4(reveal_C)

    %% R5/R6 same pattern
    Note over Mi,S: R5 & R6 share same broadcast pattern
```

### 3.3 Signature Phase (Rounds 7‑9)

```mermaid
sequenceDiagram
    participant Mi as Mobile App (A)
    participant S  as Cloud Relay (B)
    participant Pi as Active Card (C)

    %% R7 sig shares
    Mi->>S: R7(sig_A)
    Mi->>Pi: R7(sig_A)
    S-->>Mi: R7(sig_B)
    Mi->>Pi: R7(sig_B)
    Pi-->>Mi: R7(sig_C)
    Mi->>S: R7(sig_C)

    %% R8 aggregate r
    Mi->>S: R8(r_A)
    Mi->>Pi: R8(r_A)
    S-->>Mi: R8(r_B)
    Mi->>Pi: R8(r_B)
    Pi-->>Mi: R8(r_C)
    Mi->>S: R8(r_C)

    %% R9 s shares
    Mi->>S: R9(s_A)
    Mi->>Pi: R9(s_A)
    S-->>Mi: R9(s_B)
    Mi->>Pi: R9(s_B)
    Pi-->>Mi: R9(s_C)
    Mi->>S: R9(s_C)

    %% Aggregate final
    Mi-->>Mi: σ = aggregate(r,s)
    Mi->>S: FINAL_SIG(σ)
    Mi-->>Pi: FINAL_SIG(σ)
```

---

## 4  Round‑to‑Event Mapping

| Phase | Round | Event ID |
|-------|-------|----------|
| Setup | R1‑P1 | 1204 |
|  | R1‑P2 | 1205 |
|  | R2 | 1206 |
| Computation | R3 | 1207 |
|  | R4 | 1208 |
|  | R5 | 1209 |
|  | R6 | 1210 |
| Signature | R7 | 1211 |
|  | R8 | 1212 |
|  | R9 | 1213 |

---

## 5  Error Codes

| Code | Phase | Meaning |
|------|-------|---------|
| 1 | Create | Malformed tx |
| 2 | Join/Setup | Party index error |
| 3 | Comp | Share invalid |
| 4 | Sig | Partial σ fail |

`SGN_ERROR {code, description}`.
