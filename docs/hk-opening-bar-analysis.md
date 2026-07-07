# HK 15m Opening Bar — Running Log

**Extend:** *"Add today's HK day data to `docs/hk-opening-bar-analysis.md`"*  
**Updated:** 2026-07-07 | **Source:** `~/Library/.../interactive-brokers/sessions` SEHK live IB  
**n=2 days, 42 sym-days, 21 symbols, 22 non-flat (8W/14L), 20 flat, PnL +980 HKD** | ran: inverse (`invertTradeSide:true`) — **target:** per-symbol **TT vs inv** from bar shape

## Legend

`cp`=close_pos `(c-l)/range` | `b`=body_pct | `atr%`=range/dailyAtr14×100 | `liq`=range≥dailyAtr×ratio  
Win/Loss/Flat = position opened, PnL >0 / <0 / =0  
Archetypes RED: **A** cp≤.15∧b≥.70 capitulation | **B** cp≤.25∧b≥.50 grind | **C** lw≥.25∨cp≥.30 rejection | **D** other  
Archetypes GREEN: **A′** cp≥.85∧b≥.70 euphoria | **D** other  
**Mode draft:** `TT`=touch turn (reversal) | `inv`=inverse — see §Mode draft. **Not deployed;** logged per bar for future symbol-level rules.  
Dedupe: 1 session per (date,symbol). Bar: `historical.jsonl` closed refetch. PnL: `session_closed.recordedPnl`.

## Symbol strategy (target — north star)

**At 09:45, per SEHK symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) and optional symbol-specific guard rails.  
Bar shape = primary signal; symbol registry = bias/calibration over time; ATR = size gate and bucket (e.g. avoid 40–60% dead zone). **Not live** until ≥30 sym-days per rule, ≥3 months — see promotion notes in chat/doc updates.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .60 | .24 | +.36 |
| b | .54 | .46 | +.08 |
| atr% | 83 | 51 | +32 |
| range | 8.15 | 1.68 | — |

**By color:** RED SHORT 13t 3W/10L 23% | GREEN LONG 9t 5W/4L 56%  
**ATR buckets:** <40% 4t 1W/3L −980 | 40–60% 9t 1W/8L −2325 | ≥60% 9t 6W/3L +4285  
**RED cp buckets:** ≤.15 8t 1W/7L −936 | .15–.25 2t 1W/1L +2279 | >.25 3t 1W/2L −769  
**Archetype (incl flat):** A 1W/2L/4F +838 | B 1W/2L/1F +1934 | C 1W/2L/6F −769 | D-R 0W/4L/4F −1429 | A′ 2W/0L/1F +1238 | D-G 3W/4L/5F −832

## Patterns (n=22 — hypothesis only)

- **cp separates W/L** — winners close higher; RED shorts: W cp≈.33 vs L cp≈.14
- **RED cp≤.15 inverse short poor** — 1W/7L −936 (counter: 00148 Jul6 +1809)
- **Wide bars win** — W atr 83% vs L 51%; 40–60% atr% dead zone 1W/8L
- **GREEN inverse long > RED short** this sample
- **Not:** colour alone, day-level switch, grind(B) as filter
- **Symbol > day** — same sym, different bar → different outcome (00148, 01888); track per §Symbols

## Mode draft (UNVALIDATED — per bar, future per-symbol)

Pick mode at 09:45 from closed bar only. **Ran inverse on all sessions so far** — TT column is counterfactual target, not actual fills.

| Bar | Draft mode | Rationale |
|-----|------------|-----------|
| RED archetype **A** (capitulation) | **TT** long | Inverse short 1W/7L on cp≤.15 |
| GREEN archetype **A′** (euphoria) | **TT** short | Fade blow-off; inverse long 2W/0L but n=2 |
| else | **inv** | Default until per-symbol n grows |

```
mode = TT if (RED and cp<=.15 and b>=.70) or (GREEN and cp>=.85 and b>=.70) else inv
# then apply guard rails to the chosen mode's side
```

**Per-symbol goal:** after ≥30 sym-days, some symbols may need symbol-specific mode bias (e.g. always wide bars on 01888). Update §Symbols notes only with evidence.

## Symbols (HK registry)

`sym days W/L/F pnl avgcp avratr` — day lines: `MM-DD col cp atr% [draft_mode] pnl`

