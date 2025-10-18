use anchor_lang::prelude::*;
use arcium_anchor::prelude::*;

declare_id!("ArcPay11111111111111111111111111111111111");

/// Arcium Private Payments Program
/// 
/// This program uses Arcium's Multi-Party Computation (MPC) to process
/// encrypted payment data, ensuring merchant transaction amounts and 
/// counterparties remain completely private.
/// 
/// Built for the Arcium Hackathon - Privacy-Enabled Payments
#[arcium_program]
pub mod arcium_private_payments {
    use super::*;

    /// Initialize computation definition for private payments
    #[init_computation_definition_accounts]
    pub fn init_private_payment_comp_def(
        ctx: Context<InitPrivatePaymentCompDef>,
    ) -> Result<()> {
        init_comp_def!(ctx);
        Ok(())
    }

    /// Process encrypted payment with private merchant details
    /// 
    /// This instruction uses Arcium's MPC network to:
    /// 1. Keep payment amounts encrypted
    /// 2. Hide merchant identities
    /// 3. Process transactions without exposing sensitive data to validators
    #[queue_computation_accounts]
    pub fn process_private_payment(
        ctx: Context<ProcessPrivatePayment>,
        encrypted_amount: Enc<Shared, u64>,
        encrypted_merchant_id: Enc<Mxe, [u8; 32]>,
        order_id: u64,
    ) -> Result<()> {
        queue_computation!(
            ctx,
            "process_payment",
            vec![
                Argument::EncU64(encrypted_amount),
                Argument::EncBytes32(encrypted_merchant_id),
                Argument::U64(order_id),
            ]
        );
        Ok(())
    }

    /// Callback handler for private payment processing
    /// 
    /// Receives encrypted results from MPC computation
    #[arcium_callback(encrypted_ix = "process_payment")]
    pub fn process_private_payment_callback(
        ctx: Context<ProcessPrivatePaymentCallback>,
        output: ComputationOutputs<ProcessPaymentOutput>,
    ) -> Result<()> {
        let result = match output {
            ComputationOutputs::Success(data) => data,
            ComputationOutputs::Failure(error) => {
                msg!("Payment computation failed: {:?}", error);
                return Err(ErrorCode::PaymentComputationFailed.into());
            }
            ComputationOutputs::Timeout => {
                msg!("Payment computation timed out");
                return Err(ErrorCode::PaymentTimeout.into());
            }
        };

        // Extract encrypted payment confirmation (Shared with buyer)
        let ProcessPaymentOutput { 
            field_0: ProcessPaymentTupleStruct0 {
                field_0: payment_success,  // Enc<Shared, bool>
                field_1: encrypted_receipt, // Enc<Shared, PaymentReceipt>
            }
        } = result;

        // Store encrypted receipt in account
        let payment_record = &mut ctx.accounts.payment_record;
        payment_record.encrypted_confirmation = encrypted_receipt;
        payment_record.order_id = ctx.accounts.computation_account.nonce;
        payment_record.timestamp = Clock::get()?.unix_timestamp;
        payment_record.buyer = ctx.accounts.buyer.key();

        // Emit event with encrypted data (buyer can decrypt their receipt)
        emit!(PrivatePaymentEvent {
            order_id: payment_record.order_id,
            buyer: payment_record.buyer,
            timestamp: payment_record.timestamp,
            encrypted_confirmation: payment_success.ciphertexts[0],
            nonce: payment_success.nonce.to_le_bytes(),
        });

        Ok(())
    }

    /// Get merchant's encrypted revenue (merchant-only decryption)
    #[queue_computation_accounts]
    pub fn get_merchant_revenue(
        ctx: Context<GetMerchantRevenue>,
        encrypted_merchant_id: Enc<Mxe, [u8; 32]>,
    ) -> Result<()> {
        queue_computation!(
            ctx,
            "calculate_revenue",
            vec![Argument::EncBytes32(encrypted_merchant_id)]
        );
        Ok(())
    }

    /// Callback for merchant revenue calculation
    #[arcium_callback(encrypted_ix = "calculate_revenue")]
    pub fn get_merchant_revenue_callback(
        ctx: Context<GetMerchantRevenueCallback>,
        output: ComputationOutputs<CalculateRevenueOutput>,
    ) -> Result<()> {
        let result = match output {
            ComputationOutputs::Success(data) => data,
            ComputationOutputs::Failure(_) => {
                return Err(ErrorCode::RevenueComputationFailed.into());
            }
            ComputationOutputs::Timeout => {
                return Err(ErrorCode::RevenueTimeout.into());
            }
        };

        // Extract encrypted revenue (Shared with merchant only)
        let CalculateRevenueOutput { field_0: encrypted_revenue } = result;

        let revenue_account = &mut ctx.accounts.revenue_account;
        revenue_account.encrypted_total = encrypted_revenue;
        revenue_account.last_updated = Clock::get()?.unix_timestamp;

        emit!(RevenueUpdateEvent {
            merchant: revenue_account.merchant,
            timestamp: revenue_account.last_updated,
            // Revenue amount stays encrypted
        });

        Ok(())
    }
}

