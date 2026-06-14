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
REVISION_PATCH := 1
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
V      := $(GEN_DIR)/$(TOP).v
JSON   := $(GEN_DIR)/$(TOP).json
ASC    := $(GEN_DIR)/$(TOP).asc
BIN    := $(GEN_DIR)/$(TOP).bin
REPORT := $(GEN_DIR)/$(TOP).report.json

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
#
# Verde target Fmax is **24 MHz** (engineClk). The v0 engine ran at
# 24 MHz on UP5K silicon (Step 17 closed at this clock); the v0.2
# rework attempted 48 MHz via the 5-stage pipeline + perf X.1..X.5
# but the UP5K-SG48 plateaued at ~32 MHz best across 10 seeds. The
# call was made to return to 24 MHz to stay safely off the
# compliance edge rather than chase a moving Fmax target.
# uartClk is 24 MHz too (1:1 ratio, both off PLLOUTGLOBAL).
#
# `--seed 4` is the v0.2 silicon-validated pin, chosen because it
# gives good Fmax margin above the 24 MHz engineClk target on the
# current v0.2 netlist (5-stage pipeline, 32-bit instructions,
# sentinel-fill FSM, larger SPRAM wiring than v0). Earlier history
# (v0 bring-up Step 16) pinned `--seed 3` because v0's much smaller
# netlist had only ~37 % of seeds passing timing at 24 MHz and
# seed 3 was the lowest-numbered passing seed; that pin survived
# into early v0.2 but became marginal as the netlist grew --- seed
# 3 was observed achieving 23.93 MHz (FAIL by 70 kHz) on a
# post-sentinel-fill bitstream. A small seed sweep (1..10) at
# 24 MHz against the current netlist picked seed 4 as the new
# pin with comfortable margin. Re-sweep if the netlist shifts
# materially again and the achieved Fmax drops near the 24 MHz
# floor.
$(ASC): $(JSON) $(PCF)
	$(NEXTPNR)              \
		--up5k          \
		--package sg48  \
		--pcf $(PCF)    \
		--json $<       \
		--asc $@        \
		--freq 24       \
		--seed 4

# 4. Pack (ASC -> BIN)
$(BIN): $(ASC)
	$(ICEPACK) $< $@

# Interactive place-and-route viewer (requires nextpnr-ice40 built with
# `-DBUILD_GUI=ON` — true on most Linux distributions and oss-cad-suite
# builds; NOT true of the default macOS Homebrew build). Loads the same
# netlist and constraints as the regular synth flow, then opens the Qt
# GUI so you can browse the placed cells, routed nets, and timing paths.
# Click on a cell to highlight its connections; click on a net to trace
# it across the fabric. The "Timing" pane shows the critical paths
# ranked by slack — single-click any line to highlight the corresponding
# route on the chip layout.
#
# The .asc output goes to /tmp/ so it doesn't clobber the regular
# bitstream build; the GUI is purely diagnostic.
gui: $(JSON) $(PCF) ## Open nextpnr GUI on the v0.2 design (Linux/oss-cad-suite)
	$(NEXTPNR)                        \
		--up5k                    \
		--package sg48            \
		--pcf $(PCF)              \
		--json $<                 \
		--asc /tmp/$(TOP).gui.asc \
		--freq 24                 \
		--seed 4                  \
		--gui

# Machine-readable timing + utilisation report. Useful for tracking
# Fmax across seeds and for scripted critical-path extraction.
# Output: gen/<TOP>.report.json
$(REPORT): $(JSON) $(PCF)
	$(NEXTPNR)                          \
		--up5k                      \
		--package sg48              \
		--pcf $(PCF)                \
		--json $<                   \
		--asc gen/$(TOP).report.asc \
		--freq 24                   \
		--seed 4                    \
		--report $@                 \
		--detailed-timing-report

report: $(REPORT) ## Generate nextpnr report (gen/<TOP>.report.json)

# Aggregate sim target. Per-block sim targets land as the project
# grows (see TODO.md). Keep this list and .PHONY in sync.
#
# Phase C status: C.10 closed the host-side gap (sticky-flag
# observability test); C.11.b re-ported the v0 MoleTopSim family
# from src/attic/ as v0.2 SpinalSim end-to-end coverage. The
# attic/ stash files remain as intent reference; the live v0.2
# sims are src/sim/MoleTop{,FlowControl,CtsViolation}Sim.scala.
#
# Post-Phase-F: sim-top / sim-top-flow-control / sim-top-cts-violation
# now pass end-to-end (REVISION + CAPTURE in the ring let the host
# decoder round-trip correctly). They are wired into the aggregate
# target below.
sim: sim-config sim-opendrain sim-baud-gen sim-rx-sync \
     sim-tx-shiftreg sim-rx-shiftreg sim-tx-fsm sim-rx-fsm \
     sim-uart-tx sim-uart-rx sim-uart sim-spram sim-isa \
     sim-engine-smoke sim-pll \
     sim-crc sim-iobuf sim-loader-fsm sim-drainer-fsm \
     sim-symbol-decoder-contention \
     sim-engine-emit-bit-data-hold \
     sim-engine-stretch-role \
     sim-engine-jmp-boundary \
     sim-engine-stretch sim-engine-target sim-engine-full \
     sim-engine-data \
     sim-regfile sim-engine-pipeline \
     sim-top sim-top-flow-control sim-top-cts-violation ## Run all simulations (Phase C in flight)

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
#   sim-engine-target    src/sim/BitCycleEngineTargetSim.scala (Step 19)
#   sim-pll              src/sim/MolePllUp5kSim.scala        (Step 13)
#   sim-crc              src/sim/Crc16XmodemSim.scala        (Step 13)
#   sim-iobuf            src/sim/MoleIoBufUp5kSim.scala      (Step 13)
#   sim-loader-fsm       src/sim/MoleLoaderFsmSim.scala      (Step 13)
#   sim-drainer-fsm      src/sim/MoleDrainerFsmSim.scala      (Step 13)
#   sim-top              src/sim/MoleTopSim.scala            (Step 14)
#   sim-regfile          src/sim/RegFileSim.scala            (Phase C.5)
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

