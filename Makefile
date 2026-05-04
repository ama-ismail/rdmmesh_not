.DEFAULT_GOAL := help

# rdmmesh — developer entrypoints. All Java work runs through bin/mvn,
# which executes Maven inside the official maven:3.9-eclipse-temurin-21
# image so JDK/Maven are not required on the host machine.

MVN          ?= ./bin/mvn
COMPOSE_FILE ?= docker/docker-compose.yml
COMPOSE      ?= docker compose -f $(COMPOSE_FILE)

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_.-]+:.*?## / {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: build
build: ## Compile + package every Maven module
	$(MVN) -T 1C clean package

.PHONY: compile
compile: ## Compile only (skips tests)
	$(MVN) -T 1C -DskipTests compile

.PHONY: test
test: ## Run unit tests
	$(MVN) -T 1C test

.PHONY: verify
verify: ## Full build incl. ArchUnit + integration tests
	$(MVN) -T 1C verify

.PHONY: format
format: ## Apply Spotless code formatting
	$(MVN) spotless:apply

.PHONY: format-check
format-check: ## Verify Spotless formatting
	$(MVN) spotless:check

.PHONY: codegen
codegen: ## Run JSON Schema codegen for rdmmesh-spec
	$(MVN) -pl rdmmesh-spec -am generate-sources

.PHONY: up
up: ## Start the dev compose stack (postgres, keycloak, rdmmesh-service)
	$(COMPOSE) up -d

.PHONY: down
down: ## Stop the dev compose stack
	$(COMPOSE) down

.PHONY: logs
logs: ## Tail compose logs
	$(COMPOSE) logs -f --tail=200

.PHONY: psql
psql: ## Open psql against the dev database
	$(COMPOSE) exec postgres psql -U rdmmesh -d rdmmesh

.PHONY: ui
ui: ## Run the React dev server
	cd rdmmesh-ui && npm run dev

.PHONY: clean
clean: ## Remove build artifacts (Maven target/ and Vite dist/)
	$(MVN) clean
	rm -rf rdmmesh-ui/dist rdmmesh-ui/node_modules/.vite
