import express, { Express, Request, Response } from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { ArciumPaymentService } from './services/ArciumPaymentService';
import { CallbackTracker } from './services/CallbackTracker';

dotenv.config();

const app: Express = express();
const port = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json());

// Initialize services
const paymentService = new ArciumPaymentService();
const callbackTracker = new CallbackTracker();

// Health check
app.get('/health', (req: Request, res: Response) => {
  res.json({ 
    status: 'ok',
    arciumEnabled: !process.env.ENABLE_FALLBACK || process.env.ENABLE_FALLBACK === 'false',
    network: process.env.SOLANA_NETWORK || 'devnet'
  });
});

/**
 * POST /encrypt-payment
 * 
 * Encrypt payment data using Arcium's encryption
 * 
 * Body:
 * {
 *   "amountLamports": number,
 *   "merchantPubkey": string,
 *   "orderId": number,
 *   "buyerPubkey": string
 * }
 */
app.post('/encrypt-payment', async (req: Request, res: Response) => {
  try {
    const { amountLamports, merchantPubkey, orderId, buyerPubkey } = req.body;
    
    if (!amountLamports || !merchantPubkey || !orderId || !buyerPubkey) {
      return res.status(400).json({ error: 'Missing required fields' });
    }

    console.log('🔒 Encrypting payment data via Arcium');
    const encryptedData = await paymentService.encryptPaymentData(
      amountLamports,
      merchantPubkey,
      orderId
    );

    res.json({
      success: true,
      encryptedAmount: encryptedData.encryptedAmount,
      encryptedMerchantId: encryptedData.encryptedMerchantId,
      nonce: encryptedData.nonce,
      orderId
    });
  } catch (error: any) {
    console.error('Encryption error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /submit-private-payment
 * 
 * Submit encrypted payment to Arcium program
 * 
 * Body:
 * {
 *   "buyerPrivateKey": string (base58),
 *   "buyerPubkey": string,
 *   "merchantPubkey": string,
 *   "amountLamports": number,
 *   "orderId": number,
 *   "encryptedAmount": string,
 *   "encryptedMerchantId": string,
 *   "nonce": string
 * }
 */
app.post('/submit-private-payment', async (req: Request, res: Response) => {
  try {
    const {
      buyerPrivateKey,
      buyerPubkey,
      merchantPubkey,
      amountLamports,
      orderId,
      encryptedAmount,
      encryptedMerchantId,
      nonce
    } = req.body;

    console.log('📤 Submitting private payment to Arcium');
    console.log(`Order ID: ${orderId}, Amount: ${amountLamports} lamports`);

    const result = await paymentService.submitPrivatePayment({
      buyerPrivateKey,
      buyerPubkey,
      merchantPubkey,
      amountLamports,
      orderId,
      encryptedAmount,
      encryptedMerchantId,
      nonce
    });

    // Track callback for this computation
    if (result.computationId) {
      callbackTracker.trackComputation(result.computationId, orderId);
    }

    res.json({
      success: true,
      signature: result.signature,
      computationId: result.computationId,
      encrypted: result.encrypted,
      message: result.encrypted 
        ? 'Private payment submitted to Arcium MPC' 
        : 'Standard payment completed (fallback mode)'
    });
  } catch (error: any) {
    console.error('Payment submission error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * GET /callback-status/:computationId
 * 
 * Check status of MPC computation callback
 */
app.get('/callback-status/:computationId', async (req: Request, res: Response) => {
  try {
    const { computationId } = req.params;
    const status = await callbackTracker.getComputationStatus(computationId);

    res.json(status);
  } catch (error: any) {
    console.error('Callback status error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /decrypt-receipt
 * 
 * Decrypt payment receipt (buyer/merchant only)
 * 
 * Body:
 * {
 *   "encryptedReceipt": string,
 *   "privateKey": string,
 *   "nonce": string
 * }
 */
app.post('/decrypt-receipt', async (req: Request, res: Response) => {
  try {
    const { encryptedReceipt, privateKey, nonce } = req.body;

    console.log('🔓 Decrypting payment receipt');
    const receipt = await paymentService.decryptReceipt(
      encryptedReceipt,
      privateKey,
      nonce
    );

    res.json({
      success: true,
      receipt
    });
  } catch (error: any) {
    console.error('Decryption error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /get-merchant-revenue
 * 
 * Get encrypted merchant revenue
 * 
 * Body:
 * {
 *   "merchantPubkey": string,
 *   "merchantPrivateKey": string
 * }
 */
app.post('/get-merchant-revenue', async (req: Request, res: Response) => {
  try {
    const { merchantPubkey, merchantPrivateKey } = req.body;

    console.log('📊 Fetching encrypted merchant revenue');
    const revenue = await paymentService.getMerchantRevenue(
      merchantPubkey,
      merchantPrivateKey
    );

    res.json({
      success: true,
      totalLamports: revenue.totalLamports,
      paymentCount: revenue.paymentCount,
      encrypted: revenue.encrypted
    });
  } catch (error: any) {
    console.error('Revenue fetch error:', error);
    res.status(500).json({ error: error.message });
  }
});

// Start server
app.listen(port, () => {
  console.log('🚀 Arcium Payment Bridge Server');
  console.log(`📡 Listening on port ${port}`);
  console.log(`🌐 Network: ${process.env.SOLANA_NETWORK || 'devnet'}`);
  console.log(`🔒 Privacy Mode: ${process.env.ENABLE_FALLBACK === 'true' ? 'Hybrid (with fallback)' : 'Full Arcium MPC'}`);
  console.log('\n✅ Ready to process privacy-enabled payments');
});

export default app;

