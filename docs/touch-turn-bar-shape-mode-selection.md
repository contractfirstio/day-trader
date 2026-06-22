# Touch Turn vs Inverse: Bar-Shape Mode Selection (Research Notes)

**Status:** Hypothesis — awaiting validation over more HK hybrid sessions  
**Last updated:** 2026-06-22  
**Authors:** Human + AI analysis session (Jun 2026)

This document captures where we got to on choosing between **Touch Turn (reversal)** and **Inverse (continuation)** at runtime, using only the **15-minute opening bar** and **daily ATR**. It is written so a future AI agent (or human) can continue the line of inquiry with a **consistent methodology** when more session data accumulates.

---

## 1. Problem statement

The operator runs **Touch and Turn Scalper** on **hybrid HK sessions** (`EMULATOR_LIVE_IB_MARKET_DATA`, SEHK symbols) via `paper-live-ib`.

On two consecutive HK days (Thu 18 Jun and Mon 22 Jun 2026), the **wrong mode feel** emerged:

- Running one mode on Thursday and the other on Monday would have performed better in hindsight.
- The goal is **not** to overfit two days, but to find **observable indicators at 09:45** (when the opening 15m bar closes) that suggest when **inverse** is a poor choice — especially **inverse short on RED bars**.

**Scope of this research:** bar shape + daily ATR at decision time. Post-bar price paths were used to validate hypotheses but are **not** part of the proposed runtime filter unless explicitly extended later.

---

## 2. Terminology (must match codebase)

All logic lives in `TouchTurnLogic.computeBracketSetup()` with `TouchTurnRuleConfig.invertTradeSide`.

| User term | Code flag | RED opening bar | GREEN opening bar |
|-----------|-----------|-----------------|-------------------|
| **Touch Turn** (reversal) | `invertTradeSide = false` | **LONG** at bar low | **SHORT** at bar high |
| **Inverse** (continuation) | `invertTradeSide = true` | **SHORT** at bar low | **LONG** at bar high |

Entry is at the bar extreme (low for RED, high for GREEN), with TP/SL derived from bar range and rule config.

**Important:** Both analysed days logged `invertTradeSide: true` in `application.jsonl` `session_started` events. What **also** changed between days was `atrLiquidityRatio` (40% Thu vs 25% Mon). Do not conflate liquidity-gate changes with mode selection when validating.

### Liquidity gate (separate knob)

- Threshold: `dailyAtr14 × atrLiquidityRatio` (default ratio 0.25 in `TouchTurnDefaults.ATR_LIQUIDITY_RATIO`).
- Bar must have `range >= threshold` to place brackets (`NO_TRADE_NOT_LIQUIDITY` otherwise).
- Thu 18: **all 27** HK sessions used `atrLiquidityRatio: 0.40`.
- Mon 22: **all 20** HK sessions used `0.25` (default).

When validating mode-selection hypotheses, **record liquidity ratio per session** and optionally filter to sessions that would pass a consistent gate.

---

## 3. Data sources (for the next agent)

### App data root (macOS)

```
~/Library/Application Support/Day Trader/paper-live-ib/
```

### Per-session paths

```
sessions/{deploymentId}/{sessionId}/
  manifest.json          # symbol, sessionDate, milestones, instrument
  historical.jsonl       # signal_context: firstCandle, dailyAtr14, atr14
  application.jsonl        # session_started (rules), bracket_*, fill_*, session_closed
  prices.jsonl           # tick/quote path after open (validation only)
```

### Filter criteria for HK hybrid research set

- `manifest.json` → `instrument.exchange == "SEHK"`
- `manifest.json` → `brokerKind == "EMULATOR_LIVE_IB_MARKET_DATA"` (in manifest or `application.jsonl`)
- `sessionDate` in ISO form: `"2026-06-18"`, `"2026-06-22"`, etc.

### Deduplication rule

Multiple session folders can exist per symbol per day. **Use one session per (sessionDate, symbol)** — prefer the folder whose `manifest.json` has the most complete milestone timeline or the latest `session_started` if duplicates are ambiguous.

