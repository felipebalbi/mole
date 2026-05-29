// theme.typ -- color tokens for the Mole presentation template.
//
// Two themes ship out of the box, both lifted from Protesilaos
// Stavrou's ef-themes (https://github.com/protesilaos/ef-themes):
//
//   melissa-light  -- warm honey paper, dark olive ink, burnt-honey
//                     accent.
//   melissa-dark   -- warm near-black paper, honey cream ink,
//                     bright honey accent.
//
// Both palettes share the same hue family (warm yellows / cream)
// so a deck can flip mode without changing accent strategy.
//
// Tokens follow ef-themes naming so the source of every color is
// obvious:
//
//   bg-main / bg-dim / bg-alt          background tiers
//   fg-main / fg-dim / fg-alt          foreground tiers
//   <hue>                              ink color (red, blue, ...)
//   bg-<hue>-subtle                    panel fill for that hue
//
// The `callout` sub-dict maps a semantic kind (`info`, `warn`,
// `success`, `danger`) to a (fill, ink) pair drawn from the
// palette above. lib.typ's `callout` and `pill` look up directly
// from this dict -- no alpha math, no derived colors.

// ---- melissa-light ------------------------------------------------
//
// Source palette: ef-melissa-light. "Melissa" = bee. Honey cream
// paper, warm dark olive ink, burnt-honey accent.
#let melissa-light = (
  // Background tiers.
  bg-main:    rgb("#fff6d8"),  // honey cream paper
  bg-dim:     rgb("#f5e9cb"),  // dimmer cream panel
  bg-alt:     rgb("#f0d8a4"),  // honey-tan; cover + code background

  // Foreground tiers.
  fg-main:    rgb("#484431"),  // warm dark olive
  fg-dim:     rgb("#56503a"),  // body text; 4.5:1 on bg-main
  fg-alt:     rgb("#80431a"),  // warm chestnut; asides

  // Named hues (ink usage).
  red:        rgb("#aa1100"),
  green:      rgb("#226f00"),
  yellow:     rgb("#8a5511"),  // body-tier yellow
  blue:       rgb("#375cc6"),
  magenta:    rgb("#a23ea4"),
  cyan:       rgb("#1f6fbf"),

  // Subtle backgrounds (panel fills paired with the matching hue).
  bg-red-subtle:     rgb("#ffc6bf"),
  bg-green-subtle:   rgb("#c4f2af"),
  bg-yellow-subtle:  rgb("#f8e0a0"),
  bg-blue-subtle:    rgb("#cbdfff"),
  bg-magenta-subtle: rgb("#f0d3ff"),
  bg-cyan-subtle:    rgb("#bfe8ff"),

  // Identity accents. The deck's visual signature is the burnt-
  // honey warmer-yellow; `accent` is held distinct from `yellow`
  // so the latter can be used as the callout / "warn" ink at
  // body-text contrast.
  accent:     rgb("#ba5205"),  // yellow-warmer -- burnt honey
  secondary:  rgb("#0f708a"),  // cyan-cooler   -- deep teal

  // Chrome / asides.
  muted:        rgb("#80431a"),  // fg-alt -- warm chestnut
  muted-light:  rgb("#a89682"),  // chrome / page number
  divider:      rgb("#c5baa6"),  // border

  // Callout lookup: kind -> (fill, ink). Both values come straight
  // from the palette above; no derived colors.
  callout: (
    info:    (fill: rgb("#cbdfff"), ink: rgb("#375cc6")),
    warn:    (fill: rgb("#f8e0a0"), ink: rgb("#8a5511")),
    success: (fill: rgb("#c4f2af"), ink: rgb("#226f00")),
    danger:  (fill: rgb("#ffc6bf"), ink: rgb("#aa1100")),
  ),
)

// ---- melissa-dark -------------------------------------------------
//
// Source palette: ef-melissa-dark. Warm near-black paper, honey
// cream ink, bright honey accent. Subtle backgrounds are deep
// desaturated variants of each hue -- no lightened pastels.
#let melissa-dark = (
  // Background tiers.
  bg-main:    rgb("#231e10"),  // warm near-black paper
  bg-dim:     rgb("#2c2614"),  // dimmer warm panel
  bg-alt:     rgb("#3a3220"),  // warm dark panel; cover + code background

  // Foreground tiers.
  fg-main:    rgb("#f1e9c4"),  // honey cream
  fg-dim:     rgb("#d8cfa8"),  // body text; 7:1 on bg-main
  fg-alt:     rgb("#e0b96d"),  // warm honey; asides

  // Named hues (ink usage on dark bg -- brighter than light theme).
  red:        rgb("#ff7f7f"),
  green:      rgb("#8fcf6f"),
  yellow:     rgb("#dfa400"),
  blue:       rgb("#a0c0ff"),
  magenta:    rgb("#df9adf"),
  cyan:       rgb("#7fc6d6"),

  // Subtle backgrounds (deep desaturated variants -- panels stay
  // visibly darker than bg-main while keeping the hue identity).
  bg-red-subtle:     rgb("#4a1010"),
  bg-green-subtle:   rgb("#0f3f1a"),
  bg-yellow-subtle:  rgb("#4a3a00"),
  bg-blue-subtle:    rgb("#102a55"),
  bg-magenta-subtle: rgb("#43103f"),
  bg-cyan-subtle:    rgb("#003a4a"),

  // Identity accents.
  accent:     rgb("#dfa400"),  // yellow-warmer -- bright honey
  secondary:  rgb("#7fc6d6"),  // cyan-cooler   -- bright teal

  // Chrome / asides.
  muted:        rgb("#e0b96d"),  // fg-alt -- warm honey
  muted-light:  rgb("#7c715a"),  // chrome / page number
  divider:      rgb("#4a4128"),  // border

  // Callout lookup: kind -> (fill, ink). Both values come straight
  // from the palette above; no derived colors.
  callout: (
    info:    (fill: rgb("#102a55"), ink: rgb("#a0c0ff")),
    warn:    (fill: rgb("#4a3a00"), ink: rgb("#dfa400")),
    success: (fill: rgb("#0f3f1a"), ink: rgb("#8fcf6f")),
    danger:  (fill: rgb("#4a1010"), ink: rgb("#ff7f7f")),
  ),
)

// ---- Selection ----------------------------------------------------
//
// Pick a theme at compile time:
//   typst compile slides.typ                    -> melissa-light
//   typst compile --input theme=dark slides.typ -> melissa-dark
//
// Any unrecognised name falls through to melissa-light so a typo
// renders something legible rather than crashing.
#let theme-name = sys.inputs.at("theme", default: "light")
#let active = if theme-name == "dark" {
  melissa-dark
} else {
  melissa-light
}
