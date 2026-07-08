# US 15m Opening Bar — Running Log

**Extend:** *"Add today's US day data to `docs/us-opening-bar-analysis.md"`*  
**Updated:** 2026-07-09 | **Source:** `~/Library/.../interactive-brokers/sessions` SMART live IB  
**n=3 days, 60 sym-days, 20 symbols, 34 non-flat (16W/18L), 26 flat, PnL +638 USD (corrected)** | ran: **Touch Turn** (`invertTradeSide` absent/false — RED→LONG, GREEN→SHORT)  
**Live:** §Operator status — **US TT / HK inverse** this week; **TT parity week from Monday** (after fix branch merge)

## Legend

Same as HK doc: `cp`, `b`, `atr%`, archetypes **A/B/C/D/A′**, W/L/Flat.  
**Mode draft:** `TT` | `inv` — see §Mode draft. US Jul 6–8 **ran TT** on all symbols; log **`draft_mode`** per sym-day for future inv candidates.  
Dedupe: 1 session per (date,symbol). Bar: `historical.jsonl` closed refetch.  
**PnL:** `session_closed.recordedPnl` for sessions **after** OPEN_DEADLINE fix merged; Jul 6–8 below uses **`corr*`** where fill drain was missing.

## PnL data quality

**OPEN_DEADLINE exit-fill bug (historical Jul 6–8 only):** Session stops with `stopTrigger=OPEN_DEADLINE`, `roundTrips=entry_only` — entry fill captured, **exit fill absent** from `recordedPnl` (shows 0). **Fix:** fill drain on **another branch** (not in Jul 6–8 captures) — merge/deploy before next week's stats collection; then use `recordedPnl` directly.

| date | sym | recorded | corr | method |
|------|-----|----------|------|--------|
| 2026-07-06 | F | 0 | **+46** | SHORT TP hit |
| 2026-07-06 | COIN | 0 | **+1** | SHORT TP hit |
| 2026-07-06 | PLTR | 0 | **+1** | SHORT TP hit |
| 2026-07-06 | IWM | 0 | **−1** | SHORT deadline est |
| 2026-07-06 | INTC | 0 | **−2** | SHORT deadline est |
| 2026-07-08 | MU | 0 | **+166** | SHORT TP hit (min px 920.34) |
| 2026-07-08 | F | 0 | **+70** | SHORT TP hit |
| 2026-07-08 | META | 0 | **+41** | LONG TP hit (fill missing) |

**Ingest rule:** Pre-fix days → tag `open_deadline_entry_only`, apply **corr*** (TP when confirmed, else deadline quote). **Post-fix days** → trust `recordedPnl`; flag if `entry_only` still appears (regression).

## Symbol strategy (target — north star)

**At 09:45 ET, per US symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) + symbol guard rails.  
Calibrate **separately from HK** — thresholds and mode bias may differ.

**Roster:** Hold **20 active US symbols**. Flat-heavy names (never fill or rarely trade) are swap candidates — see §Symbol roster.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .69 | .54 | +.15 |
| b | .63 | .43 | +.20 |
| atr% | 48 | 46 | +2 |
| range | 9.40 | 4.79 | — |

**By color (TT):** RED LONG 15t **4W/11L** −67 | GREEN SHORT 19t **12W/7L** +705  
**ATR buckets:** <40% 10t 4W/6L +253 | 40–60% 18t 9W/9L +327 | ≥60% 6t 3W/3L +58  
**RED cp buckets:** ≤.15 5t 2W/3L +18 | .15–.25 2t 1W/1L 0 | .25–.35 1t 0W/1L −15 | .35–.50 4t 1W/3L −5 | .50+ 3t 0W/3L −65  
**Archetype (incl flat):** A 2W/1L/0 | B 1W/1L/1F | C 1W/7L/10F | D-R 0W/2L/0 | A′ 3W/2L/0 | D-G 9W/5L/15F

## Patterns (n=34 — hypothesis only)

- **GREEN TT short >> RED TT long** — 12W/7L +705 vs 4W/11L −67 (stable across 3 days)
- **cp separates W/L** — W cp≈.69 vs L cp≈.54
- **Jul6 losing day** — 4W/8L −17 corr; Jul7 +20; Jul8 +635 — book saved by Jul8
- **A′ euphoria TT short strong** — 3W/2L (AMD, SOXL, PLTR Jul6; NVDA −30 one loss)
- **RED capitulation (A) TT long mixed** — 2W/1L +41 (AAPL, META Jul8; PFE Jul6 A −22)
- **RED cp≥.50 TT long poor** — 0W/3L −65 (INTC, QQQ Jul7; BAC Jul8) — **U2 candidate**
- **Wide bars win** — W range 9.40 vs L 4.79
- **Symbol > day** — PLTR, GOOGL, NVDA, QQQ, META flip by bar shape
- **Not enough n** for hard cp gates beyond U2; HK G1/G2 not ported — US ran TT not inv

## Mode draft (UNVALIDATED)

Jul 6–8 ran TT on all. Draft formula (HK parity) tags bars that *would* use inv if we split modes.

| Bar | Draft mode | Jul 6–8 note |
|-----|------------|--------------|
| GREEN (esp cp≥.60) | **TT** short | 12W/7L +705 actual |
| GREEN archetype **A′** (cp≥.85, b≥.70) | **TT** short | 3W/2L; NVDA −30 monitor |
| RED archetype **A** (cp≤.15, b≥.70) | **TT** long | 2W/1L +41; PFE Jul6 −22 |
| RED cp≥.50 | **inv** or skip? | 0W/3L −65 TT long |
| else RED | **monitor** | PLTR +57; META +3 Jul6 |

```
draft_mode = TT if (RED and cp<=0.15 and b>=0.70) or (GREEN and cp>=0.85 and b>=0.70) else inv
# US currently runs TT on all — inv column is counterfactual target
```

## Guard rails (UNVALIDATED — n=34, TT mode)

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **U1** | TT ∧ RED (all RED longs) | 4W/11L −67 | META +41/+3, AAPL +23, PLTR +57 |
| **U2** | TT ∧ RED ∧ **cp≥.50** | 0W/3L −65 | none |
| **U3** | TT ∧ GREEN ∧ **cp<.50** | 0W/0L | none (n=0) |

**Counterfactual (Jul 6–8, corrected):** U2 → 31t PnL **+703** (vs +638); skips INTC, QQQ, BAC only. U1 → 19t **+705** (vs +638); skips four RED winners. **Apply U2 when promoted; reject U1.**

**Apply first:** U2 when promoted. See §Recommended config.

## Operator status (memory — update when decisions change)

**Decision (2026-07-09):** Keep **TT on all 20 US symbols** (`invertTradeSide: false`). Three-day corrected **+638** (Jul6 −17, Jul7 +20, Jul8 +635). GREEN TT short validated. **No cp gate yet.**

**Schedule (2026-07-09):** **This week (through Sun)** — **no config change**; keep live preset below. **From Monday** — switch HK + US to **§TT parity week** preset (after OPEN_DEADLINE fix branch merged).

**OPEN_DEADLINE fix (2026-07-09):** Exit fill drain **fixed on another branch** — merge before Monday parity-week start. Jul 6–8 stats stay corrected; sessions from Monday use `recordedPnl`.

**Planned — TT parity week (from Monday):** HK + US both **Touch Turn** (`invertTradeSide: false`), **TP:SL 2.0**, liq 0.25, **no cp gate**, deadline 90m, trailing OFF — one calendar week, compare markets on identical preset. Revert HK to inverse after week unless stats justify split.

**Why wait on U2 deploy:** n=34 pre-fix; collect **≥1 week post-fix** TT parity data first, then re-run U2 counterfactual on clean fills.

**Promotion gate (revisit when met):** Fix branch merged **and** ~1 week post-fix sessions → re-run U2 on `recordedPnl`; deploy **`US Touch Turn + U2`** if RED cp≥.50 still 0W.

### Live config (what is running now)

All 20 SMART deployments — **Touch Turn, no cp gate**.

| Setting | Live value | Note |
|---------|------------|------|
| invertTradeSide | **OFF** (TT) | RED→LONG, GREEN→SHORT |
| liquidityRangeDailyAtr | **ON**, 0.25 | |
| closePositionGate | **OFF** | U2 not deployed |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | fill-drain fix on branch — merge before parity week |

### Watch list while collecting

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| GREEN cp≥.60 TT short | Keep trading | 12W/7L +705 |
| GREEN A′ (cp≥.85, b≥.70) | Keep TT short | 3W/2L |
| RED cp≤.15 TT long (A) | Keep; mixed | 2W/1L (+41/−22) |
| RED cp≥.50 TT long | Monitor U2 | 0W/3L −65 |
| OPEN_DEADLINE entry_only | Tag + corr PnL | 8 sym-days Jul6+8 |
| Symbol shape flips | Track per §Symbols | PLTR, GOOGL, NVDA, QQQ, META |
| post-euphoria | NVDA Jul7 +27 → Jul8 −30 | n=1; monitor |

### Inv-switch candidates (TT → inverse per symbol)

| Sym | inv-draft / 3d | TT PnL on inv-draft days | Tier | Notes |
|-----|----------------|--------------------------|------|-------|
| *most* | 3/3 | mixed | stay TT | GREEN book carries |
| INTC | 3/3 | −2*, −46, flat | watch | |
| AMZN | 3/3 | flat, −15, flat | watch | |
| TSLA | 3/3 | −5, −16, flat | watch | |

**Do not inv-flip yet:** GREEN TT short 12W/7L.

### Jul 6–8 synthesis (for next agent)

| Hypothesis | Verdict after 3d corr |
|------------|----------------------|
| GREEN TT short > RED TT long | **Stronger** — 12W/7L vs 4W/11L |
| cp separates W/L | **Emerging** (.69 vs .54) |
| RED TT long always bad | **Disproved** — PLTR/META/AAPL RED wins |
| RED cp≥.50 TT long bad (U2) | **Stronger** — 0W/3L; Jul6 adds no U2 cases |
| A′ TT short | **Strong** — SOXL/AMD/F/MU; NVDA only A′ loss |
| atr 40–60% dead zone | **Weaker** — 9W/9L +327 (unlike HK G2) |
| Jul6 inclusion | **Matters** — lowers total +638 vs 2d +655; U2 unchanged |
| recordedPnl without corr | **Unreliable** — 8× OPEN_DEADLINE entry_only |

**Counterfactual anchor (corrected):** U2 → **+703** vs actual **+638**; U1 → **+705** (worse than U2).

### Symbol roster (swap policy — memory)

**Flat-heavy (3d, 0 nf):** `MSFT`, `SPY` — no PnL all 3 days.  
**Low activity:** `COIN` (+1 Jul6 only), `AMZN`, `BAC` — consider if flat streak continues.  
**Keep:** `SOXL`, `MU`, `F`, `META`, `AAPL`, `PLTR`, `QQQ`, `AMD`.

**Swap gate (suggested):** ≥**5 consecutive US sessions** flat **and** bar often fails liquidity → drop; log replacement in Validation log.

## Recommended US config (all 20 SMART deployments)

**Preset name:** `US Touch Turn + U2` | **Status:** hypothesis — **not deployed**; apply uniformly when promotion gate met (§Operator status)  
**Maps research →** `TouchTurnRuleConfig` cp gate (`redSkipClosePositionAbove`). US runs **TT** (`invertTradeSide` OFF) — **not** HK inverse + G1. **Based on Jul 6–8 corrected (n=34).**

### Triggers

| Setting | Value | Note |
|---------|-------|------|
| Require minimum range (× daily ATR) | **ON** | |
| Liquidity range (× ATR) | **0.25** | do not raise |
| Skip when bar is green | **OFF** | 12W/7L GREEN TT short |
| Skip when bar is red | **OFF** | RED winners exist |
| Close position (cp) gate | **ON** | |
| Green — skip if cp at or below | *(empty)* | U3 n=0 |
| Green — skip if cp at or above | *(empty)* | **never** — kills A′ |
| Red — skip if cp at or below | *(empty)* | **never** — kills RED-A wins |
| Red — skip if cp at or above | **0.50** | **U2** — 0W/3L −65 over 3d |
| Min gross profit | **0** | |
| Closed-bar refetch settle | **3000** ms | default |

### Execution

| Setting | Value | Note |
|---------|-------|------|
| Invert trade side | **OFF** (TT) | RED→LONG, GREEN→SHORT |
| Entry inward offset (× range) | **0.0** | live IB at bar extreme |
| Entry outward offset (× range) | **0.0** | |
| TP green / red (× range) | **0.382 / 0.382** | defaults |
| TP : SL ratio | **2.0** | app default |

### Post-entry · Session

| Setting | Value | Note |
|---------|-------|------|
| Adjustable trailing stop | **OFF** | |
| RTH open deadline | **ON**, **90** min | requires fill-drain fix branch merged |

### Compact reference

```
Triggers:    liq ON 0.25 | skipGreen OFF | skipRed OFF | cpGate ON
             green cp below/above: — / —
             red cp below: — | red cp above: 0.50
