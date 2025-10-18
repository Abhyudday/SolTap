# Arcium Payment Bridge Service

TypeScript/Node.js service that provides REST API for Arcium MPC integration with Android app.

## Quick Start

```bash
# Install dependencies
npm install

# Configure environment
cp .env.example .env
# Edit .env with your settings

# Build
npm run build

# Start server
npm start

# Or for development with auto-reload
npm run dev
```

## Configuration

Edit `.env`:

```env
# Solana network
SOLANA_NETWORK=devnet
SOLANA_RPC_URL=https://api.devnet.solana.com

# Arcium program ID (from deployment)
ARCIUM_PROGRAM_ID=YOUR_PROGRAM_ID_HERE

# MXE configuration (from Arcium testnet)
MXE_PUBKEY=YOUR_MXE_PUBKEY
CLUSTER_PUBKEY=YOUR_CLUSTER_PUBKEY

# Server
PORT=3000
HOST=0.0.0.0

# Enable fallback to standard transactions
ENABLE_FALLBACK=true

# Log level
LOG_LEVEL=info
```

## API Endpoints

### GET `/health`

Check service health.

**Response:**
```json
{
  "status": "ok",
  "arciumEnabled": true,
  "network": "devnet"
}
```

### POST `/encrypt-payment`

Encrypt payment data using Arcium SDK.

**Request:**
```json
{
  "amountLamports": 100000000,
  "merchantPubkey": "MERCHANT_PUBKEY",
  "orderId": 12345,
  "buyerPubkey": "BUYER_PUBKEY"
}
```

**Response:**
```json
{
  "success": true,
  "encryptedAmount": "base64_encrypted_amount",
  "encryptedMerchantId": "base64_encrypted_merchant",
  "nonce": "base64_nonce",
  "orderId": 12345
}
```

### POST `/submit-private-payment`

Submit encrypted payment to Arcium program or fallback to standard transfer.

**Request:**
```json
{
  "buyerPrivateKey": "base58_private_key",
  "buyerPubkey": "BUYER_PUBKEY",
  "merchantPubkey": "MERCHANT_PUBKEY",
  "amountLamports": 100000000,
  "orderId": 12345,
  "encryptedAmount": "base64_encrypted_amount",
  "encryptedMerchantId": "base64_encrypted_merchant",
  "nonce": "base64_nonce"
}
```

**Response:**
```json
{
  "success": true,
  "signature": "TRANSACTION_SIGNATURE",
  "computationId": "comp_12345_1234567890",
  "encrypted": true,
  "message": "Private payment submitted to Arcium MPC"
}
```

### GET `/callback-status/:computationId`

Check MPC computation callback status.

**Response:**
```json
{
  "computationId": "comp_12345_1234567890",
  "orderId": 12345,
  "status": "success",
  "timestamp": 1234567890,
  "lastChecked": 1234567900
}
```

Status values: `pending`, `processing`, `success`, `failed`, `timeout`

### POST `/decrypt-receipt`

Decrypt payment receipt (buyer/merchant only).

**Request:**
```json
{
  "encryptedReceipt": "base64_encrypted_receipt",
  "privateKey": "base58_private_key",
  "nonce": "base64_nonce"
}
```

**Response:**
```json
{
  "success": true,
  "receipt": {
    "amount": 100000000,
    "orderId": 12345,
    "merchant": "MERCHANT_PUBKEY",
    "timestamp": 1234567890
  }
}
```

### POST `/get-merchant-revenue`

Get encrypted merchant revenue.

**Request:**
```json
{
  "merchantPubkey": "MERCHANT_PUBKEY",
  "merchantPrivateKey": "base58_private_key"
}
```

**Response:**
```json
{
  "success": true,
  "totalLamports": 500000000,
  "paymentCount": 5,
  "encrypted": true
}
```

## Architecture

```
Android App (Kotlin)
       │
       │ HTTP/JSON
       ▼
Bridge Service (TypeScript)
       │
       ├─► Arcium SDK
       │   └─► Encryption/Decryption
       │
       ├─► Solana Web3.js
       │   └─► Transaction Submission
       │
       └─► Callback Tracker
           └─► Polls for MPC Results
```

## Development

```bash
# Watch mode with auto-reload
npm run dev

# Build only
npm run build

# Run built version
npm start
```

## Testing

```bash
# Test health endpoint
curl http://localhost:3000/health

# Test encryption
curl -X POST http://localhost:3000/encrypt-payment \
  -H "Content-Type: application/json" \
  -d '{
    "amountLamports": 100000000,
    "merchantPubkey": "YOUR_MERCHANT_PUBKEY",
    "orderId": 12345,
    "buyerPubkey": "YOUR_BUYER_PUBKEY"
  }'
```

## Deployment

### Production Server

```bash
# Build for production
npm run build

# Run with PM2
pm2 start dist/index.js --name arcium-bridge

# Or with systemd
sudo systemctl enable arcium-bridge
sudo systemctl start arcium-bridge
```

### Docker (Optional)

```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY dist ./dist
EXPOSE 3000
CMD ["node", "dist/index.js"]
```

```bash
docker build -t arcium-bridge .
docker run -p 3000:3000 --env-file .env arcium-bridge
```

## Security

⚠️ **Important Security Notes:**

1. **Private Keys**: Never log or expose private keys
2. **HTTPS**: Use HTTPS in production (not HTTP)
3. **Rate Limiting**: Add rate limiting for production
4. **Authentication**: Add API authentication tokens
5. **CORS**: Configure CORS for production domains only

## Troubleshooting

### Bridge service won't start

```bash
# Check Node.js version (need 18+)
node --version

# Check dependencies
npm install

# Check .env configuration
cat .env
```

### Encryption fails

- Verify Arcium SDK is installed: `npm list @arcium/client`
- Check MXE_PUBKEY and CLUSTER_PUBKEY in `.env`
- Ensure network connectivity to Arcium testnet

### Payments fail

- Check SOLANA_RPC_URL is reachable
- Verify ARCIUM_PROGRAM_ID is correct
- Check buyer has sufficient SOL for transaction fees
- Look for error messages in console logs

### Fallback always triggers

- Verify `ENABLE_FALLBACK=true` in `.env`
- This is expected if Arcium program not deployed
- Check logs for specific Arcium errors

## Privacy-Enabled Payments, Powered by Arcium 🔒