/// Computation definition initialization context
#[derive(Accounts)]
pub struct InitPrivatePaymentCompDef<'info> {
    #[account(mut)]
    pub payer: Signer<'info>,
    
    /// CHECK: Validated by Arcium
    #[account(mut)]
    pub computation_definition_account: AccountInfo<'info>,
    
    /// CHECK: Validated by Arcium
    #[account(mut)]
    pub mxe_account: Account<'info, MXEAccount>,
    
    /// CHECK: Validated by Arcium
    pub cluster: Account<'info, Cluster>,
    
    pub system_program: Program<'info, System>,
    
    /// CHECK: Arcium program
    pub arcium_program: AccountInfo<'info>,
}

#[derive(Accounts)]
#[instruction(encrypted_amount: Enc<Shared, u64>, encrypted_merchant_id: Enc<Mxe, [u8; 32]>, order_id: u64)]
pub struct ProcessPrivatePayment<'info> {
    #[account(mut)]
    pub buyer: Signer<'info>,
    
    /// CHECK: Merchant public key (encrypted in computation)
    pub merchant: AccountInfo<'info>,
    
    /// CHECK: Validated by Arcium
    #[account(mut)]
    pub computation_account: AccountInfo<'info>,
    
    /// CHECK: Validated by Arcium
    pub computation_definition_account: AccountInfo<'info>,
    
    /// CHECK: Validated by Arcium
    #[account(mut)]
    pub mxe_account: Account<'info, MXEAccount>,
    
    /// CHECK: Validated by Arcium
    pub cluster: Account<'info, Cluster>,
    
    /// CHECK: Validated by Arcium
    #[account(
        mut,
        address = ARCIUM_FEE_POOL_ACCOUNT_ADDRESS,
    )]
    pub pool_account: Account<'info, FeePool>,
    
    /// CHECK: Validated by Arcium
    pub clock_account: Account<'info, ClockAccount>,
    
    pub system_program: Program<'info, System>,
    
    /// CHECK: Arcium program
    pub arcium_program: AccountInfo<'info>,
}

#[derive(Accounts)]
pub struct ProcessPrivatePaymentCallback<'info> {
    #[account(
        init,
        payer = buyer,
        space = 8 + PaymentRecord::LEN,
        seeds = [b"payment", computation_account.nonce.to_le_bytes().as_ref()],
        bump
    )]
    pub payment_record: Account<'info, PaymentRecord>,
    
    #[account(mut)]
    pub buyer: Signer<'info>,
    
    /// CHECK: Validated by callback macro
    pub computation_account: AccountInfo<'info>,
    
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct GetMerchantRevenue<'info> {
    #[account(mut)]
    pub merchant: Signer<'info>,
    
    /// CHECK: Validated by Arcium
    #[account(mut)]
    pub computation_account: AccountInfo<'info>,
    
    /// CHECK: Validated by Arcium  
    pub computation_definition_account: AccountInfo<'info>,
    
    /// CHECK: Validated by Arcium
    #[account(mut)]
    pub mxe_account: Account<'info, MXEAccount>,
    
    /// CHECK: Validated by Arcium
    pub cluster: Account<'info, Cluster>,
    
    /// CHECK: Validated by Arcium
    #[account(
        mut,
        address = ARCIUM_FEE_POOL_ACCOUNT_ADDRESS,
    )]
    pub pool_account: Account<'info, FeePool>,
    
    /// CHECK: Validated by Arcium
    pub clock_account: Account<'info, ClockAccount>,
    
    pub system_program: Program<'info, System>,
    
    /// CHECK: Arcium program
    pub arcium_program: AccountInfo<'info>,
}

#[derive(Accounts)]
pub struct GetMerchantRevenueCallback<'info> {
    #[account(
        mut,
        seeds = [b"revenue", merchant.key().as_ref()],
        bump
    )]
    pub revenue_account: Account<'info, RevenueAccount>,
    
    #[account(mut)]
    pub merchant: Signer<'info>,
    
    /// CHECK: Validated by callback macro
    pub computation_account: AccountInfo<'info>,
}

/// Encrypted payment record - all sensitive data remains private
#[account]
pub struct PaymentRecord {
    pub encrypted_confirmation: SharedEncryptedStruct<4>, // PaymentReceipt encrypted
    pub order_id: u64,
    pub timestamp: i64,
    pub buyer: Pubkey,
}

impl PaymentRecord {
    pub const LEN: usize = 200; // Encrypted struct + metadata
}

#[account]
pub struct RevenueAccount {
    pub encrypted_total: SharedEncryptedStruct<2>, // (u64 total, u32 count)
    pub merchant: Pubkey,
    pub last_updated: i64,
}

/// Payment receipt structure (encrypted)
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct PaymentReceipt {
    pub amount: u64,
    pub order_id: u64,
    pub merchant: Pubkey,
    pub timestamp: i64,
}

#[event]
pub struct PrivatePaymentEvent {
    pub order_id: u64,
    pub buyer: Pubkey,
    pub timestamp: i64,
    pub encrypted_confirmation: [u8; 32],
    pub nonce: [u8; 16],
}

#[event]
pub struct RevenueUpdateEvent {
    pub merchant: Pubkey,
    pub timestamp: i64,
}

#[error_code]
pub enum ErrorCode {
    #[msg("Payment computation failed")]
    PaymentComputationFailed,
    #[msg("Payment computation timed out")]
    PaymentTimeout,
    #[msg("Revenue computation failed")]
    RevenueComputationFailed,
    #[msg("Revenue computation timed out")]
    RevenueTimeout,
    #[msg("The cluster is not set")]
    ClusterNotSet,
}
