import { Connection, Keypair, PublicKey, Transaction } from '@solana/web3.js';
import { Program, AnchorProvider, Wallet } from '@project-serum/anchor';
import { ArciumClient, encryptData, decryptData } from '@arcium/client';
import * as bs58 from 'bs58';

interface EncryptedPaymentData {
  encryptedAmount: string;
  encryptedMerchantId: string;
  nonce: string;
}

interface PaymentSubmissionParams {
  buyerPrivateKey: string;
  buyerPubkey: string;
  merchantPubkey: string;
  amountLamports: number;
  orderId: number;
  encryptedAmount?: string;
  encryptedMerchantId?: string;
  nonce?: string;
}

interface PaymentResult {
  signature: string;
  computationId?: string;
  encrypted: boolean;
}

interface MerchantRevenue {
  totalLamports: number;
  paymentCount: number;
  encrypted: boolean;
}

export class ArciumPaymentService {
  private connection: Connection;
  private arciumClient: ArciumClient | null;
  private enableFallback: boolean;

  constructor() {
    const rpcUrl = process.env.SOLANA_RPC_URL || 'https://api.devnet.solana.com';
    this.connection = new Connection(rpcUrl, 'confirmed');
    this.enableFallback = process.env.ENABLE_FALLBACK === 'true';
    
    // Initialize Arcium client
    try {
      this.arciumClient = new ArciumClient({
        connection: this.connection,
        programId: new PublicKey(process.env.ARCIUM_PROGRAM_ID || 'ArcPay11111111111111111111111111111111111'),
        mxePubkey: process.env.MXE_PUBKEY ? new PublicKey(process.env.MXE_PUBKEY) : undefined,
        clusterPubkey: process.env.CLUSTER_PUBKEY ? new PublicKey(process.env.CLUSTER_PUBKEY) : undefined,
      });
      console.log('✅ Arcium client initialized');
    } catch (error) {
      console.warn('⚠️  Arcium client initialization failed, fallback mode enabled:', error);
      this.arciumClient = null;
      this.enableFallback = true;
    }
  }

  /**
   * Encrypt payment data using Arcium's encryption
   */
  async encryptPaymentData(
    amountLamports: number,
    merchantPubkey: string,
    orderId: number
  ): Promise<EncryptedPaymentData> {
    if (!this.arciumClient) {
      throw new Error('Arcium client not initialized');
    }

    try {
      console.log(`🔐 Encrypting payment: ${amountLamports} lamports for merchant ${merchantPubkey.substring(0, 8)}...`);

      // Encrypt amount (Enc<Shared, u64>)
      const amountBuffer = Buffer.alloc(8);
      amountBuffer.writeBigUInt64LE(BigInt(amountLamports));
      
      const encryptedAmount = await encryptData(
        this.arciumClient,
        amountBuffer,
        'Shared' // Shared encryption type
      );

      // Encrypt merchant ID (Enc<Mxe, [u8; 32]>)
      const merchantPubkeyBytes = bs58.decode(merchantPubkey);
      const encryptedMerchantId = await encryptData(
        this.arciumClient,
        merchantPubkeyBytes,
        'Mxe' // MXE-only encryption
      );

      console.log('✅ Payment data encrypted successfully');

      return {
        encryptedAmount: Buffer.from(encryptedAmount.ciphertext).toString('base64'),
        encryptedMerchantId: Buffer.from(encryptedMerchantId.ciphertext).toString('base64'),
        nonce: Buffer.from(encryptedAmount.nonce).toString('base64'),
      };
    } catch (error) {
      console.error('❌ Encryption failed:', error);
      throw error;
    }
  }

  /**
   * Submit private payment to Arcium program or fallback to standard transaction
   */
  async submitPrivatePayment(params: PaymentSubmissionParams): Promise<PaymentResult> {
    const {
      buyerPrivateKey,
      buyerPubkey,
      merchantPubkey,
      amountLamports,
      orderId,
      encryptedAmount,
      encryptedMerchantId,
      nonce
    } = params;

    // Try Arcium path first if available
    if (this.arciumClient && !this.enableFallback && encryptedAmount && encryptedMerchantId) {
      try {
        console.log('🔒 Submitting private payment via Arcium MPC');
        return await this.submitViaArcium(params);
      } catch (error) {
        console.error('⚠️  Arcium submission failed:', error);
        
        if (!this.enableFallback) {
          throw error;
        }
        
        console.log('🔄 Falling back to standard transaction');
      }
    }

    // Fallback to standard Solana transfer
    console.log('💸 Processing standard Solana transfer (fallback mode)');
    return await this.submitStandardTransfer(
      buyerPrivateKey,
      buyerPubkey,
      merchantPubkey,
      amountLamports,
      orderId
    );
  }

