# Key Import & Export

## Overview

The key import/export functionality in the TSS library is primarily designed for importing/exporting mnemonic phrases from the Regular Key MPC group.

This document outlines the details of the implementation and flow of the key export process. Note that key import follows the same flow as the key generation with a mnemonic protocol.

  

## Implementation

The key export is implemented through a simple method in the `LocalPartySaveData`structure:

```go
func (l *LocalPartySaveData) GetXi(iden KeyIdentity) (xi *big.Int, err error) {
    if share, ok := l.Shares[iden]; ok {
        return share.Xi, nil
    }
    return nil, fmt.Errorf("key identity: %s is not found", iden)
}
```

and `Reconstruction`method:

```plain
func (shares Shares) ReConstruct(ec elliptic.Curve) (secret *big.Int, err error) {
	if shares != nil && shares[0].Threshold > len(shares) {
		return nil, ErrNumSharesBelowThreshold
	}
	modN := common.ModInt(ec.Params().N)

	// x coords
	xs := make([]*big.Int, 0)
	for _, share := range shares {
		xs = append(xs, share.ID)
	}

	secret = zero
	for i, share := range shares {
		times := one
		for j := 0; j < len(xs); j++ {
			if j == i {
				continue
			}
			sub := modN.Sub(xs[j], share.ID)
			subInv := modN.ModInverse(sub)
			div := modN.Mul(xs[j], subInv)
			times = modN.Mul(times, div)
		}

		fTimes := modN.Mul(share.Share, times)
		secret = modN.Add(secret, fTimes)
	}

	return secret, nil
}
```

##   

## Process Flow

1. **Secret holder Initiation**
    *   The secret holder initiates the export process
    *   Sends requests to each participant to retrieve their VSS share (Xi + ShareID)
2. **Share Retrieval**
    *   Each participant provides their share through the `GetXi` method
    *   Shares are verified for authenticity
3. **Secret Reconstruction**
    *   The Secret holder reconstructs the secret from the retrieved VSS shares
    *   The reconstruction process ensures the integrity of the exported key by verifying that the local public key is identical to the new public key derived from the secret above.

## ![](https://t9018252776.p.clickup-attachments.com/t9018252776/35745a66-4f4f-46db-b552-55a4d91eccd7/image.png)

## Message Samples

ECDSA

```json
"ShareID": 59857031556462284717113645237935722663924232558699039874171440941840562677324,
"Xi": 76948082823091852504553670832408291290543297863564249603348941514219073751559,
"ECType": "ecdsa"
```

EDDSA

```json
"ShareID": 59857031556462284717113645237935722663924232558699039874171440941840562677323,
"Xi": 1233025138004589643391610487146375912865215893327288952688723470489178137639,
"ECType": "eddsa"
```