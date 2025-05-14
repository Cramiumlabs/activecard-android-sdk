# t-of-n MPC Overview

# Prerequisite

*   Each party can only join one active session at a time
    *   If a party joins a session, they cannot initiate or join other sessions
*   **Party A** is designated as the **initializer** and holds the mnemonic phrase at the first time
    *   All members can be an initializer
    *   Only initializers can start a session
*   **Party C** is another Party member as **Mobile**, **Browser Extension**, or etc.
*   User can make multiple MPC groups
    *   Example scenarios for User A:
        *   Wallet 1: Members - A, B, C
        *   Wallet 2: Members - A, B, D
        *   Wallet 3: Members - A, B
*   Each process checks the HW device is paired
*   Trusted server model assumption: All parties send messages to Cloud server which is as relay server. All parties use AES encryption with ECDH key exchange and encrypt messages using server's public key. Server decrypts messages and send them with encryption using final recipient's public key.

# What members are needed for

| **All members** | Adding member | Removing member | Creating new wallet |
| ---| ---| ---| --- |
| Only **threshold** members | Signing | Exporting |  |

# Discussion

*   **Special master role :** Select a master and only master can initialize requesting process or exporting a secret
*   **Offline process for 2-of-2(3) with HW** : If threshold is 2 and user have a HW device, user doesn't need to access our infrastructure (but to send tx to blockchain, user still need to access internet). If we can skip user's authentication, user can send tx without our infrastructure.
    *   this can be solution about key export when our infra is down

# Adding new member

### Party A side

