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
sim: sim-config sim-opendrain sim-baud-gen sim-rx-sync \
     sim-tx-shiftreg sim-rx-shiftreg sim-tx-fsm sim-rx-fsm \
     sim-uart-tx sim-uart-rx sim-uart ## Run all simulations

# Per-block sim / gen targets land here once their hint blocks in
# TODO.md flip from 🔲 to ✅. The naming contract is fixed:
#
#   sim-config           src/sim/MoleConfigSim.scala         (Step  1)
#   sim-opendrain        src/sim/OpenDrainBusSim.scala       (Step  2)
#   sim-baud-gen         src/sim/BaudGeneratorSim.scala      (Step  4)
#   sim-rx-sync          src/sim/RxSyncSim.scala             (Step  4)
#   sim-tx-shiftreg      src/sim/TxShiftRegSim.scala         (Step  4)
#   sim-rx-shiftreg      src/sim/RxShiftRegSim.scala         (Step  4)
#   sim-tx-fsm           src/sim/TxFsmSim.scala              (Step  4)
#   sim-rx-fsm           src/sim/RxFsmSim.scala              (Step  4)
#   sim-uart-tx          src/sim/UartTxSim.scala             (Step  4)
#   sim-uart-rx          src/sim/UartRxSim.scala             (Step  4)
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

sim-config: ## Run MoleConfig spec-floor audit (Step 1)
	$(SBT) -batch "runMain $(PACKAGE).MoleConfigSim"

sim-opendrain: ## Run OpenDrainBus wired-AND audit (Step 2)
	$(SBT) -batch "runMain $(PACKAGE).OpenDrainBusSim"

sim-baud-gen: ## Run BaudGenerator DDS audit (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).BaudGeneratorSim"

sim-rx-sync: ## Run RxSync metastability audit (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).RxSyncSim"

sim-tx-shiftreg: ## Run TxShiftReg sim (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).TxShiftRegSim"

sim-rx-shiftreg: ## Run RxShiftReg sim (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).RxShiftRegSim"

sim-tx-fsm: ## Run TxFsm sim (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).TxFsmSim"

sim-rx-fsm: ## Run RxFsm sim (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).RxFsmSim"

sim-uart-tx: ## Run UartTx black-box sim (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).UartTxSim"

sim-uart-rx: ## Run UartRx black-box sim (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).UartRxSim"

sim-uart: ## Run UART TX -> RX loopback (Step 4)
	$(SBT) -batch "runMain $(PACKAGE).UartSim"

# sim-spram: ## Run SPRAM controller sim (lands with Step 6)
# 	$(SBT) -batch "runMain $(PACKAGE).SpramControllerSim"

# sim-isa: ## Round-trip the 11-opcode encoder/decoder (lands with Step 7)
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

.PHONY: all sim sim-config sim-opendrain sim-baud-gen sim-rx-sync \
        sim-tx-shiftreg sim-rx-shiftreg sim-tx-fsm sim-rx-fsm \
        sim-uart-tx sim-uart-rx sim-uart \
        waves flash clean distclean help