### Key code references

| Topic | Location |
|-------|----------|
| Bracket side / invert | `day-trader/src/commonMain/kotlin/daytrader/domain/TouchTurnLogic.kt` (`computeBracketSetup`, `applyInvertTradeSide`) |
| Liquidity gate | `TouchTurnLogic.isLiquidityCandle`, `liquidityRangeThresholdFromDailyAtr` |
| Invert placement block | `TouchTurnLogic.invertPlacementBlockOutcome` → `NO_TRADE_INVERT_STOP_WOULD_TRIGGER` |
| Defaults | `day-trader/src/commonMain/kotlin/daytrader/domain/TouchTurnDefaults.kt` |
| Session logs README | root `README.md` → Touch Turn diagnosis table |

---

## 4. Bar-shape metrics (compute at 09:45 from closed opening bar)

Use the **closed-bar refetch** line from `historical.jsonl` (`isClosedBarRefetch: true`) when available; otherwise last `signal_context` line.

```text
range       = high - low
body_pct    = |close - open| / range
close_pos   = (close - low) / range          # 0 = at low, 1 = at high
lower_wick  = (min(open, close) - low) / range
upper_wick  = (high - max(open, close)) / range
pct_atr     = range / dailyAtr14 * 100
color       = GREEN if close > open else RED
```

### Hypothesised archetypes

| ID | Name | Condition (RED bar) | Inverse trade | Concern |
|----|------|---------------------|---------------|---------|
| **A** | Capitulation | `close_pos <= 0.15` AND `body_pct >= 0.70` | SHORT at low | Exhaustion dump; bounce risk |
| **B** | Grind-down | `close_pos <= 0.25` AND `body_pct >= 0.50` | SHORT at low | Was thought to favour inverse — **weak support** |
| **C** | Rejection | `lower_wick >= 0.25` OR `close_pos >= 0.30` | SHORT at low | Close recovered from low |
| **D** | Other | Everything else | — | — |

**GREEN bar mirror (hypothesis only, minimal data):**

| ID | Name | Condition (GREEN bar) | Inverse trade | Concern |
|----|------|----------------------|---------------|---------|
| **A′** | Euphoria / blow-off top | `close_pos >= 0.85` AND `body_pct >= 0.70` | LONG at high | Exhaustion rip; fade risk |

---

## 5. Sessions analysed (baseline)

| Date | HK sessions | Invert flag | ATR liquidity ratio | Filled (inverse) | Approx PnL |
|------|-------------|-------------|---------------------|------------------|------------|
| 2026-06-18 (Thu) | 27 | `true` | **0.40** | 14 | ~ -6,257 HKD |
| 2026-06-22 (Mon) | 20 | `true` | **0.25** | 9 | ~ -4,942 HKD |

Overlap: **20 symbols** traded on both days.

### Monday inverse short losers — capitulation pattern (strongest signal)

| Symbol | close_pos | body_pct | Actual PnL | Post-fill price |
|--------|-----------|----------|------------|-----------------|
| 1810 | 0.03 | 0.83 | -1,176 | Ran **up** after short fill |
| 3690 | 0.05 | 0.95 | -1,400 | Ran **up** after short fill |
| 09618 | 0.05 | 0.95 | -720 | Ran **up** after short fill |
| 1024 | 0.06 | 0.94 | -1,218 | Ran **up** after short fill |

**4 of 7** Monday losses matched capitulation (A). **0 of 2** Monday wins matched.

Monday winners (inverse short): **07747**, **0939** — close_pos ~0.31–0.33, body ~0.22–0.53 (not capitulation).

### Same-symbol, opposite outcomes (bar shape matters more than symbol)

| Symbol | Thu 18 | Mon 22 |
|--------|--------|--------|
| **3690** | close_pos 0.13, body **13%** → inverse **won** (+1,672) | close_pos 0.05, body **95%** → inverse **lost** (-1,400) |

### GREEN / inverse long (anecdotal only, n = 2)

