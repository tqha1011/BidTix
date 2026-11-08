# Shortcuts for the commands used every day. Run `make` (or `make help`) to list them.
# Note: recipe lines below MUST start with a TAB character, not spaces.

MVN := ./mvnw

.DEFAULT_GOAL := help
.PHONY: help keys run run-local test test-one build clean db-up db-down psql

help: ## Show this list
	@grep -hE '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "} {printf "  %-10s %s\n", $$1, $$2}'

# ---- JWT signing keys -------------------------------------------------------
# Real files as targets, so they are only generated when missing and never overwritten.
keys/private.pem:
	mkdir -p keys
	openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $@
	chmod 600 $@

keys/public.pem: keys/private.pem
	openssl pkey -in $< -pubout -out $@

keys: keys/private.pem keys/public.pem ## Create the dev JWT keys if they do not exist yet

# ---- Run --------------------------------------------------------------------
run: keys ## Run the app (PostgreSQL is started from compose.yaml automatically)
	$(MVN) spring-boot:run

run-local: keys ## Run the app with a non-Secure refresh cookie, for testing over plain http
	BIDTIX_AUTH_REFRESHCOOKIE_SECURE=false $(MVN) spring-boot:run

# ---- Test and build ---------------------------------------------------------
test: ## Run all tests (BidtixBackendApplicationTests fails until Testcontainers is added)
	$(MVN) test

test-one: ## Run one test: make test-one T=AuthServiceTest  (or T=AuthServiceTest#login_issuesTokens...)
	@test -n "$(T)" || { echo "usage: make test-one T=ClassName[#method]"; exit 1; }
	$(MVN) test -Dtest='$(T)'

build: ## Build the jar without running tests
	$(MVN) clean package -DskipTests

clean: ## Delete build output (keys/ is not touched)
	$(MVN) clean

# ---- Database ---------------------------------------------------------------
db-up: ## Start only PostgreSQL from compose.yaml
	docker compose up -d --wait postgres

db-down: ## Stop and remove the PostgreSQL container (its data is not kept)
	docker compose down

psql: ## Open a psql shell in the running PostgreSQL container
	docker compose exec postgres psql -U myuser -d mydatabase
