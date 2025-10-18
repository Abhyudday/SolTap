#!/bin/bash

# Arcium Private Payments Deployment Script
# Deploys Arcium program and starts bridge service

set -e

echo "🔒 Arcium Private Payments Deployment"
echo "======================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check prerequisites
echo "📋 Checking prerequisites..."

if ! command -v cargo &> /dev/null; then
    echo -e "${RED}❌ Cargo not found. Install Rust: https://rustup.rs/${NC}"
    exit 1
fi

if ! command -v node &> /dev/null; then
    echo -e "${RED}❌ Node.js not found. Install Node.js 18+${NC}"
    exit 1
fi

if ! command -v solana &> /dev/null; then
    echo -e "${RED}❌ Solana CLI not found. Install: https://docs.solana.com/cli/install-solana-cli-tools${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Prerequisites check passed${NC}"
echo ""

# Step 1: Build Arcium program
echo "🏗️  Step 1: Building Arcium program..."
cd arcium-program

if ! command -v arcium &> /dev/null; then
    echo -e "${YELLOW}⚠️  Arcium CLI not found. Installing...${NC}"
    cargo install --git https://github.com/arcium-labs/arcium arcium-cli || {
        echo -e "${RED}❌ Failed to install Arcium CLI${NC}"
        exit 1
    }
fi

echo "Building program..."
arcium build || {
    echo -e "${RED}❌ Program build failed${NC}"
    exit 1
}

echo -e "${GREEN}✅ Program built successfully${NC}"
echo ""

# Step 2: Deploy program (optional - comment out if not ready)
echo "🚀 Step 2: Deploy Arcium program? (y/n)"
read -r DEPLOY_PROGRAM

if [ "$DEPLOY_PROGRAM" = "y" ]; then
    echo "Select network:"
    echo "1) devnet"
    echo "2) mainnet"
    read -r NETWORK_CHOICE
    
    NETWORK="devnet"
    if [ "$NETWORK_CHOICE" = "2" ]; then
        NETWORK="mainnet"
        echo -e "${YELLOW}⚠️  WARNING: Deploying to MAINNET${NC}"
        echo "Are you sure? (yes/no)"
        read -r CONFIRM
        if [ "$CONFIRM" != "yes" ]; then
            echo "Deployment cancelled"
            exit 0
        fi
    fi
    
    echo "Deploying to $NETWORK..."
    arcium deploy --network $NETWORK || {
        echo -e "${RED}❌ Deployment failed${NC}"
        exit 1
    }
    
    echo -e "${GREEN}✅ Program deployed successfully${NC}"
    echo ""
    echo "⚠️  IMPORTANT: Update program ID in:"
    echo "  - arcium-client-bridge/.env"
    echo "  - app/src/main/java/com/solanatappay/arcium/ArciumPrivateRpcClient.kt"
    echo ""
else
    echo -e "${YELLOW}⚠️  Skipping program deployment${NC}"
    echo "Program can be deployed later with: cd arcium-program && arcium deploy --network devnet"
    echo ""
fi

cd ..

# Step 3: Setup bridge service
echo "🌉 Step 3: Setting up bridge service..."
cd arcium-client-bridge

if [ ! -f ".env" ]; then
    echo "Creating .env from template..."
    cp .env.example .env
    echo -e "${YELLOW}⚠️  Please edit arcium-client-bridge/.env with your configuration${NC}"
else
    echo ".env already exists"
fi

echo "Installing dependencies..."
npm install || {
    echo -e "${RED}❌ npm install failed${NC}"
    exit 1
}

echo "Building TypeScript..."
npm run build || {
    echo -e "${RED}❌ Build failed${NC}"
    exit 1
}

echo -e "${GREEN}✅ Bridge service built successfully${NC}"
echo ""

cd ..

# Step 4: Build Android app
echo "📱 Step 4: Building Android app..."

if [ ! -f "gradlew" ]; then
    echo -e "${YELLOW}⚠️  gradlew not found. Skipping Android build${NC}"
    echo "Build manually with: ./gradlew assembleDebug"
else
    ./gradlew assembleDebug || {
        echo -e "${RED}❌ Android build failed${NC}"
        exit 1
    }
    
    echo -e "${GREEN}✅ Android APK built successfully${NC}"
    echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
fi

echo ""
echo "======================================"
echo -e "${GREEN}🎉 Deployment Complete!${NC}"
echo "======================================"
echo ""
echo "📋 Next Steps:"
echo ""
echo "1. Configure Bridge Service:"
echo "   cd arcium-client-bridge"
echo "   nano .env  # Update ARCIUM_PROGRAM_ID and MXE config"
echo ""
echo "2. Start Bridge Service:"
echo "   npm start"
echo "   # Or for development: npm run dev"
echo ""
echo "3. Test Bridge:"
echo "   curl http://localhost:3000/health"
echo ""
echo "4. Install Android App:"
echo "   adb install app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "5. Configure App:"
echo "   - Open app settings"
echo "   - Set bridge URL to: http://YOUR_SERVER_IP:3000"
echo "   - For emulator use: http://10.0.2.2:3000"
echo ""
echo "📚 Documentation:"
echo "   - ARCIUM_INTEGRATION.md - Complete integration guide"
echo "   - arcium-client-bridge/README.md - Bridge API docs"
echo "   - arcium-program/README.md - Program documentation"
echo ""
echo "🔒 Privacy-Enabled Payments, Powered by Arcium"
echo ""