| Symbol | Date | close_pos | body_pct | PnL |
|--------|------|-----------|----------|-----|
| 02899 | Thu | 0.88 | 0.85 | -1,027 |
| 1024 | Thu | 1.00 | 0.78 | -42 |

Both match euphoria (A′). **Do not treat as validated.**

---

## 6. Current theory (provisional)

### Supported (weak confidence, Mon-heavy)

**Inverse SHORT on RED capitulation bars (A) is a poor expectation.**

- Bar often shows price **above close** by session end (6/6 capitulation bars across both days bounced from close in tick analysis).
- When inverse short **filled** on capitulation shape Monday, price frequently moved **up** after fill (adverse for short).

**Proposed filter (do not auto-implement without validation):**

```text
IF invertTradeSide AND color == RED
   AND close_pos <= 0.15
   AND body_pct >= 0.70
THEN
   do NOT expect inverse short to work
   → consider Touch Turn (reversal long at low) OR skip
```

### Hypothesis only (mirror, not validated)

```text
IF invertTradeSide AND color == GREEN
   AND close_pos >= 0.85
   AND body_pct >= 0.70
THEN
   do NOT expect inverse long to work
   → consider Touch Turn (reversal short at high) OR skip
```

### Not supported — deprioritise

| Idea | Result |
|------|--------|
| Grind-down (B) → favour inverse | Grind bars bounced ~82%; poor discriminator |
| Day-level mode switch (Thu vs Mon) | Same symbol reversed; not stable |
| Candle colour alone | ~80% RED both days; useless for mode pick |
| Simple shape classifier (A/C→rev, B→inv) | ~32% accuracy on combined sample |
| Range/ATR alone for mode | ATR gates *whether* to trade; shape gates *direction/mode* |

### Confounders to always note

1. **`atrLiquidityRatio`** changed between baseline days.
2. **`NO_TRADE_INVERT_STOP_WOULD_TRIGGER`** — tight bars block inverse regardless of shape.
3. **`NO_TRADE_DATA_FAILED`** — missing bootstrap; not shape-related.
4. Bracket TP/SL geometry — session-end price ≠ actual trade outcome; use `session_closed` PnL for ground truth.

---

## 7. Validation plan (for the next agent)

Run this **every time new HK hybrid batches accumulate** (suggest: monthly or after every 10+ session days).

### Step 1 — Ingest

1. Glob `paper-live-ib/sessions/**/manifest.json` for `SEHK` + target dates.
2. Deduplicate to one row per `(sessionDate, symbol)`.
3. Parse `session_started` → `touchTurnRules` (`invertTradeSide`, `atrLiquidityRatio`).
4. Parse closed bar from `historical.jsonl` → compute metrics in §4.
5. Parse `session_closed` → `recordedPnl`, `positionOpened`, `decisionOutcome` / `stopTrigger`.

### Step 2 — Label outcomes

For each session where `invertTradeSide == true` and orders were placed:

| Label | Definition |
|-------|------------|
| `inverse_fill_win` | Position opened, PnL > 0 |
| `inverse_fill_loss` | Position opened, PnL < 0 |
| `inverse_no_fill` | Orders placed, no position |
| `inverse_no_trade` | `NO_TRADE_*` decision |

Record `entrySide` from `bracket_submitted` (expect `SHORT` on RED inverse).

### Step 3 — Test primary hypothesis

**H1:** Among `inverse_fill_*` on **RED** bars, capitulation (A) has lower win rate than non-capitulation.

Report:

- Win rate capitulation vs non-capitulation
- Count when filter would have **skipped** a winner (false positive cost)
- Count when filter would have **avoided** a loser (true positive)

**H1′ (secondary):** Same for euphoria (A′) on **GREEN** inverse longs.

### Step 4 — Optional price-path corroboration

From `prices.jsonl` after `fill_recorded` (or after bar close if no fill):

- `max_adverse` for short = `max(price) - entry` after fill
- `bounce_from_close` = `max(price after 09:45) - bar_close`

Check if capitulation losses cluster on `max_adverse > 0` and larger `bounce_from_close`.