Execution:   invert OFF (TT) | entryInward 0.0 | TP:SL 2.0
Post-entry:  trailing OFF
Session:     deadline ON 90m
```

### Pseudocode (TT mode)

```
if not liq: skip
if color==RED and cp>=0.50: skip   # U2
# else: trade TT (RED→long, GREEN→short)
```

### Counterfactual (Jul 6–8, corrected, cp gate as configured)

`redSkipClosePositionAbove=0.50` → skip 3 nf (0W: INTC −46, QQQ −8, BAC −11); kept 31t **+703** vs actual **+638**.

**Do not use U1** (skip all RED) — skips PLTR +57, META +41/+3, AAPL +23 → cf **+705** (marginal vs U2, loses RED edge).

**Do not use HK G1** (`redSkipClosePositionBelow=0.15`) — skips RED-A winners; opposite of US TT.

### Leave off (explicit)

| Setting | Why |
|---------|-----|
| `redSkipClosePositionBelow = 0.15` | blocks RED-A TT long wins |
| `greenSkipClosePositionAbove = 0.85` | blocks A′ TT short (+705 bucket) |
| `skipGreenLiquidityBar` / `skipRedLiquidityBar` | colour-only; disproved |
| G2 atr 40–60% skip | US mid-atr 9W/9L +327 |
| `invertTradeSide = ON` | inverts GREEN book |
| `fiveMinuteConfirmation` | N/A on current TT path |

### Caveats

- n=34 pre-fix (8× corr); post-fix week uses `recordedPnl` — not production-validated until parity week complete.
- Jul6 −17 lowers headline; U2 unchanged (no Jul6 RED cp≥.50 fills).
- Roster swaps (`MSFT`, `SPY`) operational — not part of preset.

### Live vs recommended (summary)

| | **Live now** | **Recommended when promoted** |
|--|--------------|-------------------------------|
| invertTradeSide | OFF | OFF |
| closePositionGate | OFF | **ON** |
| red cp above | — | **0.50** |
| All other triggers | same | same |

## Days

| date | n | nf W/L | PnL | avg atr% | note |
|------|---|--------|-----|----------|------|
| 2026-07-06 | 20 | 4/8 | −17 | 48 | losing day; F +46 corr; 5× deadline bug |
| 2026-07-07 | 20 | 4/7 | +20 | 37 | all wins GREEN short |
| 2026-07-08 | 20 | 8/3 | +635 | 40 | MU/F/META corr; SOXL +233 |

## Symbols (US registry)

`sym days W/L/F pnl avgcp avratr` — day: `MM-DD col cp atr% [ran_mode] pnl` — `*` = corr PnL

```
AAPL  3d 1/1/1  +20 .38 46  | 07-06 G .76 32 TT -3 | 07-07 R .35 60 TT 0 | 07-08 R .03 47 TT +23
AMD   3d 1/1/1  +75 .86 49  | 07-06 G .97 73 TT -10 | 07-07 G .73 33 TT 0 | 07-08 G .87 40 TT +85
AMZN  3d 0/1/2  -15 .41 46  | 07-06 R .06 53 TT 0 | 07-07 R .34 37 TT -15 | 07-08 R .83 48 TT 0
BAC   3d 0/1/2  -11 .63 62  | 07-06 G .86 83 TT 0 | 07-07 G .53 56 TT 0 | 07-08 R .51 47 TT -11
COIN  3d 1/0/2   +1 .56 38  | 07-06 G .65 45 TT +1* | 07-07 G .34 37 TT 0 | 07-08 G .68 33 TT 0
F     3d 2/0/1 +116 .90 50  | 07-06 G 1.00 53 TT +46* | 07-07 G .71 28 TT 0 | 07-08 G 1.00 68 TT +70*  ← 2W GREEN
GOOGL 3d 1/2/0  +23 .50 43  | 07-06 R .20 40 TT -3 | 07-07 G .92 45 TT +42 | 07-08 R .38 44 TT -16  ← shape flip
INTC  3d 0/2/1  -48 .66 38  | 07-06 G .84 44 TT -2* | 07-07 R .77 48 TT -46 | 07-08 G .38 23 TT 0
IWM   3d 0/2/1   -9 .50 36  | 07-06 G .95 42 TT -1* | 07-07 R .02 36 TT -8 | 07-08 G .54 30 TT 0
META  3d 3/0/0  +87 .41 53  | 07-06 R .18 58 TT +3 | 07-07 G .97 39 TT +43 | 07-08 R .08 62 TT +41*  ← 3W
MSFT  3d 0/0/3   0 .47 45  | 07-06 R .37 60 TT 0 | 07-07 R .27 46 TT 0 | 07-08 G .76 28 TT 0  ← flat-heavy
MU    3d 1/0/2 +166 .83 31  | 07-06 R .78 18 TT 0 | 07-07 G .87 22 TT 0 | 07-08 G .84 52 TT +166*
NVDA  3d 1/1/1   -3 .83 36  | 07-06 G .93 24 TT 0 | 07-07 G .61 30 TT +27 | 07-08 G .94 54 TT -30
PFE   3d 0/2/1  -39 .56 47  | 07-06 R .11 76 TT -22 | 07-07 G .70 37 TT -17 | 07-08 R .88 27 TT 0
PLTR  3d 2/1/0  +34 .62 58  | 07-06 G .94 74 TT +1* | 07-07 R .49 42 TT -24 | 07-08 R .42 58 TT +57  ← shape flip
QQQ   3d 1/1/1   +9 .73 25  | 07-06 G .85 22 TT 0 | 07-07 R .53 27 TT -8 | 07-08 G .80 27 TT +17
SOXL  3d 1/0/2 +233 .86 25  | 07-06 G .93 24 TT 0 | 07-07 G .76 22 TT 0 | 07-08 G .90 30 TT +233
SPY   3d 0/0/3   0 .63 20  | 07-06 R .54 20 TT 0 | 07-07 R .53 17 TT 0 | 07-08 G .81 22 TT 0  ← flat-heavy
T     3d 1/1/1  +20 .55 49  | 07-06 R .40 72 TT -22 | 07-07 G .80 40 TT +42 | 07-08 R .44 35 TT 0
TSLA  3d 0/2/1  -21 .55 36  | 07-06 G .76 53 TT -5 | 07-07 R .08 31 TT -16 | 07-08 R .80 23 TT 0
```

**Symbol tags (3d max — revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **3W** | META | +3/+43/+41 across colour flips |
| **2W** | F | Jul6 +46*, Jul8 +70* |
| **big Jul8** | SOXL, MU, AMD, PLTR | |
| **shape flip** | GOOGL, NVDA, PLTR, QQQ, META | |
| **flat-heavy** | MSFT, SPY | 0 nf all 3d |
| **RED win** | AAPL, PLTR, META | |
| **A′ watch** | AMD, SOXL, NVDA, IWM | |
| **deadline-bug** | F, COIN, PLTR, IWM, INTC, MU, META | corr PnL |
| **swap-candidate** | MSFT, SPY | |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-08 SOXL G .90 .88 30 A' +233
2026-07-08 MU    G .84 .80 52 D  +166 *
2026-07-08 AMD   G .87 .77 40 A'  +85
2026-07-08 F     G 1.00 .56 68 D   +70 *
2026-07-08 PLTR  R .42 .20 58 C   +57
2026-07-06 F     G 1.00 .65 53 D   +46 *
2026-07-07 META  G .97 .60 39 D   +43
2026-07-07 GOOGL G .92 .45 45 D   +42
2026-07-07 T     G .80 .73 40 D   +42
2026-07-08 META  R .08 .81 62 A   +41 *
2026-07-07 NVDA  G .61 .10 30 D   +27
2026-07-08 AAPL  R .03 .95 47 A   +23
2026-07-08 QQQ   G .80 .80 27 D   +17
2026-07-06 META  R .18 .60 58 B    +3
2026-07-06 PLTR  G .94 .83 74 A'   +1 *
2026-07-06 COIN  G .65 .39 45 D    +1 *
2026-07-06 IWM   G .95 .91 42 A'   -1 *
2026-07-06 INTC  G .84 .63 44 D    -2 *
2026-07-06 GOOGL R .20 .29 40 D    -3
2026-07-06 AAPL  G .76 .69 32 D    -3
2026-07-06 TSLA  G .76 .05 53 D    -5
2026-07-07 IWM   R .02 .53 36 B    -8
2026-07-07 QQQ   R .53 .01 27 C    -8
2026-07-06 AMD   G .97 .66 73 D   -10
2026-07-08 BAC   R .51 .14 47 C   -11
2026-07-07 AMZN  R .34 .45 37 C   -15
2026-07-07 TSLA  R .08 .48 31 D   -16
2026-07-08 GOOGL R .38 .03 44 C   -16
2026-07-07 PFE   G .70 .65 37 D   -17
2026-07-06 PFE   R .11 .80 76 A   -22
2026-07-06 T     R .40 .30 72 C   -22
2026-07-07 PLTR  R .49 .05 42 C   -24
2026-07-08 NVDA  G .94 .91 54 A'  -30
2026-07-07 INTC  R .77 .22 48 C   -46
```

`*` = OPEN_DEADLINE exit fill missing. 26 flat omitted.

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-07 | 1 | 20 | 4/7 | +20 | seed, live IB **TT mode** |
| 2026-07-07–08 | 2 | 40 | 12/10 | +655 | omitted Jul6 initially |
| 2026-07-06–08 | 3 | 60 | 16/18 | **+638** | Jul6 ingested; 8× deadline corr; U2 cf +703 |

---
*Agent: north star = §Symbol strategy. US ≠ HK — separate totals/modes. **Respect §Operator status** — do not assume §Recommended config is live. Ingest day → check **`open_deadline_entry_only`**; tag `draft_mode`, `u2-would-skip` on RED cp≥.50; update §Inv-switch, §Symbol roster, §Symbol tags. Also: Symbols, Totals, Patterns, Guard rails, Recommended config, Days, Trades, Validation log. Keep terse.*