```
00148  2d 1/1/0 +1100 .22 51  | 07-06 R .12 76 inv +1809 | 07-07 R .31 27 inv -708  ← shape flip
00388  2d 0/2/0  -675 .12 40  | 07-06 R .11 46 inv -437 | 07-07 G .14 35 inv -239
00700  2d 2/0/0  +558 .76 89  | 07-06 G .62 95 inv +193 | 07-07 G .90 84 TT +366   ← strong G
00939  2d 0/2/0  -933 .23 47  | 07-06 R .36 48 inv -582 | 07-07 R .10 46 inv -351
00992  2d 0/0/2     0 .30 58  | 07-06 R .06 61 inv 0 | 07-07 G .53 55 inv 0
01299  2d 0/1/1  -369 .28 62  | 07-06 R .50 44 inv 0 | 07-07 R .06 81 TT -370
01347  2d 0/0/2     0 .59 62  | 07-06 R .26 61 inv 0 | 07-07 G .93 63 TT 0
01810  2d 0/1/1  -696 .46 83  | 07-06 R .22 75 inv 0 | 07-07 G .70 92 inv -697
01888  2d 2/0/0 +2941 .43 75  | 07-06 R .19 122 inv +2421 | 07-07 R .67 30 inv +521  ← both W, wide Jul6
02318  2d 0/2/0  -242 .33 45  | 07-06 G .56 41 inv -46 | 07-07 R .10 50 inv -197
02628  2d 0/2/0  -899 .30 43  | 07-06 G .48 39 inv -554 | 07-07 R .11 47 inv -345
02899  2d 0/0/2     0 .58 46  | 07-06 G .80 58 inv 0 | 07-07 R .36 34 inv 0
03033  2d 1/1/0  +149 .47 65  | 07-06 R .16 53 inv -142 | 07-07 G .79 77 inv +291  ← colour flip
03690  2d 1/0/1  +872 .62 90  | 07-06 R .24 54 inv 0 | 07-07 G 1.00 127 TT +873
03750  2d 0/0/2     0 .13 46  | 07-06 R .13 60 inv 0 | 07-07 R .14 32 inv 0
06869  2d 0/0/2     0 .20 64  | 07-06 R .12 109 inv 0 | 07-07 R .27 19 inv 0
07709  2d 0/0/2     0 .50 18  | 07-06 R .00 18 TT 0 | 07-07 G 1.00 18 inv 0
07747  2d 0/0/2     0 .54 20  | 07-06 R .08 17 inv 0 | 07-07 G .99 24 inv 0
09618  2d 0/1/1  -442 .28 48  | 07-06 R .50 55 inv 0 | 07-07 R .07 42 inv -443
09988  2d 1/0/1  +218 .39 53  | 07-06 R .27 57 inv 0 | 07-07 G .51 49 inv +218
09992  2d 0/1/1  -601 .13 61  | 07-06 R .06 65 TT -601 | 07-07 R .19 56 inv 0
```

**Symbol tags (2d max — revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **2W** | 00700, 01888 | |
| **2L** | 00388, 00939, 02628 | |
| **shape flip** | 00148, 03033 | outcome changed when cp/colour changed |
| **flat-heavy** | 00992, 01347, 02899, 03750, 06869, 07709, 07747 | 2F of 2d |
| **TT candidate** | 00700, 03690, 01347 | euphoria bars; ran inv except draft |
| **inv ok wide** | 01888 | wins at high atr% both days |

## Guard rails (UNVALIDATED — n=22)

Do **not** submit brackets when ALL of a rule's conditions match at 09:45 (closed bar). Revisit after each doc update.

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **G1** | inverse ∧ RED ∧ **cp≤.15** | 1W/7L −935 | 00148 +1809 |
| **G2** | inverse ∧ **atr% 40–60** | 1W/8L −2325 | 09988 +218 |
| **G3** | inverse ∧ RED ∧ **cp≤.15 ∧ b≥.70** (A) | 1W/2L +838 | 00148 +1809 |
| **G4** | inverse ∧ GREEN ∧ **cp<.20 ∧ atr%<40** | 0W/1L −239 | none (n=1, monitor) |

**Apply first:** G1. G3 = stricter G1 (fewer skips, same winner cost). G2 independent of colour. G4 monitor only.

**Do not add yet:** colour-only, day-of-week, grind(B), euphoria A′ skip (2W/0L).

### Pseudocode (inverse mode)

```
if not liq: skip                          # existing
if invert and color==RED and cp<=0.15: skip   # G1
if invert and 40<=atr_pct<=60: skip           # G2
# optional: G3 replaces G1 if tighter RED filter wanted
# monitor: G4
```

**Counterfactual (Jul 6–7):** G1 → 14t PnL +1915 (vs +980); G1∨G2 → 10t +2467, skips 00148 +09988.

## Days

| date | n | nf W/L | PnL | avg atr% | note |
|------|---|--------|-----|----------|------|
| 2026-07-06 | 21 | 3/6 | +2061 | 60 | 01888 +2421, 00148 +1809 |
| 2026-07-07 | 21 | 5/8 | −1081 | 52 | GREEN 4W/2L; 00700/03690 A′ win |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-07 00148 R .31 .30 27 C -708
2026-07-07 01810 G .70 .61 92 D -697
2026-07-06 09992 R .06 .82 65 A -601
2026-07-06 00939 R .36 .55 48 C -582
2026-07-06 02628 G .48 .03 39 D -554
2026-07-07 09618 R .07 .40 42 D -443
2026-07-06 00388 R .11 .47 46 D -437
2026-07-07 01299 R .06 .93 81 A -370
2026-07-07 00939 R .10 .20 46 D -351
2026-07-07 02628 R .11 .58 47 B -345
2026-07-07 00388 G .14 .07 35 D -239
2026-07-07 02318 R .10 .31 50 D -197
2026-07-06 03033 R .16 .81 53 B -142
2026-07-06 02318 G .56 .32 41 D -46
2026-07-06 00700 G .62 .11 95 D +193
2026-07-07 09988 G .51 .39 49 D +218
2026-07-07 03033 G .79 .77 77 D +291
2026-07-07 00700 G .90 .77 84 A′ +366
2026-07-07 01888 R .67 .03 30 C +521
2026-07-07 03690 G 1.00 .91 127 A′ +873
2026-07-06 00148 R .12 .72 76 A +1809
2026-07-06 01888 R .19 .61 122 B +2421
```

20 flat sessions omitted (rebuild from captures if needed).

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-06–07 | 2 | 42 | 8/14 | +980 | seed, live IB inverse |

---
*Agent: north star = §Symbol strategy. Ingest day → Symbols registry, Totals, Patterns, Mode draft, Guard rails, Days, Trades, Validation log. Keep terse.*
