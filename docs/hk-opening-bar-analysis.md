# HK 15m Opening Bar — Running Log

**Extend:** *"Add today's HK day data to `docs/hk-opening-bar-analysis.md`"*  
**Updated:** 2026-07-08 | **Source:** `~/Library/.../interactive-brokers/sessions` SEHK live IB  
**n=3 days, 63 sym-days, 21 symbols, 32 non-flat (11W/21L), 31 flat, PnL −2531 HKD** | ran: inverse (`invertTradeSide:true`) — **target:** per-symbol **TT vs inv** from bar shape  
**Live:** §Operator status — **inverse** this week; **TT parity week from Monday** (merge OPEN_DEADLINE fix branch first)

## Legend

`cp`=close_pos `(c-l)/range` | `b`=body_pct | `atr%`=range/dailyAtr14×100 | `liq`=range≥dailyAtr×ratio  
Win/Loss/Flat = position opened, PnL >0 / <0 / =0  
Archetypes RED: **A** cp≤.15∧b≥.70 capitulation | **B** cp≤.25∧b≥.50 grind | **C** lw≥.25∨cp≥.30 rejection | **D** other  
Archetypes GREEN: **A′** cp≥.85∧b≥.70 euphoria | **D** other  
**Mode draft:** `TT`=touch turn (reversal) | `inv`=inverse — see §Mode draft. Log **`draft_mode` per sym-day**; frequent TT → §TT-switch candidates. **Not deployed globally;** per-symbol TT flip is a separate promotion path.  
Dedupe: 1 session per (date,symbol). Bar: `historical.jsonl` closed refetch. PnL: `session_closed.recordedPnl`.

## Symbol strategy (target — north star)

**At 09:45, per SEHK symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) and optional symbol-specific guard rails.  
Bar shape = primary signal; symbol registry = bias/calibration over time; ATR = size gate and bucket (e.g. avoid 40–60% dead zone). **Not live** until ≥30 sym-days per rule, ≥3 months — see promotion notes in chat/doc updates.

**Roster:** Hold **21 active HK symbols**. Symbols that **never open a position** (flat every session) are prime candidates to **drop and replace** — frees slots for names with liquidity/fill rate. Track in §Operator status · §Symbol roster; do not remove winners/shape-flip names without evidence.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .68 | .34 | +.34 |
| b | .61 | .52 | +.09 |
| atr% | 80 | 52 | +28 |
| range | 6.24 | 2.02 | — |

**By color:** RED SHORT 15t 3W/12L 20% | GREEN LONG 17t 8W/9L 47%  
**ATR buckets:** <40% 6t 1W/5L −2449 | 40–60% 12t 3W/9L −2334 | ≥60% 14t 7W/7L +2252  
**RED cp buckets:** ≤.15 9t 1W/8L −2040 | .15–.25 2t 1W/1L +2279 | >.25 4t 1W/3L −1258  
**Archetype (incl flat):** A 1W/3L/6F −266 | B 1W/2L/3F +1934 | C 1W/3L/6F −1258 | D-R 0W/4L/5F −1429 | A′ 4W/0L/1F +1675 | D-G 4W/9L/10F −3188

## Patterns (n=32 — hypothesis only)

- **cp separates W/L** — winners close higher; W cp≈.68 vs L cp≈.34
- **RED cp≤.15 inverse short poor** — 1W/8L −2040 (counter: 00148 Jul6 +1809; Jul8 cp=.02 lost −1103)
- **Wide bars win** — W atr 80% vs L 52%; 40–60% atr% dead zone 3W/9L −2334
- **GREEN inverse long > RED short** — 8W/9L vs 3W/12L; Jul8 mostly GREEN bars, still net −3511
- **A′ euphoria strong** — 4W/0L/1F +1675 (00939 Jul8, 02318 Jul8, 00700/03690 Jul7)
- **Not:** colour alone, day-level switch, grind(B) as filter
- **Symbol > day** — same sym, different bar → different outcome (00148, 01888, 03690); track per §Symbols

