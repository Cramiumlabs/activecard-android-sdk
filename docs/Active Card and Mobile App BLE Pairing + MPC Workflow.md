# Active Card and Mobile App BLE Pairing + MPC Workflow

This document outlines the enhanced BLE pairing protocol using QR code + Challenge-Response, identity key exchange, ECDH key exchange, and secure session setup for MPC initialization between the **Mobile Device** (User + App) and the **Active Card**.

* * *

  

## 1\. Secure BLE Pairing with QR Code + Challenge-Response (Identity Key Exchange)

### 1.1 Sequence Diagram

![](https://t9018252776.p.clickup-attachments.com/t9018252776/b60ebbc5-27f3-4e29-a715-c16a6cd1ba16/image.png)

### 1.2 QR code data format

```plain
{
  "identityPublicKey": "BASE64_ENCODED_PUBKEY",
  "deviceId": "DEVICE-UUID-1234",
  "deviceName": "AC-Bob-Key",
  "ownerUser": "bob@example.com",
  "firmwareVersion": "1.0.3",
  "timestamp": 1715148123
}
```

### 1.3 Commands & Messages

#### CMD\_GET\_NONCE\_REQ

*   Request for random nonce from firmware
*   **Command Only**, no payload

#### CMD\_GET\_NONCE\_RSP

```plain
message NonceResponse {
  bytes nonce = 1;
}
```

#### CMD\_SIGNED\_NONCE\_REQ

```plain
message SignedNonce {
  bytes signature = 1; // ECDSA Signature of the nonce using firmware's private key
}
```

#### CMD\_VERIFY\_SIGNATURE\_RSP

```plain
message SignatureVerificationResult {
  bool valid = 1;
  string reason = 2; // e.g. "invalid signature"
}
```

#### CMD\_SEND\_IDENTITY\_PUBKEY

```plain
message IdentityPublicKey {
  bytes pubkey = 1;
  string source = 2; // "mobile"
}
```

* * *

## 2\. ECDH Key Exchange and Shared Secret Derivation

### 2.1 Sequence Diagram: Shared Secret Establishment

![](https://t9018252776.p.clickup-attachments.com/t9018252776/5c4eba1b-0a63-47a4-ae23-47f6f2c2dfb5/image.png)

### 2.2 Commands & Messages

#### CMD\_SEND\_ECDH\_PUBKEY

```plain
message ECDHPublicKey {
  bytes pubkey = 1;
  string source = 2; // "mobile" or "firmware"
}
```

#### CMD\_ACK\_ECDH\_EXCHANGE

```plain
message ECDHExchangeAck {
  bool success = 1;
  string reason = 2;
}
```

* * *

  

## 3\. Ownership Association

### 3.1 Sequence Diagram: Pairing Confirmation and User Binding

![](https://t9018252776.p.clickup-attachments.com/t9018252776/1ed2a08d-bb24-4451-9e61-bc0636a48ead/image.png)

### 3.2 Commands & Messages

#### CMD\_SEND\_USER\_IDENTITY

```plain
message UserIdentity {
  bytes encrypted_user_id = 1;
}
```

#### CMD\_PAIRING\_CONFIRMATION

```plain
message PairingConfirmation {
  bool confirmed = 1;
}
```

* * *

  

## 4\. Session Management

### 4.1 Reconnect

![](https://t9018252776.p.clickup-attachments.com/t9018252776/387cd74e-ec22-4210-96b7-a934636d9b46/image.png)

### 4.2 Forget Device

![](https://t9018252776.p.clickup-attachments.com/t9018252776/bc2c5870-8535-4171-a4f3-5d12d52f757d/image.png)

#### CMD\_FORGET\_DEVICE

*   No payload; command triggers erasure of pairing data

#### CMD\_FORGET\_ACK

```plain
message ForgetAck {
  bool erased = 1;
}
```

* * *

  

## 5\. Notes

*   All public keys use `secp256r1`
*   Signatures are done with `ecdsa.SignASN1`
*   Shared secret is used with AES-GCM
*   QR Code must include firmware's static identity public key for verification
*   Identity key is generated from go-sdk by method `generateIdentityKeypair`