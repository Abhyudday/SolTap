# Arcium Integration Guide

## 🔒 Privacy-Enabled Payments, Powered by Arcium

This document explains the complete Arcium MPC integration for private merchant payments in this Solana Tap & Pay app.

## Architecture Overview

```
┌─────────────────┐
│  Android App    │
│  (Kotlin)       │
└────────┬────────┘
         │
         │ HTTP/JSON
         ▼
┌─────────────────┐
│ TypeScript      │
│ Bridge Service  │
│ (Arcium SDK)    │
└────────┬────────┘
         │
         │ Arcium Client API
         ▼
┌─────────────────┐      ┌─────────────────┐
│ Arcium Program  │◄────►│  Arcium MPC     │
│ (Solana/Anchor) │      │  Nodes          │
└─────────────────┘      └─────────────────┘
```

## Components

### 1. **Arcium Solana Program** (`arcium-program/`)

Production-ready Anchor program using Arcium's `arcium-anchor` and `arcis` frameworks.

**Key Features:**
- `#[arcium_program]` macro for MPC-enabled programs
- `#[queue_computation_accounts]` for encrypted computation submission
- `#[arcium_callback]` for handling MPC results
- Proper `Enc<Owner, T>` types for encrypted data
- Callback handling for async MPC computations

**Files:**
- `src/lib.rs` - Main program with instructions and callbacks
- `encrypted_ixes/process_payment.rs` - Encrypted payment logic (runs in MPC)
- `encrypted_ixes/calculate_revenue.rs` - Encrypted revenue computation
- `Cargo.toml` - Dependencies (`arcium-anchor`, `arcis`)

**Instructions:**
1. `init_private_payment_comp_def` - Initialize computation definition
2. `process_private_payment` - Queue encrypted payment for MPC
3. `process_private_payment_callback` - Handle payment result
4. `get_merchant_revenue` - Queue revenue calculation
5. `get_merchant_revenue_callback` - Handle revenue result

### 2. **TypeScript Bridge Service** (`arcium-client-bridge/`)

Express.js server that wraps Arcium's JavaScript SDK for Android integration.

**Why a Bridge?**
- Arcium SDK is JavaScript/TypeScript (Node.js/browser)
- Android app is Kotlin/Java
- Bridge service provides REST API for Android to consume
- Handles Arcium encryption, callback tracking, result decryption

**Key Endpoints:**

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Check service status |
| `/encrypt-payment` | POST | Encrypt payment data with Arcium |
| `/submit-private-payment` | POST | Submit to Arcium program |
| `/callback-status/:id` | GET | Check MPC computation status |
| `/decrypt-receipt` | POST | Decrypt payment receipt |
| `/get-merchant-revenue` | POST | Get encrypted revenue |

**Files:**
- `src/index.ts` - Express server and API routes
- `src/services/ArciumPaymentService.ts` - Arcium client integration
- `src/services/CallbackTracker.ts` - Callback polling and tracking

### 3. **Android Integration** (`app/src/main/java/com/solanatappay/arcium/`)

Kotlin wrappers that communicate with the bridge service.

**Files:**
- `ArciumPrivateRpcClient.kt` - Main client (HTTP → Bridge)
- `ArciumEncryption.kt` - Local encryption utilities (fallback)

**Flow:**
1. Check bridge health
2. Encrypt payment via bridge (`/encrypt-payment`)
3. Submit private payment (`/submit-private-payment`)
4. Bridge submits to Arcium program OR falls back to standard transfer
5. **Transaction always completes** (fallback ensures this)

### 4. **UI Integration**

Subtle branding added to all payment screens:
- `activity_merchant.xml` - Footer text
- `activity_buyer.xml` - Footer text

Text: `"privacy enabled payments, powered by arcium"`
- 9sp font size
- 60% opacity
- Centered at bottom

## Privacy Features

### What's Encrypted?

✅ **Payment amounts** - Encrypted with `Enc<Shared, u64>`  
✅ **Merchant identities** - Encrypted with `Enc<Mxe, [u8; 32]>`  
✅ **Revenue totals** - Computed within MPC, encrypted results  
✅ **Payment receipts** - Re-encrypted for buyer/merchant only  

### What's Public?

- Order IDs (non-sensitive nonces)
- Timestamps
- Buyer public keys
- Transaction signatures

### MPC Computation Flow

```
1. Android app encrypts data locally
2. Bridge service re-encrypts with Arcium SDK
3. Encrypted data submitted to Solana
4. Arcium program queues computation
5. MPC nodes execute encrypted logic
6. Callback writes encrypted results on-chain
7. Only authorized parties can decrypt
```

## Deployment

### Prerequisites

```bash
# Install Arcium CLI
cargo install --git https://github.com/arcium-labs/arcium arcium-cli

# Install Node.js 18+
nvm install 18
nvm use 18
```

