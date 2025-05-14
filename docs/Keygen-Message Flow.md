# Message Flow

# **Message Flow**

## Round 1: Commitment Distribution

Each party Pi:

1. Generates a random value ui (their "partial" key share)
2. Creates VSS shares for all parties
3. Commits to their VSS polynomial points
4. Broadcasts this commitment to all other parties (KGRound1Message)

##   

## Round 2: Share Distribution and Proof

Each party Pi:

1. Sends their VSS shares privately to each other party (KGRound2Message1)
2. Creates a Schnorr proof of knowledge of their secret
3. Broadcasts the de-commitment values and Schnorr proof to all parties (KGRound2Message2)

##   

## Round 3: Key Computation (No Messages Exchanged)

Each party Pi:

1. Verifies the shares and proofs they received from other parties
2. Computes their private key share by combining all received shares
3. Computes the public key and public key shares for all parties
4. Saves the final key material

![](https://t9018252776.p.clickup-attachments.com/t9018252776/2b18638d-727a-48cb-93da-cd2d0883b1b7/image.png)