![](https://t9018252776.p.clickup-attachments.com/t9018252776/13d090d1-81ba-42c9-a196-af41fa3557d4/image.png)

  

1. **Party A** : Fetch shards from Cloud and request transaction
    1. If the user has already removed the server's shard then skip fetching server shard.
2. **Party A** : Send MPC key resharing request
    1. Send MPC key resharing request with Cloud's shard and session ID
    2. Send session ID to other parties
        1. Session ID is from Auth server
            1. Auth server generates session ID as uuidv4
            2. Auth server saves session ID
            3. Auth server sends session ID to user device
        2. Send session ID to all parties includes a new party
            1. Show session ID as QR code
            2. share button or copy session ID
            3. Send QR code or session ID to user securely
    3. User waits all parties will be joined
        1. Show parties list that are currently joined
3. **(If HW device exist) Mobile SDK :** Send MPC key resharing request to HW device with session ID
4. **(If HW device exist) HW device** : HW device sends join session request to Mobile SDK
    1. Mobile SDK forwards HW device's request to cloud server
5. **Server** : Gets Cloud SEK and decrypt Cloud's shard
6. **All** : Run rounds of Key sharing
    1. rounds will start after session is started
7. **All** : Saving new shards
    1. Saving server's shard on secure Device after encrypting the shard
    2. Saving Party's shard on each Device after encrypting the shard
    3. **(If HW device exist)** Saving HW's shard on HW device after encrypting the shard

* * *

### Party C side (except HW device)

![](https://t9018252776.p.clickup-attachments.com/t9018252776/78aae121-a96e-4328-9090-84a36444bb3e/image.png)

1. **Party A** : Party A sends session ID to new parties
2. **Party C :** Request join party with session ID
3. **Mobile SDK (Party B)** : Join the session with session ID
    1. New party is not registered in our app
        1. Open an app and press join a group button
            1. There is a join a group button along with the sign-in button at the beginning, and when pressed, **the adding process proceeds without creating a wallet after registering with the auth server for the first time.**
            2. After registering, show a join page with scan QR code button or input session ID button
                1. If new party scans QR code or input session ID, then join the the session
    2. New party is already registered in our app (optional)
        1. Press wallet setting button
        2. Press join wallet button
            1. join button is located below the add button
        3. Show a join page with scan QR code button or input session ID button
            1. If new party scans QR code or input session ID, then join the the session
4. **All** : Run key resharing protocol
    1. rounds will start after session is started
5. **All** : Saving Party's shard on each Device after encrypting the shard

* * *

# Removing a party

![](https://t9018252776.p.clickup-attachments.com/t9018252776/9ef26400-191c-4882-9b1d-70ab3252a7c7/image.png)

  

1. **Party A** : Request remove party with a user ID to be removed and session ID
2. **Mobile SDK** : Join the session with a user ID to be removed and session ID
3. **Party A** : Send session ID to other parties without the party to be removed
    1. All parties except the party to be removed must join the session
4. **(If HW device exist) Mobile SDK** : Send MPC key resharing request with session ID to HW device
5. **(If HW device exist) HW device** : HW device sends join session request to Mobile SDK
    1. Mobile SDK forwards HW device's request to cloud server
6. **All** : Run key resharing protocol
    1. all members shards will be changed
7. **All** : Saving new shards (only left members)
    1. Saving Party's shard on each Device after encrypting the shard
        1. If party's are only Mobile app and server, send encrypted shard to Mobile SDK and Mobile SDK saves it in the user's cloud.
    2. Saving Party's shard on each Device after encrypting the shard
    3. **(If HW device exist)** Saving HW's shard on HW device after encrypting the shard

* * *

# Creating a new wallet - Full MPC

![](https://t9018252776.p.clickup-attachments.com/t9018252776/43fdb55a-edb3-4c75-8615-1c6db8b57d4b/image.png)

1. **Party A/C** : User initiates creating an wallet
2. **Party A** : Send create MPCAA wallet request
    1. New wallet with new MPC group
3. **Mobile SDK** : Create a session
    1. Sends create Session request
    2. **Party A** : Send session ID to all parties
    3. Get participant list
    4. show participant list
    5. **Party C** : Send join Session request
    6. **Party A** : Wait for other parties to join
4. **(If HW device exist) Mobile SDK** : Send create MPCAA wallet request with session ID to HW device
5. **(If HW device exist) HW device** : Send join Session request to Mobile SDK
    1. Mobile SDK forwards HW device's request to cloud server
6. **Party A** : Session start
    1. Send Session start request
    2. **Mobile SDK** : Send session start
7. **All** : Run round of key generation for secret
    1. Run round of key generation for secret
    2. Froward HW MPC process messages
8. **All** : Saving Party's shard on each Device after encrypting the shard
    1. Saving server's shard on secure Device after encrypting the shard
    2. Saving Party's shard on each Device after encrypting the shard
    3. **(If HW device exist)** Saving HW's shard on HW device after encrypting the shard
9. **Party A** : Generate a Pre-Calculated AA Master Address from Public Key.

  

* * *

# Creating a new wallet - Mnemonic compatible (key import)

![](https://t9018252776.p.clickup-attachments.com/t9018252776/7d62d3d6-f441-4559-8375-531c1a71e6c2/image.png)

1. **Party A** : User initiate the wallet ( same as 2-of-2 wallet creation)
    1. **Key import process**
        1. **Mnemonic phrase import** : Mobile app derives the child public/secret key pairs from the Mnemonic Phrase and run left process.
        2. **Secret key import** : Mobile app skips generating mnemonic phrase and deriving key pairs, and run left process
2. **Party A** : Send create MPCAA wallet request
    1. New wallet with new MPC group
        1. User creates 2-of-2 wallet
3. **Mobile SDK** : Create a session
    1. Sends create Session request
    2. **Party A** : Send session ID to all parties
    3. Get participant list
    4. show participant list
    5. **Party C** : Send join Session request
    6. **Party A** : Wait for other parties to join
4. **(If HW device exist) Mobile SDK** : Send create MPCAA wallet request with session ID to HW device
5. **(If HW device exist) HW device** : Send join Session request to Mobile SDK
    1. Mobile SDK forwards HW device's request to cloud server
6. **Party A** : Session start
    1. Send Session start request
    2. **Mobile SDK** : Send session start
7. **All** : Run round of key generation for mnemonic phrase and secret
8. **All** : Saving Party's shard on each Device after encrypting the shard

  

* * *

# The party selection flow

*   The signature and output process only requires a threshold number of parties unlike wallet creation and adding/removing members. Therefore, before this process, it is necessary to choose which device to use.

![](https://t9018252776.p.clickup-attachments.com/t9018252776/c11fe801-a6d3-44da-a8f8-955d8fcbb555/image.png)

1. **Party A** : Send party selection request
2. **Mobile SDK** : Send party selection request
3. Get party list
4. **Mobile SDK** : Show party lists
5. **Party A** : Select parties who will join the session
    1. Party A selects threshold number parties
        1. If n=3, t=2, then Party A should select 2 members

  

* * *

# The transaction signing flow

![](https://t9018252776.p.clickup-attachments.com/t9018252776/d05b490d-7f47-4aaf-a3c6-0234028fcb77/image.png)

1. **Party A** : run the party selection process
2. **Party A** : Sends signing request
3. **Mobile SDK** : Create a session and send singing request with tx data
    1. Sends create Session request
    2. **Party A** : Send session ID to selected parties (excluding HW)
    3. Get participant list from Cloud server
    4. show participant list
    5. **Other participants** : Send join Session request (excluding HW)
    6. **Party A** : Wait for other parties to join
4. **(If HW device exist) Mobile SDK** : Send signing request with session ID to HW device
5. **(If HW device exist) HW device** : Send join Session request to Mobile SDK
6. **Party A** : Send Session start request
    1. **Mobile SDK** : Send session start
7. **All** : Run round of signing for tx data
8. **Mobile SDK :** Send the signed transaction to blockchain

* * *

# The export flow

![](https://t9018252776.p.clickup-attachments.com/t9018252776/98f06954-b7ff-41bd-af85-9b6983d71fa7/image.png)

1. **Party A** : starts the party selection process
2. **Party A** : Send export request
3. **Mobile SDK** : Create a session and send export request
    1. Sends create Session request
    2. **Party A** : Send session ID to selected parties
    3. Get participant list from Cloud server
    4. show participant list
    5. **Other participants** : Send join Session request
    6. **Party A** : Wait for other parties to join
4. **(If HW device exist) Mobile SDK** : Send export request with session ID to HW device
5. **(If HW device exist) HW device** : Send join Session request to Mobile SDK
6. **Party A** : Send Session start request
    1. **Mobile SDK** : Send session start
7. **All** : Run round of export
8. **Party A :** show exported Secret

* * *

# Backup ( t-of-n )

> This workflow is more general flow than below Mobile-Cloud version. The user can set the threshold and the total number of members independently of the existing t and n. If user sets 2-of-2 and selects devices as Mobile and Cloud, then it is same as below except session creation.

![](https://t9018252776.p.clickup-attachments.com/t9018252776/2d9d5728-7dca-41c5-a087-4c7726aa1ead/image.png)

1. **Party A** : Run party selection process
2. **Party A** : Send Daric backup request
3. **Mobile SDK** : Send Daric backup request with session ID
    1. Send session ID
    2. **Party A** : Send session ID to selected parties
    3. Get participant list from Cloud server
    4. show participant list
    5. **Other participants** : Send join Session request
    6. **Party A** : Wait for other parties to join
4. **Mobile SDK** : If all parties are joined, Send Daric backup request with session ID
5. **Daric** : Generate VSS polynomial with Daric's shard and generate shards
    1. User can set threshold of Daric's backup
    2. If user want to recover Daric's backup, threshold number parties should join
6. **Daric** : Send mobile shard and all other shards. Mobile shard is encrypted with mobile's key and other shards are encrypted with cloud's key
7. **Mobile SDK** : Send all other shards
8. **Cloud** : Send the shard to each participants
9. **All** : Saving Daric backup shards
    1. **Cloud** : Encrypt backup shard with SEK and Store it in secure database
    2. **Party A** : Encrypt backup shard with SEK and Store it in user cloud
    3. **Party C** : Encrypt backup shard with SEK and Store it in user cloud

* * *

# Recovery (t-of-n)

![](https://t9018252776.p.clickup-attachments.com/t9018252776/1be58abc-e08d-433e-ae2a-60549e2c6f7d/image.png)

1. **Party A** : Run party selection process
2. **Party A** : Send Daric backup request
3. **Mobile SDK** : Fetch Daric backup data
4. **Mobile SDK** : Send Daric backup request with session ID
    1. Send session ID
    2. **Party A** : Send session ID to selected parties
    3. Get participant list from Cloud server
    4. Show participant list
    5. **Other participants** : Send join Session request
    6. **Party A** : Wait for other parties to join
5. **Party C :** Decrypt Daric backup data with SEK and send it to cloud
6. **Cloud**: Send all decrypted shards to Mobile SDK
7. **Mobile SDK** : Send Daric recovery request with all shards
8. **Daric** : Reconstruct Daric's shard

* * *

# Backup (with Mobile-Cloud)

> This workflow is same as the PRD. This flow is even if the group is t-of-n, Daric's backup is stored in only mobile and cloud. There is no interaction for backup flow, so the flow doesn't create session.

![](https://t9018252776.p.clickup-attachments.com/t9018252776/43346fbb-3e81-4c74-8c04-4ce86f7e6a7d/image.png)

1. **Party A** : Send Daric backup request
2. **Mobile SDK** : Send Daric backup request with session ID
3. **Daric** : Generate VSS polynomial with Daric's shard and generate two shards
4. **Daric** : Send mobile shard and cloud shard. Mobile shard is encrypted with mobile's key and cloud shard is encrypted with cloud's key
5. **Mobile SDK** : Send the cloud shard
6. **All** : Saving Daric backup shards
    1. **Cloud** : Encrypt backup shard with SEK and Store it in secure database
    2. **Party A** : Encrypt backup shard with SEK and Store it in user cloud

* * *

# Recovery (with Mobile-Cloud)

![](https://t9018252776.p.clickup-attachments.com/t9018252776/760903b7-4ea2-494e-8672-dba0a468ed64/image.png)

1. **Party A** : Send Daric recovery request to Mobile SDK
2. **Mobile SDK** : Fetch Daric backup data from the Party A Cloud Storage
3. **Mobile SDK** : Send Daric recovery request to the Cloud Server
4. **Cloud** : Send Daric backup data to SDK
5. **Mobile SDK** : Send Daric recovery request with mobile backup data and cloud backup data to Daric
6. **Daric** : Reconstruct Daric shard using mobile and cloud backup data

* * *

  

### Implementation Challenges (optional)

*       *   Allow all parties to create a new wallet(mnemonic)
        *   Or allow only master user to create a new wallet
*       *   Creating a new shard without changing original shards
        *   Note: Web3Auth supports multiple backup key generation
        *   User can choose between:
            *   Modifying threshold : all members shards will be changed
            *   Adding new member (with optional server removal) : all members shards will be changed
            *   Adding member only : only new party’s shard will be created
        *   tss-lib, core-lib implementation needed
*       *   If a user has more shards than the threshold, user can delete the server's shards
        *   example : If 2-of-2 wallets and user add a new party, then user can delete server's shard and keep user's wallet 2-of-2.