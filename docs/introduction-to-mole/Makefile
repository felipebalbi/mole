# Mole --- Introduction deck.
#
# Targets:
#   make           build slides.pdf (default)
#   make notes     build slides-notes.pdf with speaker notes inlined
#   make all       build both
#   make watch     auto-rebuild slides.pdf while editing
#   make clean     remove built PDFs
#
# Set QUIET=1 to filter out the harmless "unknown font family"
# fallback warnings that typst emits when the cross-platform font
# stack (Inter, Helvetica Neue, Cascadia Mono, ...) isn't installed
# alongside the Aporetic family that the deck actually uses. The
# awk filter drops the whole multi-line warning block, not just
# the header line.

TYPST ?= typst
SRC   := slides.typ
PDF   := slides.pdf
NOTES := slides-notes.pdf

# Process substitution below needs bash, not /bin/sh.
SHELL := /bin/bash

DEPS  := $(SRC) $(wildcard chapters/*.typ) $(wildcard figures/*.typ) lib.typ

ifeq ($(QUIET),1)
FILTER := 2> >(awk ' \
    /^warning: unknown font family/ { skip = 1; next } \
    /^warning: please specify/      { skip = 1; next } \
    skip && /^$$/                   { skip = 0; next } \
    !skip                           { print > "/dev/stderr" } \
  ')
else
FILTER :=
endif

.PHONY: all notes watch clean

all: $(PDF) $(NOTES)

$(PDF): $(DEPS)
	$(TYPST) compile $(SRC) $(PDF) $(FILTER)

$(NOTES): $(DEPS)
	$(TYPST) compile --input notes=true $(SRC) $(NOTES) $(FILTER)

notes: $(NOTES)

watch:
	$(TYPST) watch $(SRC) $(PDF)

clean:
	rm -f $(PDF) $(NOTES)