## Mode draft (UNVALIDATED — per bar, future per-symbol)

Pick mode at 09:45 from closed bar only. **Ran inverse on all sessions so far** — TT column is counterfactual target, not actual fills.

| Bar | Draft mode | Rationale |
|-----|------------|-----------|
| RED archetype **A** (capitulation) | **TT** long | Inverse short 1W/8L on cp≤.15 |
| GREEN archetype **A′** (euphoria) | **TT** short | Fade blow-off; inverse long 4W/0L |
| else | **inv** | Default until per-symbol n grows |

```
mode = TT if (RED and cp<=.15 and b>=.70) or (GREEN and cp>=.85 and b>=.70) else inv
# then apply guard rails to the chosen mode's side
```

**Per-symbol goal:** after ≥30 sym-days, some symbols may need symbol-specific mode bias (e.g. always wide bars on 01888). Symbols that **often** produce TT-draft bars → log as **§TT-switch candidates** (`invertTradeSide: false` per deployment when promoted). Update §Symbols notes only with evidence.

### TT-draft bar (for per-symbol mode logging)

At 09:45, bar qualifies for **draft TT** (not inverse) when:

```
TT if (RED and cp<=0.15 and b>=0.70)   # archetype A → TT long
    or (GREEN and cp>=0.85 and b>=0.70) # archetype A′ → TT short
else inv
```

**Ingest rule:** For each sym-day, log `[draft_mode]` in registry (`TT` or `inv`). Bump §TT-switch candidate counters when `draft_mode==TT`.

## Symbols (HK registry)

`sym days W/L/F pnl avgcp avratr` — day lines: `MM-DD col cp atr% [draft_mode] pnl`

```
00148  3d 1/2/0  -3 .15 44  | 07-06 R .12 76 inv +1809 | 07-07 R .31 27 inv -708 | 07-08 R .02 29 TT -1103  ← capitulation flip
00388  3d 0/3/0 -954 .34 45  | 07-06 R .11 46 inv -437 | 07-07 G .14 35 inv -239 | 07-08 G .76 54 inv -279
00700  3d 2/0/1 +559 .71 86  | 07-06 G .62 95 inv +193 | 07-07 G .90 84 TT +366 | 07-08 G .62 80 inv 0
00939  3d 1/2/0 -573 .49 68  | 07-06 R .36 48 inv -582 | 07-07 R .10 46 inv -351 | 07-08 G 1.00 108 TT +360  ← colour flip A′
00992  3d 0/2/1 -1090 .38 54  | 07-06 R .06 61 TT -601 | 07-07 R .19 57 inv 0 | 07-08 R .27 60 inv -489
01299  3d 0/1/2 -370 .40 62  | 07-06 R .50 44 inv 0 | 07-07 R .06 81 TT -370 | 07-08 G .63 61 inv 0  ← colour flip
01347  3d 0/0/3 0 .44 61  | 07-06 R .26 61 inv 0 | 07-07 G .93 63 TT 0 | 07-08 G .12 59 inv 0
01810  3d 0/1/2 -697 .53 75  | 07-06 R .22 75 inv 0 | 07-07 G .70 92 inv -697 | 07-08 G .68 60 inv 0
01888  3d 2/0/1 +2942 .31 65  | 07-06 R .19 122 inv +2421 | 07-07 R .67 30 inv +521 | 07-08 R .08 44 TT 0
02318  3d 1/2/0 -166 .54 49  | 07-06 G .56 41 inv -46 | 07-07 R .10 50 inv -197 | 07-08 G .94 57 TT +77  ← A′ win
02628  3d 0/3/0 -1265 .42 40  | 07-06 G .48 39 inv -554 | 07-07 R .11 47 inv -345 | 07-08 G .67 33 inv -366
02899  3d 0/0/3 0 .56 41  | 07-06 G .80 58 inv 0 | 07-07 R .36 34 inv 0 | 07-08 G .52 32 inv 0
03033  3d 1/2/0 -307 .53 66  | 07-06 R .16 53 inv -142 | 07-07 G .79 77 inv +291 | 07-08 G .64 66 inv -456
03690  3d 1/1/1 +97 .62 81  | 07-06 R .24 55 inv 0 | 07-07 G 1.00 127 TT +873 | 07-08 G .61 60 inv -776  ← post-A′ loss
03750  3d 0/0/3 0 .17 46  | 07-06 R .13 60 inv 0 | 07-07 R .14 33 inv 0 | 07-08 R .25 44 inv 0
06869  3d 0/0/3 0 .16 56  | 07-06 R .12 109 TT 0 | 07-07 R .27 19 inv 0 | 07-08 R .10 40 TT 0
07709  3d 0/0/3 0 .40 20  | 07-06 R .00 18 TT 0 | 07-07 G 1.00 18 inv 0 | 07-08 R .20 23 inv 0
07747  3d 0/0/3 0 .44 22  | 07-06 R .08 17 inv 0 | 07-07 G .99 24 inv 0 | 07-08 R .25 24 inv 0
09618  3d 0/2/1 -1115 .47 61  | 07-06 R .50 56 inv 0 | 07-07 R .07 42 inv -443 | 07-08 G .84 85 inv -672
09988  3d 2/0/1 +411 .50 52  | 07-06 R .27 57 inv 0 | 07-07 G .51 49 inv +218 | 07-08 G .72 49 inv +193  ← 2W streak
09992  3d 0/2/1 -1090 .17 61  | 07-06 R .06 65 TT -601 | 07-07 R .19 56 inv 0 | 07-08 R .27 60 inv -489
```

