# Mole --- Introduction deck.
#
# Targets:
#   make          build slides.pdf
#   make watch    auto-rebuild while editing
#   make clean    remove slides.pdf

TYPST ?= typst
SRC   := slides.typ
PDF   := slides.pdf

.PHONY: all watch clean

all: $(PDF)

$(PDF): $(SRC) $(wildcard chapters/*.typ) $(wildcard figures/*.typ)
	$(TYPST) compile $(SRC) $(PDF)

watch:
	$(TYPST) watch $(SRC) $(PDF)

clean:
	rm -f $(PDF)
