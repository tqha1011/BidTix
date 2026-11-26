# Shortcuts for the commands used every day. Run `make` (or `make help`) to list them.
# Note: recipe lines below MUST start with a TAB character, not spaces.

MVN := ./mvnw

.DEFAULT_GOAL := help
.PHONY: help keys run run-local run-seed test bench bench-gate bench-cancel load-test sale-day test-one build clean db-up db-down psql

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

run-seed: keys ## Like run-local, and fills an empty database with sample users, events and tickets (dev only)
	BIDTIX_SEED_ENABLED=true BIDTIX_AUTH_REFRESHCOOKIE_SECURE=false $(MVN) spring-boot:run

# ---- Test and build ---------------------------------------------------------
test: ## Run all tests (BidtixBackendApplicationTests fails until Testcontainers is added)
	$(MVN) test

test-one: ## Run one test: make test-one T=AuthServiceTest  (or T=AuthServiceTest#login_issuesTokens...)
	@test -n "$(T)" || { echo "usage: make test-one T=ClassName[#method]"; exit 1; }
	$(MVN) test -Dtest='$(T)'

bench: ## Run the anti-overselling benchmark (slow, Docker): make bench [ONLY=optimistic] [RUNS=3] [THREADS=500] [SCALE=5] [VIRTUAL=true] [RATIOS=0.1,1,10 [REQUESTS=20000]]
	$(MVN) test -Dgroups=benchmark -Dbenchmark.excludedGroups=none -Dtest='StrategyBenchmarkTest,RedisGateCrashTest' \
		$(if $(ONLY),-Dbench.only=$(ONLY)) $(if $(RUNS),-Dbench.runs=$(RUNS)) \
		$(if $(THREADS),-Dbench.threads=$(THREADS)) $(if $(SCALE),-Dbench.scale=$(SCALE)) \
		$(if $(VIRTUAL),-Dbench.virtual=$(VIRTUAL)) \
		$(if $(RATIOS),-Dbench.ratios=$(RATIOS)) $(if $(REQUESTS),-Dbench.requests=$(REQUESTS))

bench-gate: ## Benchmark the booking gate on the real booking flow, gate on then off: make bench-gate [RUNS=3] [THREADS=500] [SCALE=1] [POOL=10]
	for g in true false; do \
		$(MVN) test -Dgroups=benchmark -Dbenchmark.excludedGroups=none -Dtest=BookingGateBenchmarkTest \
			-Dbidtix.booking.gate-enabled=$$g $(if $(RUNS),-Dbench.runs=$(RUNS)) \
			$(if $(THREADS),-Dbench.threads=$(THREADS)) $(if $(SCALE),-Dbench.scale=$(SCALE)) \
			$(if $(POOL),-Dspring.datasource.hikari.maximum-pool-size=$(POOL)) || exit 1; \
	done

bench-cancel: ## Benchmark cancelling unpaid orders, 1 vs many orders per transaction: make bench-cancel [RUNS=3] [SCALE=1] [SIZES=1,10,100,500] [BOOKERS=16] [POOL=40]
	$(MVN) test -Dgroups=benchmark -Dbenchmark.excludedGroups=none -Dtest=OrderCancellationBenchmarkTest \
		-Dspring.datasource.hikari.maximum-pool-size=$(or $(POOL),40) $(if $(RUNS),-Dbench.runs=$(RUNS)) \
		$(if $(SCALE),-Dbench.scale=$(SCALE)) $(if $(SIZES),-Dbench.sizes=$(SIZES)) $(if $(BOOKERS),-Dbench.bookers=$(BOOKERS))

load-test: ## Load test the whole app over HTTP (Docker, k6): make load-test [PROFILE=smoke|read|book|mixed|users|concurrency|all]
	loadtest/run.sh $(or $(PROFILE),all)

sale-day: ## 30 minutes of simulated buyers and bidders, then data checks: make sale-day [MINUTES=30] [BASE=300] [PEAK=1000] [BIDDERS=150]
	loadtest/soak.sh

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