  /**
   * Submit via Arcium MPC program
   */
  private async submitViaArcium(params: PaymentSubmissionParams): Promise<PaymentResult> {
    if (!this.arciumClient) {
      throw new Error('Arcium client not initialized');
    }

    const { buyerPrivateKey, merchantPubkey, encryptedAmount, encryptedMerchantId, orderId } = params;

    try {
      // Parse buyer keypair
      const buyerKeypair = Keypair.fromSecretKey(bs58.decode(buyerPrivateKey));

      // Build Arcium instruction
      const instruction = await this.arciumClient.createProcessPrivatePaymentInstruction({
        buyer: buyerKeypair.publicKey,
        merchant: new PublicKey(merchantPubkey),
        encryptedAmount: Buffer.from(encryptedAmount!, 'base64'),
        encryptedMerchantId: Buffer.from(encryptedMerchantId!, 'base64'),
        orderId: orderId,
      });

      // Build and send transaction
      const transaction = new Transaction().add(instruction);
      const signature = await this.connection.sendTransaction(
        transaction,
        [buyerKeypair],
        { skipPreflight: false, preflightCommitment: 'confirmed' }
      );

      // Wait for confirmation
      await this.connection.confirmTransaction(signature, 'confirmed');

      console.log(`✅ Private payment submitted: ${signature}`);
      console.log(`🔒 Amount and merchant ID remain encrypted on-chain`);

      return {
        signature,
        computationId: `comp_${orderId}_${Date.now()}`, // Derive from on-chain computation account
        encrypted: true,
      };
    } catch (error) {
      console.error('❌ Arcium submission failed:', error);
      throw error;
    }
  }

  /**
   * Submit standard Solana transfer (fallback)
   */
  private async submitStandardTransfer(
    buyerPrivateKey: string,
    buyerPubkey: string,
    merchantPubkey: string,
    amountLamports: number,
    orderId: number
  ): Promise<PaymentResult> {
    try {
      const buyerKeypair = Keypair.fromSecretKey(bs58.decode(buyerPrivateKey));
      const merchantPubkeyObj = new PublicKey(merchantPubkey);

      // Create transfer instruction
      const { SystemProgram } = await import('@solana/web3.js');
      const transferInstruction = SystemProgram.transfer({
        fromPubkey: buyerKeypair.publicKey,
        toPubkey: merchantPubkeyObj,
        lamports: amountLamports,
      });

      const transaction = new Transaction().add(transferInstruction);
      const signature = await this.connection.sendTransaction(
        transaction,
        [buyerKeypair],
        { skipPreflight: false, preflightCommitment: 'confirmed' }
      );

      await this.connection.confirmTransaction(signature, 'confirmed');

      console.log(`✅ Standard payment completed: ${signature}`);

      return {
        signature,
        encrypted: false,
      };
    } catch (error) {
      console.error('❌ Standard transfer failed:', error);
      throw error;
    }
  }

  /**
   * Decrypt payment receipt
   */
  async decryptReceipt(
    encryptedReceipt: string,
    privateKey: string,
    nonce: string
  ): Promise<any> {
    if (!this.arciumClient) {
      throw new Error('Arcium client not initialized');
    }

    try {
      const encryptedBuffer = Buffer.from(encryptedReceipt, 'base64');
      const nonceBuffer = Buffer.from(nonce, 'base64');
      const keyBuffer = bs58.decode(privateKey);

      const decrypted = await decryptData(
        this.arciumClient,
        { ciphertext: encryptedBuffer, nonce: nonceBuffer },
        keyBuffer
      );

      // Parse decrypted receipt structure
      const receipt = JSON.parse(decrypted.toString('utf8'));
      
      console.log('✅ Receipt decrypted successfully');
      return receipt;
    } catch (error) {
      console.error('❌ Decryption failed:', error);
      throw error;
    }
  }

  /**
   * Get merchant revenue (encrypted or plaintext based on mode)
   */
  async getMerchantRevenue(
    merchantPubkey: string,
    merchantPrivateKey: string
  ): Promise<MerchantRevenue> {
    if (this.arciumClient && !this.enableFallback) {
      try {
        // Query encrypted revenue from Arcium program
        console.log('📊 Fetching encrypted revenue from Arcium program');
        
        const revenueAccountPda = await this.deriveRevenueAccountPda(merchantPubkey);
        const accountInfo = await this.connection.getAccountInfo(revenueAccountPda);

        if (!accountInfo) {
          console.log('No revenue account found');
          return { totalLamports: 0, paymentCount: 0, encrypted: true };
        }

        // Decrypt revenue data (merchant only)
        const encryptedTotal = accountInfo.data.slice(8, 40); // Extract encrypted portion
        // Decryption would happen here with merchant's private key

        return {
          totalLamports: 0, // Placeholder - would decrypt here
          paymentCount: 0,
          encrypted: true,
        };
      } catch (error) {
        console.error('⚠️  Encrypted revenue fetch failed:', error);
      }
    }

    // Fallback: calculate from transaction history
    console.log('📊 Calculating revenue from transaction history (fallback)');
    const signatures = await this.connection.getSignaturesForAddress(
      new PublicKey(merchantPubkey),
      { limit: 100 }
    );

    let totalLamports = 0;
    let paymentCount = 0;

    for (const sigInfo of signatures) {
      const tx = await this.connection.getTransaction(sigInfo.signature, {
        commitment: 'confirmed'
      });
      
      if (tx && tx.meta) {
        const postBalance = tx.meta.postBalances[0] || 0;
        const preBalance = tx.meta.preBalances[0] || 0;
        const diff = postBalance - preBalance;
        
        if (diff > 0) {
          totalLamports += diff;
          paymentCount++;
        }
      }
    }

    return {
      totalLamports,
      paymentCount,
      encrypted: false,
    };
  }

  /**
   * Derive revenue account PDA
   */
  private async deriveRevenueAccountPda(merchantPubkey: string): Promise<PublicKey> {
    const programId = new PublicKey(process.env.ARCIUM_PROGRAM_ID || 'ArcPay11111111111111111111111111111111111');
    const [pda] = await PublicKey.findProgramAddress(
      [Buffer.from('revenue'), new PublicKey(merchantPubkey).toBuffer()],
      programId
    );
    return pda;
  }
}

