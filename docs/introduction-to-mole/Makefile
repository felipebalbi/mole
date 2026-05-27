# Mole --- Introduction deck.
#
# Targets:
#   make           build slides.pdf (default)
#   make notes     build slides-notes.pdf with speaker notes inlined
#   make all       build both
#   make watch     auto-rebuild slides.pdf while editing
#   make clean     remove built PDFs

TYPST ?= typst
SRC   := slides.typ
PDF   := slides.pdf
NOTES := slides-notes.pdf

DEPS  := $(SRC) $(wildcard chapters/*.typ) $(wildcard figures/*.typ) lib.typ

.PHONY: all notes watch clean

all: $(PDF) $(NOTES)

$(PDF): $(DEPS)
	$(TYPST) compile $(SRC) $(PDF)

$(NOTES): $(DEPS)
	$(TYPST) compile --input notes=true $(SRC) $(NOTES)

notes: $(NOTES)

watch:
	$(TYPST) watch $(SRC) $(PDF)

clean:
	rm -f $(PDF) $(NOTES)
