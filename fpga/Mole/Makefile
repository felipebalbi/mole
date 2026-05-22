PACKAGE  := mole
TOP      := MoleTop
SRC_DIR  := src
GEN_DIR  := gen
SIM_DIR  := simWorkspace
PCF      := icebreaker.pcf

# IP revision -- single source of truth for the REVISION word the
# bit engine reports back to the host. Threaded into every sbt
# invocation that elaborates the engine (REVISION_DEFS below) and
# read on the Scala side via `sys.props` in `src/hw/Revision.scala`.
# Keep the defaults there in lockstep with these values.
REVISION_MAJOR := 0
REVISION_MINOR := 1
REVISION_PATCH := 0
REVISION_DEFS  := -Drevision.major=$(REVISION_MAJOR) \
                  -Drevision.minor=$(REVISION_MINOR) \
                  -Drevision.patch=$(REVISION_PATCH)

# Tools
SBT     := sbt $(REVISION_DEFS)
YOSYS   := yosys
NEXTPNR := nextpnr-ice40
ICEPACK := icepack
ICEPROG := iceprog
GTKWAVE := gtkwave

# Derived files. The Verilog entrypoint is `MoleTopVerilog`, the
# generated module is `MoleTop`.
V    := $(GEN_DIR)/$(TOP).v
JSON := $(GEN_DIR)/$(TOP).json
ASC  := $(GEN_DIR)/$(TOP).asc
BIN  := $(GEN_DIR)/$(TOP).bin

# Hardware sources only -- sim sources don't affect Verilog
# generation, so excluding them prevents needless rebuilds when
# iterating on tests.
HW_SRCS := $(shell find $(SRC_DIR)/hw -name '*.scala' 2>/dev/null)

# Default target
.DEFAULT_GOAL := all

all: $(BIN) ## Build flashable bitstream (gen/<TOP>.bin)

$(GEN_DIR):
	mkdir -p $(GEN_DIR)

# 1. Generate Verilog (Spinal -> Verilog)
$(V): $(HW_SRCS) | $(GEN_DIR)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

# 2. Synthesize (Verilog -> JSON)
$(JSON): $(V)
	$(YOSYS) -p "synth_ice40 -top $(TOP) -json $@" $<

# 3. Place & route (JSON -> ASC) -- iCE40 UP5K, SG48 package.
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR) \
		--up5k          \
		--package sg48  \
		--pcf $(PCF)    \
		--json $< \
		--asc $@  \
		--freq 12

# 4. Pack (ASC -> BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

# Aggregate sim target. Per-block sim targets land as the project
# grows (see TODO.md). Keep this list and .PHONY in sync.
sim: ## Run all simulations
	@echo "no sims wired yet -- add sim-<block> targets per TODO.md"

# Per-block sim / gen targets land here once their hint blocks in
# TODO.md flip from 🔲 to ✅. The naming contract is fixed:
#
#   sim-config           src/sim/MoleConfigSim.scala         (Step  1)
#   sim-opendrain        src/sim/OpenDrainBusSim.scala       (Step  2)
#   sim-uart             src/sim/UartSim.scala               (Step  4)
#   sim-spram            src/sim/SpramControllerSim.scala    (Step  6)
#   sim-isa              src/sim/InstructionSim.scala        (Step  7)
#   sim-engine-smoke     src/sim/BitCycleEngineSmokeSim.scala (Step  9)
#   sim-engine-full      src/sim/BitCycleEngineSim.scala     (Step 12)
#   sim-top              src/sim/MoleTopSim.scala            (Step 14)
#
# Declared here in advance so the build contract is visible from
# `make help` and code review can verify each step lands what it
# promised. Each will be uncommented as its step ships.

# sim-config: ## Run MoleConfig spec-floor audit (lands with Step 1)
# 	$(SBT) -batch "runMain $(PACKAGE).MoleConfigSim"

# sim-opendrain: ## Run OpenDrainBus wired-AND sim (lands with Step 2)
# 	$(SBT) -batch "runMain $(PACKAGE).OpenDrainBusSim"

# sim-uart: ## Run UART RX/TX sim (lands with Step 4)
# 	$(SBT) -batch "runMain $(PACKAGE).UartSim"

# sim-spram: ## Run SPRAM controller sim (lands with Step 6)
# 	$(SBT) -batch "runMain $(PACKAGE).SpramControllerSim"

# sim-isa: ## Round-trip the 10-opcode encoder/decoder (lands with Step 7)
# 	$(SBT) -batch "runMain $(PACKAGE).InstructionSim"

# sim-engine-smoke: ## EMIT_BIT smoke: drive 0x55 onto sim bus (lands with Step 9)
# 	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineSmokeSim"

# sim-engine-full: ## Full ISA coverage (lands with Step 12)
# 	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineSim"

# sim-top: ## End-to-end MoleTop sim: UART load -> engine -> UART results (Step 14)
# 	$(SBT) -batch "runMain $(PACKAGE).MoleTopSim"

# gen target -- the canonical Verilog entrypoint is MoleTopVerilog;
# generated module is MoleTop. Lands with Step 15.

# gen: | $(GEN_DIR) ## Generate MoleTop.v in gen/ (lands with Step 15)
# 	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

waves: ## Open the most recent simulation VCD in gtkwave
	@vcd=$$(find $(SIM_DIR) -name '*.vcd' -printf '%T@ %p\n' 2>/dev/null \
		| sort -nr | head -1 | cut -d' ' -f2-); \
	if [ -z "$$vcd" ]; then \
		echo "no .vcd found under $(SIM_DIR)/ -- run a sim first"; \
		exit 1; \
	fi; \
	echo "opening $$vcd"; \
	$(GTKWAVE) "$$vcd" &

flash: $(BIN) ## Flash bitstream to the iCEbreaker
	$(ICEPROG) $<

clean: ## Remove generated artefacts (gen/, simWorkspace/)
	rm -rf $(GEN_DIR) $(SIM_DIR)

distclean: clean ## clean + nuke sbt build state (target/, project/target/)
	rm -rf target project/target project/project

help: ## Show this help (every target with a ## description)
	@awk 'BEGIN { FS = ":.*## "; printf "Available targets:\n\n" } \
		/^[a-zA-Z0-9_.-]+:.*## / { printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2 }' \
		$(MAKEFILE_LIST)

.PHONY: all sim waves flash clean distclean help