### Step 5 — Update this document

Append a row to **§9 Validation log** with date range, n sessions, H1 result, and whether to promote, revise, or reject thresholds.

### Promotion criteria (suggested)

| Stage | Criteria |
|-------|----------|
| **Keep monitoring** | < 30 RED inverse fills total |
| **Soft filter** | ≥ 30 fills; capitulation win rate < 35% and non-capitulation > 45%; false skip rate < 25% |
| **Hard filter** | ≥ 60 fills; statistically meaningful separation across 3+ months |

Adjust thresholds as sample grows. Do not optimize `0.15` / `0.70` on the same data used to promote without holdout weeks.

---

## 8. Consistent approach checklist

When continuing this research, the agent **must**:

- [ ] Use **SEHK hybrid** sessions under `paper-live-ib` unless scope explicitly widens.
- [ ] Deduplicate **one session per symbol per day**.
- [ ] Read **closed-bar** OHLC from `historical.jsonl` refetch line.
- [ ] Separate **liquidity gate** (`atrLiquidityRatio`) from **mode hypothesis**.
- [ ] Use **`invertTradeSide` from logs**, not operator memory of “Touch Turn vs Inverse”.
- [ ] Ground truth PnL from **`session_closed`**, not synthetic session-end marks.
- [ ] Report **sample sizes** and **confounders** honestly.
- [ ] Distinguish **inverse short (RED)** evidence from **inverse long (GREEN)** hypothesis.
- [ ] Avoid claiming day-level rules (“always reversal on Monday”).
- [ ] Append results to §9 Validation log; do not delete prior baseline findings.

---

## 9. Validation log

| Period | n (HK hybrid) | n (RED inverse fills) | Capitulation loss rate | Notes |
|--------|---------------|------------------------|------------------------|-------|
| 2026-06-18 – 2026-06-22 | 47 unique symbol-days (27+20) | ~23 fills (both days, invert) | Mon: 4/4 capitulation fills lost; 0/2 wins capitulation | Initial exploration; ATR ratio differed by day |
| *next review* | | | | |

---

## 10. Open questions

1. Does capitulation filter still hold when **`atrLiquidityRatio` is fixed** at 0.25 (or 0.40) across all days?
2. Would a **5-minute post-bar confirmation** (price holds below low vs reclaims close) beat bar shape alone without adding too much latency?
3. Does **Reversal Score** (`ReversalScoreService`) correlate with capitulation fade success?
4. Should capitulation skip **block orders** or only **flip `invertTradeSide`**?
5. Are US / UK opens different enough that HK thresholds need separate calibration?

---

## 11. Quick reference — proposed runtime rules (UNVALIDATED)

```text
# Inverse SHORT caution (RED) — weak evidence
capitulation_red = (close_pos <= 0.15 and body_pct >= 0.70)

# Inverse LONG caution (GREEN) — hypothesis only
euphoria_green = (close_pos >= 0.85 and body_pct >= 0.70)

# Liquidity (existing product behaviour)
passes_liquidity = (range >= dailyAtr14 * atrLiquidityRatio)
```

**Do not ship to production** until §7 promotion criteria are met and documented in §9.

---

## 12. Related analysis from initial session

### 40% ATR gate counterfactual (Mon 22 only)

If Monday had used **40%** liquidity ratio instead of 25%:

- **1 win** would not have run (07747, +960 HKD) — range exactly 25% of daily ATR.
- **0 losses** would have been filtered.
- Net would have been **worse**, not better.

Liquidity ratio and mode selection are independent decisions.

### Aggregate bar stats (20 common symbols)

| Day | Avg opening range | Avg range/daily ATR | Invert fills |
|-----|-------------------|---------------------|--------------|
| Thu 18 | 4.09 | 53% | 11 |
| Mon 22 | 6.12 | 69% | 9 |

Wider bars Monday → more orders, lower touch rate; separate from capitulation hypothesis.

---

*End of research notes. Next agent: extend §9, revise §6 only with new evidence, keep methodology in §7–8 unchanged unless there is a documented reason to change it.*
