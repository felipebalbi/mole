# Mole --- Introduction deck.
#
# Targets:
#   make           build slides.pdf (default)
#   make notes     build slides-notes.pdf with speaker notes inlined
#   make dark      build slides-dark.pdf in melissa-dark
#   make all       build all three
#   make watch     auto-rebuild slides.pdf while editing
#   make clean     remove built PDFs

TYPST ?= typst
SRC   := slides.typ
PDF   := slides.pdf
NOTES := slides-notes.pdf
DARK  := slides-dark.pdf

# Root one level up (= docs/) so the template under
# ../presentation-template/ is reachable. Typst forbids imports
# that escape the project root.
ROOT  := ..

DEPS  := $(SRC) $(wildcard chapters/*.typ) $(wildcard figures/*.typ) \
         ../presentation-template/lib.typ \
         ../presentation-template/theme.typ

.PHONY: all notes dark watch clean

all: $(PDF) $(NOTES) $(DARK)

$(PDF): $(DEPS)
	$(TYPST) compile --root $(ROOT) $(SRC) $(PDF)

$(NOTES): $(DEPS)
	$(TYPST) compile --root $(ROOT) --input notes=true $(SRC) $(NOTES)

$(DARK): $(DEPS)
	$(TYPST) compile --root $(ROOT) --input theme=dark $(SRC) $(DARK)

notes: $(NOTES)
dark:  $(DARK)

watch:
	$(TYPST) watch --root $(ROOT) $(SRC) $(PDF)

clean:
	rm -f $(PDF) $(NOTES) $(DARK)
