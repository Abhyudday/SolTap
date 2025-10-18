import { Connection, PublicKey } from '@solana/web3.js';

interface ComputationStatus {
  computationId: string;
  orderId: number;
  status: 'pending' | 'processing' | 'success' | 'failed' | 'timeout';
  result?: any;
  error?: string;
  timestamp: number;
  lastChecked: number;
}

/**
 * Tracks Arcium MPC computation callbacks
 * 
 * Polls Solana for computation completion events and callback results
 */
export class CallbackTracker {
  private connection: Connection;
  private computations: Map<string, ComputationStatus>;
  private pollInterval: NodeJS.Timeout | null;

  constructor() {
    const rpcUrl = process.env.SOLANA_RPC_URL || 'https://api.devnet.solana.com';
    this.connection = new Connection(rpcUrl, 'confirmed');
    this.computations = new Map();
    this.pollInterval = null;

    // Start polling for callback completions
    this.startPolling();
  }

  /**
   * Track a new computation
   */
  trackComputation(computationId: string, orderId: number): void {
    console.log(`📌 Tracking computation: ${computationId} (order ${orderId})`);
    
    this.computations.set(computationId, {
      computationId,
      orderId,
      status: 'pending',
      timestamp: Date.now(),
      lastChecked: Date.now(),
    });
  }

  /**
   * Get computation status
   */
  async getComputationStatus(computationId: string): Promise<ComputationStatus | null> {
    const computation = this.computations.get(computationId);
    
    if (!computation) {
      return null;
    }

    // Try to update status from chain
    await this.checkComputationStatus(computationId);

    return this.computations.get(computationId) || null;
  }

  /**
   * Start polling for computation updates
   */
  private startPolling(): void {
    // Poll every 5 seconds
    this.pollInterval = setInterval(() => {
      this.pollComputations();
    }, 5000);

    console.log('🔄 Started callback polling (5s interval)');
  }

  /**
   * Poll all tracked computations
   */
  private async pollComputations(): Promise<void> {
    const now = Date.now();
    const staleTimeout = 120000; // 2 minutes

    for (const [computationId, computation] of this.computations.entries()) {
      // Skip if already completed or failed
      if (computation.status === 'success' || computation.status === 'failed') {
        continue;
      }

      // Mark as timeout if too old
      if (now - computation.timestamp > staleTimeout) {
        computation.status = 'timeout';
        computation.error = 'Computation callback timed out';
        continue;
      }

      // Check status
      await this.checkComputationStatus(computationId);
    }

    // Clean up old completed computations (older than 10 minutes)
    const cleanupThreshold = now - 600000;
    for (const [computationId, computation] of this.computations.entries()) {
      if (computation.lastChecked < cleanupThreshold) {
        this.computations.delete(computationId);
        console.log(`🗑️  Cleaned up old computation: ${computationId}`);
      }
    }
  }

  /**
   * Check computation status on-chain
   */
  private async checkComputationStatus(computationId: string): Promise<void> {
    const computation = this.computations.get(computationId);
    if (!computation) return;

    try {
      // Derive computation account PDA
      const programId = new PublicKey(process.env.ARCIUM_PROGRAM_ID || 'ArcPay11111111111111111111111111111111111');
      
      // For Arcium, computation accounts are created by the program
      // In a real implementation, we'd derive the PDA and check the account
      // For now, we'll simulate checking for events

      // Query recent logs for callback events
      const signatures = await this.connection.getSignaturesForAddress(
        programId,
        { limit: 20 }
      );

      for (const sigInfo of signatures) {
        const tx = await this.connection.getTransaction(sigInfo.signature, {
          commitment: 'confirmed'
        });

        if (tx && tx.meta && tx.meta.logMessages) {
          // Check if this transaction contains our computation callback
          const hasCallbackLog = tx.meta.logMessages.some(log => 
            log.includes('PrivatePaymentEvent') && log.includes(computation.orderId.toString())
          );

          if (hasCallbackLog) {
            computation.status = 'success';
            computation.lastChecked = Date.now();
            console.log(`✅ Computation ${computationId} completed successfully`);
            return;
          }

          // Check for errors
          const hasError = tx.meta.logMessages.some(log => 
            log.includes('Error') || log.includes('failed')
          );

          if (hasError) {
            computation.status = 'failed';
            computation.error = 'Computation callback failed';
            computation.lastChecked = Date.now();
            console.log(`❌ Computation ${computationId} failed`);
            return;
          }
        }
      }

      // Still processing
      computation.status = 'processing';
      computation.lastChecked = Date.now();

    } catch (error) {
      console.error(`Error checking computation ${computationId}:`, error);
      computation.lastChecked = Date.now();
    }
  }

  /**
   * Stop polling
   */
  stop(): void {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
      console.log('🛑 Stopped callback polling');
    }
  }
}

