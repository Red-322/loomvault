# =============================================================================================
#  LoomVault — common developer tasks.
#  Recipes are indented with a TAB (required by make).
# =============================================================================================

# Tooling (override on the command line, e.g. `make test MVN=./mvnw`).
MVN     ?= mvn
COMPOSE ?= docker compose -f deploy/docker-compose.yml

# Executable fat jar produced by the server module (<finalName>loomvault</finalName>).
JAR := loomvault-server/target/loomvault.jar

.DEFAULT_GOAL := help
.PHONY: help build test package verify up down logs ps run-standalone clean

help: ## Show this help
	@echo "LoomVault targets:"
	@echo "  make build           Compile all modules and install them locally (skip tests)"
	@echo "  make test            Run the full test suite (mvn verify)"
	@echo "  make package         Build the executable jar (skip tests)"
	@echo "  make up              Build image and start postgres + redis + app"
	@echo "  make down            Stop the stack and remove containers"
	@echo "  make logs            Follow the application logs"
	@echo "  make ps              Show stack status"
	@echo "  make run-standalone  Run the jar with the zero-dependency standalone profile"
	@echo "  make clean           Remove all build output"

build: ## Compile and install all modules (skip tests)
	$(MVN) -B -ntp -DskipTests install

test: ## Run the full test suite
	$(MVN) -B -ntp verify

package: ## Build the executable jar (skip tests)
	$(MVN) -B -ntp -DskipTests package

verify: ## Full verify build
	$(MVN) -B -ntp verify

up: ## Start the whole stack via docker compose
	$(COMPOSE) up --build

down: ## Stop the stack
	$(COMPOSE) down

logs: ## Follow application logs
	$(COMPOSE) logs -f loomvault

ps: ## Show stack status
	$(COMPOSE) ps

run-standalone: ## Run locally with H2 + in-memory cache (no Docker required)
	$(MVN) -B -ntp -DskipTests package -pl loomvault-server -am
	java -jar $(JAR) --spring.profiles.active=standalone

clean: ## Remove build output
	$(MVN) -B -ntp clean
