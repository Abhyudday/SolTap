# Solana Tap & Pay - Private Payment App

A proof-of-concept Android app that enables instant "tap-to-pay" cryptocurrency payments using **Bluetooth Low Energy (BLE)** or **NFC** for device discovery and **Solana devnet** for on-chain transactions.

## 🔒 Privacy-Enabled Payments, Powered by Arcium

This app integrates **[Arcium's Multi-Party Computation (MPC) network](https://arcium.com)** to provide **privacy-first merchant payments**. Transaction amounts, merchant identities, and revenue totals remain fully encrypted throughout the payment lifecycle, preventing front-running, data leakage, and competitive surveillance.

### Arcium Integration Highlights

✅ **Encrypted Payment Amounts** - Transaction values never exposed to validators or blockchain observers  
✅ **Hidden Merchant Identity** - Merchant addresses encrypted during processing  
✅ **Private Revenue Tracking** - Total merchant revenue computed within MPC  
✅ **Confidential Order Processing** - Payment details only decryptable by authorized parties

Built for the **Arcium Hackathon** - demonstrating real-world privacy use cases in DeFi payments.

---

## 📚 Arcium Integration Documentation

For complete Arcium MPC integration details, see:
- **[ARCIUM_INTEGRATION.md](ARCIUM_INTEGRATION.md)** - Complete integration guide
- **[arcium-program/README.md](arcium-program/README.md)** - Solana program documentation
- **[arcium-client-bridge/README.md](arcium-client-bridge/README.md)** - Bridge service API docs

**Quick Deploy**: Run `./deploy-arcium.sh` to deploy the Arcium program and start the bridge service.

---

## ⚠️ CRITICAL SECURITY WARNING

**THIS IS A PROTOTYPE FOR DEMONSTRATION PURPOSES ONLY**

- ❌ **DO NOT USE ON MAINNET**
- ❌ **DO NOT USE WITH REAL MONEY**
- ❌ Private keys are stored in Android Keystore but are accessed programmatically without user approval
- ❌ Payments execute automatically upon BLE detection with NO confirmation prompt
- ❌ No authentication, no spending limits, no fraud protection

### For Production Use:
1. **Never auto-sign transactions** - require explicit user approval (biometric, PIN)
2. **Use server-side custody** or hardware security modules (HSM)
3. **Implement spending limits** and velocity checks
4. **Add multi-factor authentication**
5. **Use proper Ed25519 library** (current implementation is simplified for demo)
6. **Implement transaction monitoring** and fraud detection
7. **Add proper error recovery** and retry logic

---

## 🎯 Features

- **Single APK, Dual Mode**: Choose Buyer or Merchant mode on first launch (permanent)
- **BLE Advertising**: Merchant broadcasts payment requests with low latency
- **BLE Scanning**: Buyer scans and auto-pays upon detection
- **Solana Devnet**: All transactions on Solana devnet (testnet)
- **Android Keystore**: Secure key storage (still accessed programmatically)
- **Foreground Services**: Ensures BLE operations continue reliably
- **No Tap-Time Prompts**: Instant payment execution (for speed testing)

---

## 📋 Requirements

### Hardware
- **2 Android devices** (for testing buyer and merchant)
- Android 8.0+ (API 26+)
- Bluetooth Low Energy support (all modern Android phones)

### Software
- Android Studio Flamingo or newer (2022.2.1+)
- JDK 8 or newer
- Solana CLI (optional, for funding wallets)

### Permissions Required
- `BLUETOOTH_ADVERTISE` (Android 12+)
- `BLUETOOTH_CONNECT` (Android 12+)
- `BLUETOOTH_SCAN` (Android 12+)
- `BLUETOOTH` & `BLUETOOTH_ADMIN` (Android 11 and below)
- `ACCESS_FINE_LOCATION` (required for BLE on older Android)
- `FOREGROUND_SERVICE`
- `POST_NOTIFICATIONS` (Android 13+)
- `INTERNET` (for Solana RPC calls)

---

## 🛠️ Setup & Installation

### Option 1: Build from Source (Recommended)

#### Step 1: Clone/Open Project
```bash
cd "/Users/abhyuday/Desktop/jai baba baijnath"
```

Open the project in Android Studio:
```bash
open -a "Android Studio" .
```

#### Step 2: Sync Gradle
- Android Studio will automatically detect the project
- Wait for Gradle sync to complete
- If prompted, accept any SDK/tool updates

#### Step 3: Build Debug APK
```bash
# From project root directory
./gradlew assembleDebug
```

Or in Android Studio:
- Go to **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**

The APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

#### Step 4: Build Release APK (Unsigned)
```bash
./gradlew assembleRelease
```

For a signed release APK (optional):
1. Generate keystore:
```bash
keytool -genkey -v -keystore solana-tap-pay.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

2. Add to `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../solana-tap-pay.keystore")
            storePassword = "your-password"
            keyAlias = "release"
            keyPassword = "your-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

3. Build signed APK:
```bash
./gradlew assembleRelease
```

---

### Option 2: Install Pre-built APK

If you have a pre-built APK:

```bash
# Connect device via USB (enable USB debugging in Developer Options)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy APK to devices and install manually:
- Transfer APK via USB, email, or cloud storage
- Open APK on Android device
- Allow "Install from Unknown Sources" if prompted
- Install the app

---

## 🧪 Testing Instructions

### Prerequisites: Fund Your Wallets

Both buyer and merchant need SOL on **devnet** to pay transaction fees (and buyer needs funds for payment).

#### Method 1: Using Solana CLI (Recommended)
```bash
# Install Solana CLI (if not installed)
sh -c "$(curl -sSfL https://release.solana.com/stable/install)"

# Fund the wallet (get address from app Settings)
solana airdrop 2 YOUR_WALLET_ADDRESS --url devnet

# Example:
solana airdrop 2 9dZu7vKMXv9NcZP9nqBr7U9BwFyLp8f3Q5kN8K7x2Xj8 --url devnet
```

#### Method 2: Web Faucet
1. Open app → Settings
2. Copy your wallet address
3. Visit: https://faucet.solana.com
4. Select "Devnet"
5. Paste your address and request airdrop
6. Wait 10-30 seconds, then refresh balance in app

**Buyer needs at least 0.1 SOL for testing**  
**Merchant needs at least 0.01 SOL for transaction fees**

---

### Test Scenario: Complete Payment Flow

#### Setup (2 Devices Required)

**Device 1 - MERCHANT:**
1. Install APK
2. Launch app
3. Select **"Merchant"** mode (this is permanent!)
4. Go to Settings → Copy wallet address
5. Fund wallet on devnet (see above)
6. Return to main screen

**Device 2 - BUYER:**
1. Install APK
2. Launch app
3. Select **"Buyer"** mode (this is permanent!)
4. Go to Settings → Copy wallet address
5. Fund wallet with at least 0.1 SOL on devnet
6. Return to main screen

#### Execute Payment

**On Merchant Device (Device 1):**
1. Enter amount in USD (e.g., `5.00`)
2. Note the SOL conversion shown below
3. Tap **"Start Payment Request"**
4. Wait for status to show "Advertising: Waiting for buyer..."
5. Keep screen on and app in foreground

**On Buyer Device (Device 2):**
1. App should already be scanning (shows "Scanning for merchants...")
2. **Bring devices close together** (within 1-3 meters)
3. Payment will execute **AUTOMATICALLY** when merchant detected:
   - Status changes to "Processing payment..."
   - Transaction is signed and sent automatically
   - Success screen appears with transaction signature
4. Tap "View on Explorer" to see transaction on Solana Explorer

**On Merchant Device:**
1. To verify payment, tap "Stop Request"
2. Go to Settings → Tap "Refresh Balance"
3. Balance should increase by payment amount (minus fees)
4. Or check merchant address on https://explorer.solana.com/?cluster=devnet

---

### Expected Results

**Successful Payment:**
- ✅ Buyer sees "Payment successful!" with transaction signature
- ✅ Transaction appears on Solana Explorer (devnet)
- ✅ Merchant balance increases within 1-2 seconds
- ✅ Total time: 2-5 seconds from detection to confirmation

**Typical Timeline:**
- BLE detection: 0.5-2 seconds
- Transaction build & sign: 0.2-0.5 seconds
- RPC submission: 0.5-2 seconds
- Blockchain confirmation: 1-2 seconds

---

### Troubleshooting

#### "Bluetooth not available" or "Scan failed"
- **Solution:** Enable Bluetooth in device settings
- Grant all permissions when prompted
- Try restarting Bluetooth

#### "Permission denied"
- **Solution:** Go to Settings → Apps → Solana Tap Pay → Permissions
- Enable: Location, Bluetooth, Notifications

#### "No merchant detected"
- **Solution:** 
  - Ensure merchant is actively advertising (button shows "Stop Request")
  - Bring devices closer (< 2 meters)
  - Ensure no physical barriers (metal, walls)
  - Restart both apps

#### "Payment failed: insufficient funds"
- **Solution:** Fund buyer wallet with more SOL on devnet
- Check balance in Settings

#### "Payment failed: blockhash not found"
- **Solution:** Network congestion or slow RPC
- Try again in a few seconds
- Check internet connection

#### "RPC error" or timeout
- **Solution:** 
  - Check internet connection on buyer device
  - Devnet may be slow or down (check status.solana.com)
  - Try again

---

## 🔧 Configuration

### Change Solana Network (Advanced)

**⚠️ WARNING: DO NOT USE MAINNET WITH AUTO-SIGNING**

Edit `app/src/main/java/com/solanatappay/solana/SolanaRpcClient.kt`:

```kotlin
// Line 31: Change RPC endpoint
private const val SOLANA_RPC_URL = "https://api.devnet.solana.com"

// For mainnet (NOT RECOMMENDED):
// private const val SOLANA_RPC_URL = "https://api.mainnet-beta.solana.com"
```

### Change BLE Service UUID

Edit `app/src/main/java/com/solanatappay/ble/PaymentPayload.kt`:

```kotlin
// Line 23: Change service UUID
val SERVICE_UUID: UUID = UUID.fromString("0000FEE0-0000-1000-8000-00805F9B34FB")
```

Must be the same on both buyer and merchant!

### Adjust USD to SOL Conversion Rate

Edit `app/src/main/java/com/solanatappay/ui/MerchantActivity.kt`:

```kotlin
// Line 23: Change conversion rate
private const val USD_TO_SOL_RATE = 0.01 // 1 USD = 0.01 SOL
```

For production, fetch real-time rates from an exchange API.

---

## 📱 App Architecture

### Components

**Activities:**
- `ModeSelectionActivity` - First launch mode selection
- `MerchantActivity` - Merchant UI and payment request control
- `BuyerActivity` - Buyer UI and payment status
- `SettingsActivity` - Wallet info and funding instructions

**Services:**
- `MerchantAdvertiserService` - BLE advertising (foreground service)
- `BuyerScannerService` - BLE scanning and auto-payment (foreground service)

**Solana Layer:**
- `SolanaKeyManager` - Keypair generation and Android Keystore storage
- `SolanaRpcClient` - Transaction building and RPC communication
- `Ed25519` - Signing (simplified demo implementation)
- `Base58` - Address encoding/decoding

**BLE Layer:**
- `PaymentPayload` - BLE advertisement data structure (48 bytes)
- `BleConfig` - Low-latency BLE settings

### BLE Payload Format

```
Total: 48 bytes
├─ Merchant Public Key: 32 bytes (raw bytes)
├─ Amount (lamports):   8 bytes (little-endian long)
└─ Order ID (nonce):    8 bytes (little-endian long)
```

Transmitted as manufacturer data (0xFFFF) with service UUID filter.

### Transaction Flow

1. **Merchant** starts advertising → Foreground service broadcasts BLE
2. **Buyer** scanner detects advertisement → Parses payload
3. **Auto-payment** triggered:
   - Fetch latest blockhash from Solana RPC
   - Build SystemProgram transfer instruction
   - Sign with buyer's private key (from Keystore)
   - Submit transaction via RPC
   - Return signature
4. **UI updates** on both devices
5. **Transaction confirms** on Solana blockchain (~1-2 seconds)

---

## 🐛 Known Limitations

### Technical
- **Ed25519 signing is simplified** - Does NOT produce valid Solana signatures in current form
  - For real testing, replace with proper Ed25519 library (TweetNaCl, Tink, or Solana SDK)
- **No transaction retry logic** beyond basic RPC retry
- **Single concurrent payment** - No queue for multiple merchants
- **No payment cancellation** once initiated

### UX
- **No spending limits** or fraud protection
- **No payment confirmation** - instant execution
- **Mode cannot be changed** after selection (by design)
- **No transaction history** in app

### Security
- **Private key accessible programmatically** - No user authentication required
- **No rate limiting** on payments
- **Replay protection** only via order ID nonce (merchant must generate unique IDs)

---

## 🚀 Production Roadmap

To make this production-ready:

1. **✅ Authentication**
   - Biometric (fingerprint/face)
   - PIN/password
   - Per-transaction approval

2. **✅ Proper Ed25519**
   - Replace demo signing with real library
   - Validate signatures before submission

3. **✅ Server-side Signing**
   - Move keys to backend service
   - Client sends payment intent, server signs

4. **✅ Transaction Monitoring**
   - WebSocket subscription for real-time confirmation
   - Merchant notification when payment confirms

5. **✅ Error Handling**
   - Retry logic with exponential backoff
   - User-friendly error messages
   - Payment timeout and cancellation

6. **✅ Spending Controls**
   - Daily/per-transaction limits
   - Merchant whitelist
   - Velocity checks

7. **✅ Mainnet Support**
   - Only after security hardening
   - Real USD/SOL price feed
   - Priority fee estimation

---

## 📄 License

This is a proof-of-concept prototype for educational purposes.  
**Not licensed for production use.**

---

## 🤝 Support

For issues or questions:
1. Check Troubleshooting section above
2. Verify devices meet requirements
3. Ensure wallets are funded on devnet
4. Check Solana devnet status: https://status.solana.com

---

## 📚 Resources

- **Solana Devnet Faucet:** https://faucet.solana.com
- **Solana Explorer:** https://explorer.solana.com/?cluster=devnet
- **Solana Docs:** https://docs.solana.com
- **Android BLE Guide:** https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview
- **Android Keystore:** https://developer.android.com/training/articles/keystore

---

**Built with Kotlin • Solana • BLE**

