use arcis::prelude::*;

/// Encrypted computation: Process private payment
/// 
/// This runs inside Arcium's MPC network.
/// All inputs are encrypted, computation happens on encrypted data,
/// and outputs are re-encrypted for authorized parties only.
#[encrypted_instruction]
pub fn process_payment(
    encrypted_amount: u64,           // Encrypted payment amount
    encrypted_merchant_id: [u8; 32], // Encrypted merchant identifier
    order_id: u64,                   // Plain order ID (non-sensitive)
) -> (bool, PaymentReceipt) {
    // Validate payment amount (happens within MPC - result never exposed)
    let is_valid = encrypted_amount >= 1000; // Minimum 1000 lamports
    
    // Create payment receipt (stays encrypted)
    let receipt = PaymentReceipt {
        amount: encrypted_amount,
        order_id,
        merchant_hash: encrypted_merchant_id,
        success: is_valid,
        timestamp: 0, // Will be set by callback
    };
    
    (is_valid, receipt)
}

/// Payment receipt returned from MPC (encrypted)
#[derive(Clone, Debug, ArcisType)]
pub struct PaymentReceipt {
    pub amount: u64,
    pub order_id: u64,
    pub merchant_hash: [u8; 32],
    pub success: bool,
    pub timestamp: i64,
}