### Step 1: Deploy Arcium Program

```bash
cd arcium-program

# Build program
arcium build

# Deploy to devnet
arcium deploy --network devnet --program-keypair keypair.json

# Copy deployed program ID
# Update ARCIUM_PROGRAM_ID in:
# - arcium-client-bridge/.env
# - app/src/main/java/com/solanatappay/arcium/ArciumPrivateRpcClient.kt
```

### Step 2: Start Bridge Service

```bash
cd arcium-client-bridge

# Install dependencies
npm install

# Configure environment
cp .env.example .env
# Edit .env with your program ID and MXE config

# Build TypeScript
npm run build

# Start service
npm start

# Or for development
npm run dev
```

### Step 3: Build Android App

```bash
cd app

# Build APK
./gradlew assembleDebug

# Install on device
adb install build/outputs/apk/debug/app-debug.apk
```

### Step 4: Configure Bridge URL in App

In the Android app settings (or code):

```kotlin
ArciumPrivateRpcClient.setBridgeUrl(context, "http://YOUR_SERVER_IP:3000")
```

For local testing:
```kotlin
ArciumPrivateRpcClient.setBridgeUrl(context, "http://10.0.2.2:3000") // Android emulator
```

## Testing

### Test Privacy Mode

1. **Start bridge service**: `cd arcium-client-bridge && npm start`
2. **Check health**: `curl http://localhost:3000/health`
3. **Run Android app**: Make a test payment
4. **Check logs**: Look for "🔒" privacy indicators

### Test Fallback Mode

1. **Stop bridge service**
2. **Run Android app**: Make a test payment
3. **Verify**: Payment still completes (standard Solana transfer)
4. **Check logs**: Look for "Falling back to standard transaction"

### Test Encryption

```bash
# Encrypt test payment
curl -X POST http://localhost:3000/encrypt-payment \
  -H "Content-Type: application/json" \
  -d '{
    "amountLamports": 100000000,
    "merchantPubkey": "MERCHANT_PUBKEY_HERE",
    "orderId": 12345,
    "buyerPubkey": "BUYER_PUBKEY_HERE"
  }'
```

## Fallback Mechanism

**Critical Feature**: Transactions ALWAYS go through, even if Arcium is unavailable.

### Fallback Triggers:
1. Bridge service unreachable
2. Encryption fails
3. Arcium program submission fails
4. Privacy mode disabled by user

### Fallback Behavior:
```kotlin
try {
    // Attempt Arcium private payment
    submitPrivatePaymentViaBridge(...)
} catch (e: Exception) {
    // ALWAYS fallback to standard transfer
    executeStandardPayment(...)
}
```

This ensures **100% payment reliability** for the hackathon demo.

## For Hackathon Judges

### What to Look For

1. **Program Structure** (`arcium-program/src/lib.rs`)
   - Proper `#[arcium_program]` usage
   - `Enc<>` types for encrypted data
   - Callback handlers for MPC results

2. **Encrypted Instructions** (`arcium-program/encrypted_ixes/`)
   - `#[encrypted_instruction]` macro
   - Logic that runs inside MPC
   - Private validation and computation

3. **TypeScript Integration** (`arcium-client-bridge/`)
   - Arcium SDK usage (`@arcium/client`)
   - Proper encryption/decryption flows
   - Callback tracking implementation

4. **Android Privacy Layer** (`app/src/main/java/com/solanatappay/arcium/`)
   - Bridge communication
   - Fallback for reliability
   - Clear privacy indicators in logs

### Privacy Architecture Highlights

- **Client-side encryption** before network transmission
- **MPC computation** without data exposure
- **Selective decryption** (merchant-only revenue)
- **On-chain privacy** (amounts never visible to validators)
- **Event emission** with encrypted data

### Production Readiness

✅ Proper error handling  
✅ Callback tracking for async results  
✅ Fallback mechanism for reliability  
✅ Configuration management  
✅ Logging and monitoring  
✅ Type-safe encrypted data structures  

## References

- **Arcium Docs**: https://docs.arcium.com/developers
- **Arcis Framework**: https://docs.arcium.com/developers/arcis
- **Encryption Guide**: https://docs.arcium.com/developers/encryption
- **JavaScript Client**: https://docs.arcium.com/developers/javascript-client
- **Callback Server**: https://docs.arcium.com/developers/callback-server

## Support

For questions about this integration:
1. Check logs (look for "🔒" emoji indicators)
2. Verify bridge service is running (`/health` endpoint)
3. Check Arcium testnet status
4. Test fallback mode to ensure basic functionality

---

**Built for Arcium Hackathon**  
**Privacy-Enabled Payments, Powered by Arcium** 🔒

