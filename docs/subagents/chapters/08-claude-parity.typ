#import "../../presentation-template/lib.typ": *

// 08-claude-parity.typ -- one slide on portability.

#section-slide("8", "Portability")

#compare-slide(
  "opencode and Claude Code converge",
  "opencode",
  bullets(
    [`.opencode/agent/<name>.md`],
    [`~/.config/opencode/agent/<name>.md`],
    [Markdown body, YAML frontmatter.],
    [Fields: `description`, `mode`, `permission`,
     `tools`, `model`.],
  ),
  "Claude Code",
  bullets(
    [`.claude/agents/<name>.md`],
    [`~/.claude/agents/<name>.md`],
    [Markdown body, YAML frontmatter.],
    [Fields: `description`, `tools`, `model`.],
  ),
  verdict: [Same shape. A Mole persona ports to Claude Code with
  frontmatter tweaks (drop `mode`, map `permission` to `tools`).],
)

#content-slide("What does *not* port")[
  #bullets(
    [Cursor's `.cursor/rules/*.mdc` are rules, not subagents —
     different niche.],
    [Continue defines assistants inline in `config.yaml`; no
     persona files.],
    [Cline / Roo use `.clinerules/` and custom modes in settings
     JSON.],
    [There is no cross-harness standard beyond the
     opencode ↔ Claude Code convergence.],
  )
  #note[
    The realistic answer to "is there a standard location?" is:
    .claude/agents is the closest thing, and .opencode/agent
    deliberately mirrors it.
  ]
]
