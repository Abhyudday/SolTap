# Arcium Private Payments Program

## Overview

This Solana program uses **Arcium's Multi-Party Computation (MPC)** network to process payments with complete privacy. Merchant transaction amounts, identities, and revenue totals remain fully encrypted throughout the payment lifecycle.

## Privacy Features

✅ **Encrypted Payment Amounts** - Transaction values never exposed to validators or blockchain observers  
✅ **Hidden Merchant Identity** - Merchant addresses encrypted during processing  
✅ **Private Revenue Tracking** - Total merchant revenue computed within MPC  
✅ **Confidential Order Processing** - Payment details only decryptable by authorized parties  

## How It Works

1. **Client Encrypts Data**: Payment amount and merchant ID encrypted before submission
2. **MPC Processing**: Arcium network processes encrypted data without decryption
3. **Private Validation**: Payment validation happens within MPC (min amounts, duplicates, etc.)
4. **Selective Decryption**: Only merchant can decrypt their revenue data

## Integration with Android App

The Android app in this repository uses this program to:
- Encrypt payment requests before BLE/NFC transmission
- Submit encrypted transactions to Solana
- Allow merchants to view encrypted revenue (decrypted only on their device)

## Building

```bash
# Install Arcium CLI (wraps Anchor)
cargo install --git https://github.com/arcium-labs/arcium arcium-cli

# Build the program
arcium build

# Run tests
arcium test
```

## Deployment

```bash
# Deploy to devnet
arcium deploy --network devnet

# Update program ID in lib.rs and Android app
```

## For Hackathon Judges

This program demonstrates:
1. **Privacy-First DeFi**: Real-world use case for encrypted merchant payments
2. **Arcis Framework Usage**: Proper use of `#[confidential]` instructions and `Enc<>` types
3. **MPC Integration**: Encrypted computation without exposing sensitive data
4. **Production-Ready Architecture**: Event emission, error handling, account validation

**Privacy-Enabled Payments, Powered by Arcium** 🔒

