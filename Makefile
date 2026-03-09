.PHONY: all build test lint clean help \
       signer companion-core companion-tui \
       companion-web companion-desktop companion-extension companion-mobile \
       test-signer test-companion-core test-companion-tui \
       lint-signer lint-companion-core lint-companion-tui \
       lint-companion-web lint-companion-desktop lint-companion-extension lint-companion-mobile \
       size-check verify-deps reproducible-build emulator setup-tools

SIGNER_DIR   := signer
CORE_DIR     := companion/core
TUI_DIR      := companion/tui
WEB_DIR      := companion/web
DESKTOP_DIR  := companion/desktop
EXT_DIR      := companion/extension
MOBILE_DIR   := companion/mobile
SIGNER_JAVA  := /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

all: build ## Build everything

build: signer companion-core companion-tui companion-web companion-desktop companion-extension ## Build all targets

signer: ## Build signer MIDlet (requires JDK 8)
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant clean build

companion-core: ## Build companion Rust core library
	cd $(CORE_DIR) && cargo build

companion-tui: companion-core ## Build companion TUI binary
	cd $(TUI_DIR) && cargo build

companion-web: ## Build companion web app
	cd $(WEB_DIR) && pnpm install && pnpm build

companion-desktop: ## Build companion desktop app (TypeScript only)
	cd $(DESKTOP_DIR) && pnpm install && pnpm build

companion-extension: ## Build companion Chrome extension
	cd $(EXT_DIR) && pnpm install && pnpm build

companion-mobile: ## Type-check companion mobile app
	cd $(MOBILE_DIR) && pnpm install && pnpm run build:check

test: test-signer test-companion-core test-companion-tui ## Run all tests

test-signer: ## Run signer test suite
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant test

test-companion-core: ## Run companion core tests
	cd $(CORE_DIR) && cargo test

test-companion-tui: ## Run companion TUI tests
	cd $(TUI_DIR) && cargo test

lint: lint-signer lint-companion-core lint-companion-tui \
      lint-companion-web lint-companion-desktop lint-companion-extension lint-companion-mobile ## Run all linters

lint-signer: ## Run signer static analysis
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant check

lint-companion-core: ## Run clippy on companion core
	cd $(CORE_DIR) && cargo clippy -- -D warnings

lint-companion-tui: ## Run clippy on companion TUI
	cd $(TUI_DIR) && cargo clippy -- -D warnings

lint-companion-web: ## Lint companion web app
	cd $(WEB_DIR) && pnpm lint

lint-companion-desktop: ## Lint companion desktop app
	cd $(DESKTOP_DIR) && pnpm lint

lint-companion-extension: ## Lint companion Chrome extension
	cd $(EXT_DIR) && pnpm lint

lint-companion-mobile: ## Lint companion mobile app
	cd $(MOBILE_DIR) && pnpm lint

size-check: ## Check signer JAR stays within device budget
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant size-check

verify-deps: ## Verify signer dependency JAR hashes
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant verify-deps

reproducible-build: ## Build signer with dep verification and output hash
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant reproducible-build

clean: ## Clean all build artifacts
	cd $(SIGNER_DIR) && JAVA_HOME=$(SIGNER_JAVA) ant clean || true
	cd $(CORE_DIR) && cargo clean
	cd $(TUI_DIR) && cargo clean
	rm -rf $(WEB_DIR)/.next $(WEB_DIR)/out
	rm -rf $(DESKTOP_DIR)/dist
	rm -rf $(EXT_DIR)/dist
	rm -rf $(MOBILE_DIR)/.expo

emulator: signer ## Launch signer MIDlet in FreeJ2ME-Plus emulator
	java -jar tools/freej2me-plus/build/freej2me.jar \
		"file://$(shell pwd)/signer/dist/BurnerWallet.jar"

setup-tools: ## Download ProGuard, Checkstyle, and build FreeJ2ME-Plus emulator
	@echo "Downloading ProGuard 7.8.2..."
	curl -L -o /tmp/proguard-7.8.2.zip \
		https://github.com/Guardsquare/proguard/releases/download/v7.8.2/proguard-7.8.2.zip
	unzip -o /tmp/proguard-7.8.2.zip -d tools/
	ln -sf proguard-7.8.2 tools/proguard
	@echo "Downloading Checkstyle 10.21.4..."
	curl -L -o tools/checkstyle-10.21.4-all.jar \
		https://github.com/checkstyle/checkstyle/releases/download/checkstyle-10.21.4/checkstyle-10.21.4-all.jar
	@echo "Building FreeJ2ME-Plus emulator..."
	test -d tools/freej2me-plus || git clone https://github.com/TASEmulators/freej2me-plus.git tools/freej2me-plus
	cd tools/freej2me-plus && JAVA_HOME=$(SIGNER_JAVA) ant
	@echo "Tools ready."