sim-spram: ## Run SPRAM controller sim (Step 6)
	$(SBT) -batch "runMain $(PACKAGE).SpramControllerSim"

sim-isa: ## Round-trip the 15-opcode encoder/decoder (Step 7)
	$(SBT) -batch "runMain $(PACKAGE).InstructionSim"

sim-engine-smoke: ## EMIT_BIT smoke: i3c-OD vs i3c-PP byte trace (Step 9)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineSmokeSim"

sim-engine-full: ## Full ISA coverage of BitCycleEngineCore (Step 12)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineSim"

sim-engine-data: ## DATA-group ISA coverage (Phase C.9)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineDataSim"

sim-engine-target: ## Target-role coverage of BitCycleEngineCore (Step 19)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineTargetSim"

sim-pll: ## Run MolePllUp5k bypass + BlackBox elaboration sim (Step 13)
	$(SBT) -batch "runMain $(PACKAGE).MolePllUp5kSim"

sim-crc: ## Run Crc16Xmodem reference + DUT audit (Step 13)
	$(SBT) -batch "runMain $(PACKAGE).Crc16XmodemSim"

sim-iobuf: ## Run MoleIoBufUp5k bypass + BlackBox elaboration sim (Step 13)
	$(SBT) -batch "runMain $(PACKAGE).MoleIoBufUp5kSim"

sim-loader-fsm: ## Run MoleLoaderFsm frame-parser audit (Step 13)
	$(SBT) -batch "runMain $(PACKAGE).MoleLoaderFsmSim"

sim-drainer-fsm: ## Run MoleDrainerFsm result-ring sweep audit (Step 13)
	$(SBT) -batch "runMain $(PACKAGE).MoleDrainerFsmSim"

sim-top: ## End-to-end MoleTop sim: UART load -> engine -> UART results (Step 14)
	$(SBT) -batch "runMain $(PACKAGE).MoleTopSim"

sim-top-flow-control: ## End-to-end MoleTop sim: UART RTS#/CTS# hardware flow control
	$(SBT) -batch "runMain $(PACKAGE).MoleTopFlowControlSim"

sim-symbol-decoder-contention: ## Exhaustive (BUS_MODE, tx_symbol) no-contention sweep
	$(SBT) -batch "runMain $(PACKAGE).SymbolDecoderContentionSim"

sim-engine-jmp-boundary: ## JMP out-of-range guard regression (F-FPGA-004)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineJmpBoundarySim"

sim-top-cts-violation: ## CTS#-violation sticky observable (F-FPGA-007)
	$(SBT) -batch "runMain $(PACKAGE).MoleTopCtsViolationSim"

sim-engine-emit-bit-data-hold: ## EMIT_BIT inter-bit tHD;DAT hold (MCXA266/RT685 regression)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineEmitBitDataHoldSim"

sim-engine-stretch: ## Stretch-aware EMIT_BIT timing scenarios (Q1->Q2 guard)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineStretchSim"

sim-engine-stretch-role: ## Stretch-aware EMIT_BIT role-gating (Controller vs Target)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineStretchRoleSim"

sim-regfile: ## 8x32-bit register file: storage, W-to-R bypass, load-use stall (Phase C.5)
	$(SBT) -batch "runMain $(PACKAGE).RegFileSim"

sim-engine-pipeline: ## 5-stage pipeline scaffold: HALT, trap, fetch sequencing (Phase C.6)
	$(SBT) -batch "runMain $(PACKAGE).EnginePipelineSim"

# gen target -- the canonical Verilog entrypoint is MoleTopVerilog;
# generated module is MoleTop. The generated file lives at gen/MoleTop.v
# and is gitignored.

generate: | $(GEN_DIR) ## Generate gen/MoleTop.v from MoleTopVerilog (Step 15)
	$(SBT) -batch "runMain $(PACKAGE).$(TOP)Verilog"

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
        sim-uart-tx sim-uart-rx sim-uart sim-spram sim-isa \
        sim-engine-smoke sim-engine-full sim-engine-target sim-pll \
        sim-engine-data \
        sim-crc sim-iobuf sim-loader-fsm sim-drainer-fsm sim-top \
        sim-top-flow-control sim-symbol-decoder-contention \
        sim-engine-jmp-boundary sim-top-cts-violation \
        sim-engine-emit-bit-data-hold \
        sim-engine-stretch sim-engine-stretch-role \
        sim-regfile sim-engine-pipeline \
        gen waves flash clean distclean help \
        gui report
