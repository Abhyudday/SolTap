#!/bin/bash

# Solana Tap & Pay - APK Build Script
# Quick script to build and locate the APK

set -e  # Exit on error

echo "=========================================="
echo "  Solana Tap & Pay - APK Builder"
echo "=========================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if we're in the right directory
if [ ! -f "settings.gradle.kts" ]; then
    echo -e "${RED}Error: Not in project root directory${NC}"
    echo "Please run this script from: /Users/abhyuday/Desktop/jai baba baijnath"
    exit 1
fi

# Make gradlew executable
chmod +x gradlew

# Clean build (optional)
echo -e "${YELLOW}Cleaning previous build...${NC}"
./gradlew clean

# Build debug APK
echo ""
echo -e "${YELLOW}Building debug APK...${NC}"
./gradlew assembleDebug

# Check if build succeeded
if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}=========================================="
    echo "  ✓ Build Successful!"
    echo "==========================================${NC}"
    echo ""
    echo -e "${GREEN}APK Location:${NC}"
    echo "  app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    
    # Show APK size
    if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        APK_SIZE=$(du -h "app/build/outputs/apk/debug/app-debug.apk" | cut -f1)
        echo -e "${GREEN}APK Size:${NC} $APK_SIZE"
        echo ""
    fi
    
    echo -e "${YELLOW}Next Steps:${NC}"
    echo "  1. Install on device:"
    echo "     adb install app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "  2. Or copy APK to your phone and install manually"
    echo ""
    echo "  3. See QUICK_START.md for testing instructions"
    echo ""
else
    echo ""
    echo -e "${RED}=========================================="
    echo "  ✗ Build Failed"
    echo "==========================================${NC}"
    echo ""
    echo "Check the error messages above for details"
    exit 1
fi

