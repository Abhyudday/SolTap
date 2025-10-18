#!/bin/bash

# Install Solana Tap & Pay with Convert to Fiat feature
# This script will uninstall the old app and install the new one

set -e

echo "=========================================="
echo "  Installing Solana Tap & Pay"
echo "  with Convert to Fiat Feature"
echo "=========================================="
echo ""

# Check if device is connected
echo "Checking for connected devices..."
DEVICES=$(adb devices | grep -v "List" | grep "device" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo "❌ No Android device found!"
    echo ""
    echo "Please:"
    echo "  1. Connect your Android device via USB"
    echo "  2. Enable USB Debugging in Developer Options"
    echo "  3. Run this script again"
    echo ""
    echo "Or install manually:"
    echo "  - Copy app/build/outputs/apk/debug/app-debug.apk to your phone"
    echo "  - Open the file and install"
    exit 1
fi

echo "✓ Device connected!"
echo ""

# Uninstall old version
echo "Uninstalling old version (if exists)..."
adb uninstall com.solanatappay 2>/dev/null || echo "  (No previous version found)"
echo ""

# Install new version
echo "Installing new version with Convert to Fiat button..."
adb install app/build/outputs/apk/debug/app-debug.apk

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "  ✅ Installation Successful!"
    echo "=========================================="
    echo ""
    echo "📱 How to use the Convert to Fiat feature:"
    echo "  1. Open Solana Tap Pay"
    echo "  2. Select Merchant mode"
    echo "  3. Click Dashboard button (top right)"
    echo "  4. Click '💰 Convert to Fiat' button"
    echo ""
    echo "The button will:"
    echo "  • Get your wallet address"
    echo "  • Open Onramp offramp widget"
    echo "  • Pre-fill your details"
    echo "  • Let you convert SOL to fiat"
    echo ""
else
    echo ""
    echo "❌ Installation failed!"
    echo "Try installing manually from:"
    echo "  app/build/outputs/apk/debug/app-debug.apk"
    exit 1
fi
