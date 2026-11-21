# DESIGN.md

## Design System

Liquid Titanium

液体金属のなめらかさとチタンの冷たい輝きで、生成AIやクリエイティブツールに向く。

## Best Fit

生成AIワークスペース、画像編集、ノードエディタ、プロンプト管理

## Design Principles

- Keep interfaces readable before adding decoration.
- Use glossy steampunk details as accents, not as the whole layout.
- Prefer consistent tokens over one-off colors, shadows, and radius values.
- Start by applying this system to buttons, cards, inputs, and tables before changing the full app shell.

## Visual Tone

- Category: metal
- Density: 中密度
- Typography: Soft Tech Sans + generous labels
- Motion: 液面が移動するような連続変形
- Spacing: 8px base / flowing panels
- Control radius: 14px

## Color Tokens

| Token | Value | Usage |
| --- | --- | --- |
| `--ds-bg` | `#070b12` | App background |
| `--ds-surface` | `rgba(18, 24, 25, 0.78)` | Cards and panels |
| `--ds-surface-strong` | `rgba(33, 30, 25, 0.88)` | Sidebars, modals, strong panels |
| `--ds-primary` | `#b7c6cf` | Primary buttons and active states |
| `--ds-accent` | `#8a7cff` | Focus rings, charts, secondary highlights |
| `--ds-highlight` | `#f5fbff` | Gloss, top edges, bright reflections |
| `--ds-text` | `#f5fbff` | Main text |
| `--ds-danger` | `#57d7c5` | Errors and destructive actions |

## Core CSS Variables

```css
:root {
  --ds-bg: #070b12;
  --ds-surface: rgba(18, 24, 25, 0.78);
  --ds-surface-strong: rgba(33, 30, 25, 0.88);
  --ds-primary: #b7c6cf;
  --ds-accent: #8a7cff;
  --ds-highlight: #f5fbff;
  --ds-text: #f5fbff;
  --ds-danger: #57d7c5;
  --ds-radius-control: 14px;
  --ds-radius-panel: 14px;
  --ds-space-base: 8px;
  --ds-space-dense: 4px;
  --ds-space-comfortable: 12px;
  --ds-shadow-glow: 0 0 28px rgba(138,124,255,.36);
  --ds-shadow-panel: 0 18px 42px rgba(0, 0, 0, 0.46);
  --ds-motion-fast: 160ms cubic-bezier(.2, .8, .2, 1);
}

[data-theme="liquid-titanium"] {
  color: var(--ds-text);
  background:
    radial-gradient(circle at 20% 0, color-mix(in srgb, var(--ds-primary) 18%, transparent), transparent 34%),
    var(--ds-bg);
}
```

## Component Recipes

```css
.ds-card {
  color: var(--ds-text);
  border: 1px solid color-mix(in srgb, var(--ds-primary) 28%, transparent);
  border-radius: var(--ds-radius-panel);
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .12), transparent 32%),
    var(--ds-surface);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .14), var(--ds-shadow-panel);
}

.ds-button {
  min-height: 40px;
  padding: 0 calc(var(--ds-space-comfortable) * 1.2);
  color: #071014;
  font-weight: 800;
  border: 1px solid color-mix(in srgb, var(--ds-highlight) 70%, white);
  border-radius: var(--ds-radius-control);
  background: linear-gradient(180deg, var(--ds-highlight), var(--ds-primary));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .38), var(--ds-shadow-glow);
  transition: transform var(--ds-motion-fast), filter var(--ds-motion-fast);
}

.ds-button:hover {
  filter: brightness(1.06);
  transform: translateY(-1px);
}

.ds-input {
  min-height: 40px;
  padding: 0 var(--ds-space-comfortable);
  color: var(--ds-text);
  border: 1px solid color-mix(in srgb, var(--ds-accent) 35%, transparent);
  border-radius: var(--ds-radius-control);
  background: rgba(0, 0, 0, .28);
}

.ds-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--ds-surface);
}

.ds-table th,
.ds-table td {
  padding: var(--ds-space-comfortable);
  border-bottom: 1px solid color-mix(in srgb, var(--ds-primary) 18%, transparent);
}
```

## Adoption Steps

1. Add the CSS variables to the app's global stylesheet.
2. Apply `.ds-button` to one safe button first.
3. Apply `.ds-card` to one repeated card or panel.
4. Apply `.ds-input` to one form area.
5. Apply `.ds-table` only after checking table contrast and row density.
6. Expand the system across the app once the small test area feels readable.

## Watch Outs

丸みと光沢が強いので業務表では少し柔らかく見える

## Scores

| Area | Score |
| --- | ---: |
| Clarity | 86 |
| Character | 99 |
| Density | 74 |
| Accessibility | 82 |
