# Message Sample

### Round 1 Message

#### KGRound1Message (Broadcast by Each Participant)

This message contains the commitment to the participant's VSS polynomial.

```plain
// Message structure
type KGRound1Message struct {
    KeyIdentity []byte            // Identifies which key this message is for
    Commitment  cmt.HashCommitment // Commitment to the VSS polynomial
}
```

**Example Message Content (Hex-encoded):**

```json
{
  "key_identity": "key_identifier_1",
  "commitment": "c011e119e06ebc0e4260ca49bcde09d8c8f0a5cf3431724cbf714f0e5431bc23"
}
```

###   

### Round 2 Messages

####   

#### KGRound2Message1 (Sent Privately to Each Participant)

This message contains an individual VSS share for a participant.

```plain
// Message structure
type KGRound2Message1 struct {
    KeyIdentity []byte     // Identifies which key this message is for
    Share       *vss.Share // The participant's VSS share
}
```

**Example Message Content (Hex-encoded):**

```json
{
  "key_identity": "key_identifier_1",
  "share": "66b6f26fcceaae7d2991b2e1cc8663bae2df58e3b15c317c5499b20128b30d9b22"
}
```

####   

#### KGRound2Message2 (Broadcast by Each Participant)

This message contains the decommitment values and Schnorr proof.

```plain
// Message structure
type KGRound2Message2 struct {
    KeyIdentity  []byte                // Identifies which key this message is for
    DeCommitment cmt.HashDeCommitment  // Decommitment values
    ProofAlphaX  []byte                // X coordinate of Schnorr proof alpha point
    ProofAlphaY  []byte                // Y coordinate of Schnorr proof alpha point
    ProofT       []byte                // Schnorr proof t value
}
```

**Example Message Content (Hex-encoded):**

```prolog
{
  "key_identity": "key_identifier_1",
  "decommitment": [
    "14149452188cd89423ef44a414d8db84c1a5605a658d9bd44462f78b9a55eccf",
    "59ba2020d6935a586d6d7ccd22ccc5377734c8b26d1a74c7b48d1eb1798bbfedad7",
    "5e808f31e055992fdd76fc34d27da4ba7bace5272b8a323a948857dbeb6e5306",
    "5607bcb3219219bb7fbfec0a09eb7a79a56ad7913d58e447d911d92fcc26a348",
    "8c64b265ff480c39b1b9bef3342043c85488b22627b6779ace9b24b08a9a277e13",
    "0417fbbe2461c80e312cc6ff4b21567252036e4c4ee9666d1bf27be0a9523b528",
    "f11d8732b579b78b967e7b7ee3405920071d0caeb77c68a51671b95cf3e"
  ],
  "proof_alpha_x": "49512a1fb2d9c9746098049741c14ee0d5c181e60f66c3b7fb91474688b242723838",
  "proof_alpha_y": "4719d7248dd4f0fc4660577d710ebc8c0a720f15c7c84056e9260277ff99278d",
  "proof_t": "b7b8e527657bc83958fb4823f89c382eca85d95926dd5f15b7bfcf91c940f20a"
}
```

###   

### Message Serialization Example

Messages are serialized using Protocol Buffers. Here's an example of the serialization process:

```plain
// Creating a KGRound1Message
meta := tss.MessageRouting{
    From:        partyID,
    IsBroadcast: true,
}
content := &KGRound1Message{
    KeyIdentity: []byte("example_key"),
    Commitment:  commitment,
}

// Create message wrapper and parsed message
msg := tss.NewMessageWrapper(meta, content)
parsedMsg := tss.NewMessage(meta, content, msg)

// Serialize to wire format
wireBytes := parsedMsg.WireBytes()

// Example of serialized bytes (hex-encoded)
// 0a1b0a0464656131100118012202312a0c0a0a0a066b6579312d3112020801120c0a0a0a066b6579312d32...
```

###   

### Message Deserialization Example

```haskell
// Deserialize from wire format
parsedMsg, err := tss.ParseWireMessage(wireBytes, nil, true)
if err != nil {
    // Handle error
}

// Access message content
switch content := parsedMsg.Content().(type) {
case *KGRound1Message:
    // Process KGRound1Message
    keyIdentity := string(content.GetKeyIdentity())
    commitment := content.GetCommitment()
    
case *KGRound2Message1:
    // Process KGRound2Message1
    keyIdentity := string(content.GetKeyIdentity())
    share := content.GetShare()
    
case *KGRound2Message2:
    // Process KGRound2Message2
    keyIdentity := string(content.GetKeyIdentity())
    deCommitment := content.GetDeCommitment()
    // Extract Schnorr proof components
    proofAlphaX := content.GetProofAlphaX()
    proofAlphaY := content.GetProofAlphaY()
    proofT := content.GetProofT()
}
```