**Symbol tags (3d max — revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **2W** | 00700, 01888, 09988 | |
| **3L** | 00388, 02628 | |
| **shape flip** | 00148, 03033, 00939, 01299 | outcome changed when cp/colour changed |
| **flat-heavy** | 00992 | 2F of 3d but fills when liq (0/2/1 −1090) — **keep for now** |
| **swap-candidate** | 01347, 02899, 03750, 06869, 07709, 07747 | **0W/0L, 3F/3d** — never traded; replace first |
| **tt-switch-strong** | 00148, 06869 | **≥2/3d TT-draft** — consider `invertTradeSide: false` per sym |
| **tt-switch-A′** | 00700, 00939, 03690, 02318, 01347 | 1+ A′ day; inverse long won — TT short untested |
| **tt-switch-RED-A** | 00148, 09992, 01299, 07709, 07747, 01888 | capitulation bar; inverse short mixed/poor |
| **inv ok wide** | 01888 | wins on inv-draft RED (cp .19–.67); only 1/3d TT-draft |
| **post-euphoria risk** | 03690 | +873 Jul7 A′ → −776 Jul8 inv-draft |

## Guard rails (UNVALIDATED — n=32)

Do **not** submit brackets when ALL of a rule's conditions match at 09:45 (closed bar). Revisit after each doc update.

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **G1** | inverse ∧ RED ∧ **cp≤.15** | 1W/8L −2040 | 00148 +1809 |
| **G2** | inverse ∧ **atr% 40–60** | 3W/9L −2334 | 09988 +218/+193, 02318 +77 |
| **G3** | inverse ∧ RED ∧ **cp≤.15 ∧ b≥.70** (A) | 1W/3L −266 | 00148 +1809 |
| **G4** | inverse ∧ GREEN ∧ **cp<.20 ∧ atr%<40** | 0W/1L −239 | none (n=1, monitor) |

**Apply first:** G1. G3 = stricter G1 (fewer skips, same winner cost). G2 independent of colour. G4 monitor only.

**Do not add yet:** colour-only, day-of-week, grind(B), euphoria A′ skip (4W/0L).

### Pseudocode (inverse mode)

```
if not liq: skip                          # existing
if invert and color==RED and cp<=0.15: skip   # G1
if invert and 40<=atr_pct<=60: skip           # G2
# optional: G3 replaces G1 if tighter RED filter wanted
# monitor: G4
```

**Counterfactual (Jul 6–8):** G1 → 23t PnL −492 (vs −2531); G1∨G2 → 16t +69, skips 00148 +09988×2 +02318.

**G1 in app:** `closePositionGate` + `redSkipClosePositionBelow = 0.15` — see §Recommended config.

## Operator status (memory — update when decisions change)

**Decision (2026-07-09):** Do **not** deploy §Recommended config (`HK Inverse + G1`) yet.

**Schedule (2026-07-09):** **This week (through Sun)** — **no config change**; keep **inverse** live preset below. **From Monday** — switch HK + US to **TT parity week** (same preset both markets; merge OPEN_DEADLINE fix branch first). Jul 6–8 inverse data preserved; parity week is a **controlled experiment**, not a permanent flip until reviewed.

**Planned — TT parity week (from Monday, HK + US, same preset):**

| Setting | Value |
|---------|-------|
| invertTradeSide | **OFF** (Touch Turn) |
| takeProfitToStopLossRatio | **2.0** |
| liquidityRangeDailyAtr | **ON**, 0.25 |
| closePositionGate | **OFF** |
| adjustableTrailingStop | **OFF** |
| openDeadline | **ON**, 90 min |
| entryInwardOffsetRatioOfRange | **0.0** |

Log per sym-day: `recordedPnl` (post-fix), colour, cp, atr%, draft_mode. End of week: compare HK TT vs Jul 6–8 inverse; decide revert vs per-market mode split.

**Why wait on G1:** n=32 inverse-only; parity week runs first on clean fills. Re-run G1 **on inverse history** only if HK reverts to inverse after parity week.

**Promotion gate (revisit when met):** Parity week complete + fix branch live → choose HK **TT stay**, **inverse revert**, or **per-symbol mode**; US U2 evaluated separately (see `us-opening-bar-analysis.md`).

### Live config (what is running now)

All 21 SEHK deployments — **inverse, no cp gate** (`closePositionGate` OFF).

| Setting | Live value | Note |
|---------|------------|------|
| invertTradeSide | **ON** | |
| liquidityRangeDailyAtr | **ON**, 0.25 | |
| closePositionGate | **OFF** | G1 not deployed |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | |
| entryInwardOffsetRatioOfRange | **0.0** | |
| takeProfitToStopLossRatio | **1.0** (Jul6/8) · **1.5** (Jul7) | inconsistent across days — log per session |

### Watch list while collecting (log in §Days notes)

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| RED cp≤.15 | Would G1 skip? Tag `g1-would-skip` in day note | 1W/8L −2040; Jul8 00148 −1103 |
| GREEN A′ (cp≥.85, b≥.70) | Keep trading inverse long; **do not** skip | 4W/0L +1675 |
| Ordinary GREEN (not A′) | Monitor; no knob yet | D-G 4W/9L −3188; Jul8 bleed |
| RED cp .15–.25 | Trade; do not widen G1 to 0.20 | skips 01888 Jul6 +2421 at 0.20 |
| Symbol repeat losers | 00388, 02628, 09992 | registry only; no auto-skip |
| post-euphoria | 03690 Jul7→Jul8 | n=1; monitor |

| post-euphoria | 03690 Jul7→Jul8 | n=1; monitor |
| TT-draft bar | Tag `draft_mode` TT/inv per sym-day; update §TT-switch | see §TT-switch candidates |

### TT-switch candidates (inverse → Touch Turn per symbol)

**Intent:** Symbols that **often** print TT-draft opening bars should be logged as candidates to flip **`invertTradeSide: false`** on that deployment only (reversal mode). Global HK stays inverse until per-symbol n supports splits.

**Count rule:** `tt_draft_days` = sym-days where §Mode draft formula yields `TT`. Track `tt_draft_days / sym_days` after each ingest.

| Tier | When to tag | Action |
|------|-------------|--------|
| **strong** | ≥2 TT-draft days in last 3 sym-days **or** ≥40% over ≥5 sym-days | Priority review for per-sym TT flip |
| **watch** | exactly 1 TT-draft day, or A′ win while ran inv | Keep logging; need more days |
| **inv-ok** | TT-draft rare; inv-draft PnL positive | Stay inverse on that symbol |

**Jul 6–8 registry (ran inv on all):**

| Sym | TT/3d | A / A′ split | Ran inv on TT days (PnL) | Tier | Notes |
|-----|-------|--------------|--------------------------|------|-------|
| 00148 | 2 | 2A | +1809, −1103 | **strong** | shape flip; TT long vs inv short |
| 06869 | 2 | 2A | flat, flat | **strong** | also swap-candidate — no fills to validate TT |
| 00700 | 1 | 1A′ | +366 | watch | inv long won on euphoria |
| 00939 | 1 | 1A′ | +360 | watch | colour flip; A′ win |
| 03690 | 1 | 1A′ | +873 | watch | post-euphoria loss next day on inv |
| 02318 | 1 | 1A′ | +77 | watch | |
| 01347 | 1 | 1A′ | flat | watch | swap-candidate |
| 01299 | 1 | 1A | −370 | watch | TT long candidate; inv short lost |
| 09992 | 1 | 1A | −601 | watch | inv short lost on capitulation |
| 01888 | 1 | 1A | flat | inv-ok | +2942 on inv-draft days |
| 07709 | 1 | 1A | flat | watch | |
| 07747 | 1 | 1A | flat | watch | |
| *rest* | 0 | — | — | inv-ok | stay inverse |

**Promotion gate (per symbol):** ≥5 sym-days **and** `tt_draft_days≥2` **and** inv PnL on TT-draft days ≤0 (or win rate <40% on TT-shaped fills) → trial `invertTradeSide: false` on that deployment. **Exception:** do not TT-flip if symbol is swap-candidate with 0 fills (fix roster first).

**Do not TT-flip yet (Jul 6–8):** A′ symbols where inverse long is 4W/0L — TT short is counterfactual only. RED-A symbols need TT-long replay, not blind flip.

### Jul 6–8 synthesis (for next agent)

| Hypothesis | Verdict after Jul8 |
|------------|-------------------|
| cp separates W/L | **Stronger** (.68 vs .34) |
| RED cp≤.15 inverse short poor (G1) | **Stronger** — deploy candidate when n grows |
| A′ euphoria inverse long | **Stronger** — never `greenSkipClosePositionAbove` |
| GREEN day = good | **Disproved** — Jul8 14/21 green, −3511 |
| atr 40–60% dead zone (G2) | **Weaker** — Jul8 mid-atr won |
| wide bars always win | **Weaker** — Jul8 ≥60% atr 1W/4L |
| colour-only skip | **Disproved** |

**Counterfactual anchor:** G1 cp gate only → **−492** vs actual **−2531** (Jul6–8); per-day: +2061→+1291, −1081→+626, −3511→−2408. Does not model TP:SL change.

**Recommended preset:** documented in §Recommended config — **not live** until promotion gate met.

### Symbol roster (swap policy — memory)

**Intent (2026-07-08):** May **remove low-activity symbols and add new ones** instead of (or before) rule changes. Priority: names that **never get a fill** — they consume a deployment slot but contribute no shape/PnL data.

**Swap candidates (Jul 6–8, 0 fills all 3 days):** `01347`, `02899`, `03750`, `06869`, `07709`, `07747` (6 of 21).

| Sym | 3d W/L/F | PnL | Why candidate |
|-----|----------|-----|---------------|
| 01347 | 0/0/3 | 0 | never opened; had A′ bar Jul7 but no fill |
| 02899 | 0/0/3 | 0 | never opened |
| 03750 | 0/0/3 | 0 | never opened |
| 06869 | 0/0/3 | 0 | never opened; wide bar Jul6 still flat |
| 07709 | 0/0/3 | 0 | never opened |
| 07747 | 0/0/3 | 0 | never opened |

**Not swap candidates (yet):**

| Sym | Reason to keep |
|-----|----------------|
| 00700, 01888, 09988 | 2W track record |
| 00148, 00939, 03033 | shape-flip signal value |
| 00388, 02628, 09992 | lose when they fill — data, not silence |
| 00992 | flat-heavy but 2 losses when liq — different from never-trade |
| 01810, 01299, 09618 | mixed flat + losses — need more days |

**Swap gate (suggested):** ≥**5 consecutive HK sessions** with `positionOpened=false` (or 3/3 flat if fewer days available) **and** bar often fails liquidity **or** brackets placed but never filled → drop; add replacement with similar sector/liquidity (log new sym in registry from day 1).

**On swap:** Remove deployment; add new symbol; note `roster: dropped XXXX → YYYY (date)` in Validation log. Recompute totals — dropped symbols don't affect traded PnL but reduce wasted sym-days.

**Replacements:** TBD — prefer liquid SEHK names with opening-range ≥25% ATR more often than swap list. No replacements chosen yet.

## Recommended HK config (all 21 SEHK deployments)

**Preset name:** `HK Inverse + G1` | **Status:** hypothesis — **not deployed**; apply uniformly when promoted  
**Maps research →** `TouchTurnRuleConfig` cp gate (branch with advanced cp options)

### Triggers

| Setting | Value | Note |
|---------|-------|------|
| Require minimum range (× daily ATR) | **ON** | |
| Liquidity range (× ATR) | **0.25** | do not raise to 0.40 |
| Skip when bar is green | **OFF** | A′ + 09988 are GREEN |
| Skip when bar is red | **OFF** | 01888 is RED |
| Close position (cp) gate | **ON** | |
| Green — skip if cp at or below | *(empty)* | G4 n=1; monitor only |
| Green — skip if cp at or above | *(empty)* | **never** — kills A′ (4W/0L) |
| Red — skip if cp at or below | **0.15** | **G1** — skip inverse short on capitulation |
| Red — skip if cp at or above | *(empty)* | high-cp RED still has winners (01888 Jul7) |
| Min gross profit | **0** | |
| Closed-bar refetch settle | **3000** ms | default |

### Execution

| Setting | Value | Note |
|---------|-------|------|
| Invert trade side | **ON** | default for non-capitulation / non-A′ bars |
| Entry inward offset (× range) | **0.0** | live IB at bar extreme |
| Entry outward offset (× range) | **0.0** | |
| TP green / red (× range) | **0.382 / 0.382** | defaults |
| TP : SL ratio | **1.5** | Jul7 setting; prefer over 1.0 (Jul8 worst day) |

### Post-entry · Session

| Setting | Value |
|---------|-------|
| Adjustable trailing stop | **OFF** |
| RTH open deadline | **ON**, **90** min |

### Compact reference

```
Triggers:    liq ON 0.25 | skipGreen OFF | skipRed OFF | cpGate ON
             green cp below/above: — / —
             red cp below: 0.15 | red cp above: —
Execution:   invert ON | entryInward 0.0 | TP:SL 1.5
Post-entry:  trailing OFF
Session:     deadline ON 90m
```

### Counterfactual (Jul 6–8, cp gate as configured)

`redSkipClosePositionBelow=0.15` on liquidity bars → skip 9 nf trades (1W: 00148 +1809); kept 23t **−492** vs actual **−2531**.

**Do not use 0.20** for red below — also skips 01888 Jul6 (cp=.19, +2421) → counterfactual **−2771**.

### Leave off (explicit)

| Setting | Why |
|---------|-----|
| `greenSkipClosePositionAbove = 0.85` | blocks A′ euphoria wins |
| `skipGreenLiquidityBar` / `skipRedLiquidityBar` | colour-only; disproved |
| G2 atr 40–60% | Jul8 mid-atr won; weaker than G1 |
| `fiveMinuteConfirmation` | N/A when invert on |

### Caveats

- n=32 — not production-validated; revisit after each doc update.
- G1 trades 00148 Jul6 (+1809) for fewer capitulation losses (incl Jul8 −1103).
- Ordinary GREEN (non-A′) still loses — no cp knob yet (03690, 09618, 03033 Jul8).
- Mode draft TT on RED A is **not** this preset — we skip, not fade.

## Days

| date | n | nf W/L | PnL | avg atr% | note |
|------|---|--------|-----|----------|------|
| 2026-07-06 | 21 | 3/6 | +2061 | 60 | 01888 +2421, 00148 +1809 |
| 2026-07-07 | 21 | 5/8 | −1081 | 52 | GREEN 4W/2L; 00700/03690 A′ win |
| 2026-07-08 | 21 | 3/7 | −3511 | 53 | mostly GREEN (14/21); 00148 −1103, 03690 −776, 09618 −672 |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-08 00148 R .02 .90 29 A -1103
2026-07-08 03690 G .61 .61 60 D -776
2026-07-07 00148 R .31 .30 27 C -708
2026-07-07 01810 G .70 .61 92 D -697
2026-07-08 09618 G .84 .68 85 D -672
2026-07-06 09992 R .06 .82 65 A -601
2026-07-06 00939 R .36 .55 48 C -582
2026-07-06 02628 G .48 .03 39 D -554
2026-07-08 09992 R .27 .66 60 C -489
2026-07-08 03033 G .64 .64 66 D -456
2026-07-07 09618 R .07 .40 42 D -443
2026-07-06 00388 R .11 .47 46 D -437
2026-07-07 01299 R .06 .93 81 A -370
2026-07-08 02628 G .67 .44 33 D -366
2026-07-07 00939 R .10 .20 46 D -351
2026-07-07 02628 R .11 .58 47 B -345
2026-07-08 00388 G .76 .57 54 D -279
2026-07-07 00388 G .14 .07 35 D -239
2026-07-07 02318 R .10 .31 50 D -197
2026-07-06 03033 R .16 .81 53 B -142
2026-07-06 02318 G .56 .32 41 D -46
2026-07-08 02318 G .94 .85 57 A' +77
2026-07-08 09988 G .72 .60 49 D +193
2026-07-06 00700 G .62 .11 95 D +193
2026-07-07 09988 G .51 .39 49 D +218
2026-07-07 03033 G .79 .77 77 D +291
2026-07-08 00939 G 1.00 .96 108 A' +360
2026-07-07 00700 G .90 .77 84 A' +366
2026-07-07 01888 R .67 .03 30 C +521
2026-07-07 03690 G 1.00 .91 127 A' +873
2026-07-06 00148 R .12 .72 76 A +1809
2026-07-06 01888 R .19 .61 122 B +2421
```

31 flat sessions omitted (rebuild from captures if needed).

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-06–08 | 3 | 63 | 11/21 | −2531 | live IB inverse; Jul8 worst day; G1 cf −492 |

---
*Agent: north star = §Symbol strategy. **Respect §Operator status** — do not assume recommended config is live. Ingest day → tag `g1-would-skip`, **`draft_mode` TT/inv** per sym-day; update **§TT-switch candidates** counts/tiers and **§Symbol roster** flat streaks. Log roster drops/adds and per-sym TT flips in Validation log. Also: Symbols, Totals, Patterns, Mode draft, Guard rails, Recommended config, Days, Trades, Validation log. Keep terse.*
