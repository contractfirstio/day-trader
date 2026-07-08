# US 15m Opening Bar — Running Log

**Extend:** *"Add today's US day data to `docs/us-opening-bar-analysis.md"`*  
**Updated:** 2026-07-09 | **Source:** `~/Library/.../interactive-brokers/sessions` SMART live IB  
**n=2 days, 40 sym-days, 20 symbols, 22 non-flat (12W/10L), 18 flat, PnL +655 USD (corrected)** | ran: **Touch Turn** (`invertTradeSide` absent/false — RED→LONG, GREEN→SHORT)  
**Live:** §Operator status — **TT on all symbols**; §Recommended config **not deployed**; fix OPEN_DEADLINE before cp gate

## Legend

Same as HK doc: `cp`, `b`, `atr%`, archetypes **A/B/C/D/A′**, W/L/Flat.  
**Mode draft:** `TT` | `inv` — see §Mode draft. US Jul 7–8 **ran TT** on all symbols; log **`draft_mode`** per sym-day for future inv candidates.  
Dedupe: 1 session per (date,symbol). Bar: `historical.jsonl` closed refetch.  
**PnL:** `session_closed.recordedPnl` unless §PnL data quality applies — **`corr*`** = TP-inferred when exit fill missing.

## PnL data quality

**Known bug (OPEN_DEADLINE, Jul 8):** Session stops with `stopTrigger=OPEN_DEADLINE`, `roundTrips=entry_only` — entry fill captured, **exit fill absent** from `recordedPnl` (shows 0). Root cause: deadline exit did not drain exit fill into session close (see `OpenDeadlineSessionExit` / `open_deadline_exit`).

| date | sym | recorded | corr | method |
|------|-----|----------|------|--------|
| 2026-07-08 | MU | 0 | **+166** | SHORT TP 921.21 hit (min px 920.34) |
| 2026-07-08 | F | 0 | **+70** | SHORT TP 13.36 hit |
| 2026-07-08 | META | 0 | **+41** | LONG TP 607.18 hit (`position_confirmed_flat` but fill missing) |

**Ingest rule:** Tag sym-days with `open_deadline_entry_only`. Prefer **TP correction** when `prices.jsonl` confirms TP touched; else last-quote at deadline (conservative). Revisit when bug fixed in app.

## Symbol strategy (target — north star)

**At 09:45 ET, per US symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) + symbol guard rails.  
Calibrate **separately from HK** — thresholds and mode bias may differ.

**Roster:** Hold **20 active US symbols**. Flat-heavy names (never fill or rarely trade) are swap candidates — see §Symbol roster.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .69 | .47 | +.22 |
| b | .61 | .35 | +.26 |
| atr% | 45 | 40 | +5 |
| range | 10.58 | 3.31 | — |

**By color (TT):** RED LONG 11t **3W/8L** −23 | GREEN SHORT 11t **9W/2L** +678  
**ATR buckets:** <40% 9t 4W/5L +256 | 40–60% 11t 6W/5L +288 | ≥60% 2t 2W/0L +111  
**RED cp buckets:** ≤.15 4t 2W/2L +40 | .15–.25 0t | .25–.35 1t 0W/1L −15 | .35–.50 3t 1W/2L +17 | .50+ 3t 0W/3L −65  
**Archetype (incl flat):** A 2W/0L/0 | B 0W/1L/0 | C 1W/6L/7F | D-R 0W/1L/0 | A′ 2W/1L/0 | D-G 7W/1L/11F

## Patterns (n=22 — hypothesis only)

- **GREEN TT short >> RED TT long** — 9W/2L +678 vs 3W/8L −23 (stronger after corr)
- **cp separates W/L** — W cp≈.69 vs L cp≈.47
- **Jul8 excellent after corr** — 8W/3L **+635**; 3 wins were `entry_only` bug (MU/F/META)
- **A′ euphoria TT short strong** — 2W/1L +288 (AMD, SOXL; NVDA −30 only loss)
- **RED capitulation (A) TT long works** — 2W/0L +64 (AAPL +23, META +41 corr)
- **RED cp≥.50 TT long still poor** — 0W/3L −65 (INTC, QQQ Jul7; BAC Jul8)
- **Wide bars win** — W range 10.58 vs L 3.31; ≥60% atr 2W/0L +111 (META, MU area)
- **Symbol > day** — PLTR, GOOGL, NVDA, QQQ flip W/L when bar shape changes
- **Not enough n** for hard cp gates; HK G1/G2 not ported — US ran TT not inv

## Mode draft (UNVALIDATED)

Jul 7–8 ran TT on all. Draft formula (HK parity) tags bars that *would* use inv if we split modes.

| Bar | Draft mode | Jul 7–8 note |
|-----|------------|--------------|
| GREEN (esp cp≥.60) | **TT** short | 9W/2L +678 actual |
| GREEN archetype **A′** (cp≥.85, b≥.70) | **TT** short | 2W/1L +288; NVDA −30 monitor |
| RED archetype **A** (cp≤.15, b≥.70) | **TT** long | **2W/0L** +64 (AAPL, META Jul8) |
| RED cp≥.50 | **inv** or skip? | 0W/3L −65 TT long |
| else RED | **monitor** / inv? | 1W/5L −22 excl A |

```
draft_mode = TT if (RED and cp<=0.15 and b>=0.70) or (GREEN and cp>=0.85 and b>=0.70) else inv
# US currently runs TT on all — inv column is counterfactual target
```

## Guard rails (UNVALIDATED — n=22, TT mode)

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **U1** | TT ∧ RED (all RED longs) | 3W/8L −23 | META +41, AAPL +23, PLTR +57 |
| **U2** | TT ∧ RED ∧ **cp≥.50** | 0W/3L −65 | none |
| **U3** | TT ∧ GREEN ∧ **cp<.50** | 0W/0L | none (n=0) |

**Counterfactual (Jul 7–8, corrected):** U1 → 11t PnL **+678** (vs +655); skips three RED winners (+121). **Do not deploy U1** — RED-A wins and PLTR flip matter.

**Apply first:** U2 when promoted. U1 rejected — skips three RED winners. See §Recommended config.

## Operator status (memory — update when decisions change)

**Decision (2026-07-09):** Keep **TT on all 20 US symbols** (`invertTradeSide: false`). Jul8 **+635 corrected** (recorded +358 understated); GREEN TT short validated. **No cp gate yet.** Fix OPEN_DEADLINE fill drain before trusting `recordedPnl` alone.

**Why wait on U1/U2:** n=22; U1 skips three RED winners incl META Jul8 A-win. RED-A now 2W/0L — opposite of Jul7-only read.

**Promotion gate (revisit when met):** OPEN_DEADLINE bug fixed **and** ≥~15 additional non-flat trades → re-run U2 counterfactual on clean `recordedPnl`; deploy **`US Touch Turn + U2`** (§Recommended config) if RED cp≥.50 still 0W.

### Live config (what is running now)

All 20 SMART deployments — **Touch Turn, no cp gate**.

| Setting | Live value | Note |
|---------|------------|------|
| invertTradeSide | **OFF** (TT) | RED→LONG, GREEN→SHORT |
| liquidityRangeDailyAtr | **ON**, 0.25 | |
| closePositionGate | **OFF** | U1/U2 not deployed |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | exit-fill bug Jul8 — fix in progress |

### Watch list while collecting

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| GREEN cp≥.60 TT short | Keep trading | 9W/2L +678 |
| GREEN A′ (cp≥.85, b≥.70) | Keep TT short | 2W/1L +288 |
| RED cp≤.15 TT long (A) | Keep; log `draft_mode` TT | **2W/0L** +64 |
| RED cp≥.50 TT long | Monitor U2 | 0W/3L −65 |
| OPEN_DEADLINE entry_only | Tag + corr PnL until fix | MU/F/META Jul8 |
| Symbol shape flips | Track per §Symbols | PLTR, GOOGL, NVDA, QQQ |
| post-euphoria | NVDA Jul7 +27 → Jul8 A′ −30 | n=1; monitor |

### Inv-switch candidates (TT → inverse per symbol)

**Intent:** Symbols where **draft_mode=inv** on most days but TT fills lose may flip to `invertTradeSide: true` per deployment.

| Sym | inv-draft / 2d | TT PnL on inv-draft days | Tier | Notes |
|-----|----------------|--------------------------|------|-------|
| *most* | 2/2 | mixed | stay TT | Jul8 GREEN day +635 corr |
| INTC | 2/2 | −46, flat | watch | |
| AMZN | 2/2 | −15, flat | watch | |
| TSLA | 2/2 | −16, flat | watch | |

**Do not inv-flip yet:** GREEN TT short 9W/2L.

### Jul 7–8 synthesis (for next agent)

| Hypothesis | Verdict after Jul8 corr |
|------------|-------------------------|
| GREEN TT short > RED TT long | **Stronger** — 9W/2L vs 3W/8L |
| cp separates W/L | **Emerging** (.69 vs .47) |
| RED TT long always bad | **Disproved** — A-type 2W/0L; PLTR +57 |
| RED cp≥.50 TT long bad (U2) | **Stronger** — 0W/3L |
| A′ TT short | **Strong** — SOXL/AMD/MU/F; NVDA only loss |
| atr 40–60% dead zone | **Weaker** — 6W/5L +288 |
| wide bars win | **Emerging** — W range 10.6 vs L 3.3 |
| recordedPnl without corr | **Unreliable** — OPEN_DEADLINE entry_only |

**Counterfactual anchor (corrected):** U1 skip all RED TT long → **+678** vs actual **+655**.

### Symbol roster (swap policy — memory)

**Flat-heavy (2d, 0 nf):** `COIN`, `MSFT`, `SPY` — no fills both days.  
**Not swap candidates:** `SOXL`, `MU`, `AMD`, `F`, `META`, `AAPL`, `PLTR`, `QQQ` — Jul8 contributors.

**Swap gate (suggested):** ≥**5 consecutive US sessions** flat **and** bar often fails liquidity → drop; log replacement in Validation log.

## Recommended US config (all 20 SMART deployments)

**Preset name:** `US Touch Turn + U2` | **Status:** hypothesis — **not deployed**; apply uniformly when promotion gate met (§Operator status)  
**Maps research →** `TouchTurnRuleConfig` cp gate (`redSkipClosePositionAbove`). US runs **TT** (`invertTradeSide` OFF) — **not** HK inverse + G1.

### Triggers

| Setting | Value | Note |
|---------|-------|------|
| Require minimum range (× daily ATR) | **ON** | |
| Liquidity range (× ATR) | **0.25** | do not raise; wide bars correlate with wins |
| Skip when bar is green | **OFF** | 9W/2L GREEN TT short |
| Skip when bar is red | **OFF** | RED-A + PLTR are RED |
| Close position (cp) gate | **ON** | |
| Green — skip if cp at or below | *(empty)* | U3 n=0; monitor only |
| Green — skip if cp at or above | *(empty)* | **never** — kills A′ (SOXL, AMD, MU) |
| Red — skip if cp at or below | *(empty)* | **never** — kills RED-A (AAPL, META 2W/0L) |
| Red — skip if cp at or above | **0.50** | **U2** — skip TT long on upper-half RED close |
| Min gross profit | **0** | |
| Closed-bar refetch settle | **3000** ms | default |

### Execution

| Setting | Value | Note |
|---------|-------|------|
| Invert trade side | **OFF** (TT) | RED→LONG, GREEN→SHORT |
| Entry inward offset (× range) | **0.0** | live IB at bar extreme |
| Entry outward offset (× range) | **0.0** | |
| TP green / red (× range) | **0.382 / 0.382** | defaults |
| TP : SL ratio | **2.0** | app default; Jul8 winners hit TP |

### Post-entry · Session

| Setting | Value | Note |
|---------|-------|------|
| Adjustable trailing stop | **OFF** | |
| RTH open deadline | **ON**, **90** min | deploy only after OPEN_DEADLINE fill-drain fix |

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

### Counterfactual (Jul 7–8, corrected, cp gate as configured)

`redSkipClosePositionAbove=0.50` on liquidity bars → skip 3 nf trades (0W: INTC −46, QQQ −8, BAC −11); kept 19t **+720** vs actual **+655**.

**Do not use U1** (skip all RED) — also skips PLTR +57, META +41, AAPL +23 → cf **+678** (worse than U2).

**Do not use HK G1** (`redSkipClosePositionBelow=0.15`) — skips RED-A winners; opposite of US TT edge.

### Leave off (explicit)

| Setting | Why |
|---------|-----|
| `redSkipClosePositionBelow = 0.15` | blocks RED-A TT long (2W/0L +64) |
| `greenSkipClosePositionAbove = 0.85` | blocks A′ TT short (+288 bucket) |
| `skipGreenLiquidityBar` / `skipRedLiquidityBar` | colour-only; disproved |
| G2 atr 40–60% skip | US mid-atr 6W/5L +288 — unlike HK |
| `invertTradeSide = ON` | inverts 9W/2L GREEN book |
| `fiveMinuteConfirmation` | N/A when TT without confirmation path |

### Caveats

- n=22 corrected — not production-validated; revisit after OPEN_DEADLINE fix + ~1 week clean sessions.
- U2 counterfactual uses corrected PnL; re-run on `recordedPnl` once fill drain ships.
- Ordinary RED C-type mixed (PLTR flip) — U2 does not replace per-symbol shape tracking.
- Roster swaps (`COIN`, `MSFT`, `SPY`) are operational — not part of this preset.

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
| 2026-07-07 | 20 | 4/7 | +20 | 37 | all wins GREEN short |
| 2026-07-08 | 20 | 8/3 | **+635** | 40 | corr: MU +166, F +70, META +41; SOXL +233 |

## Symbols (US registry)

`sym days W/L/F pnl avgcp avratr` — day: `MM-DD col cp atr% [ran_mode] pnl` — `*` = corr PnL

```
AAPL  2d 1/0/1  +23 .19 54  | 07-07 R .35 60 TT 0 | 07-08 R .03 47 TT +23  ← A capitulation W
AMD   2d 1/0/1  +85 .80 36  | 07-07 G .73 33 TT 0 | 07-08 G .87 40 TT +85  ← A′ W
AMZN  2d 0/1/1  -15 .58 42  | 07-07 R .34 37 TT -15 | 07-08 R .83 48 TT 0
BAC   2d 0/1/1  -11 .52 52  | 07-07 G .53 56 TT 0 | 07-08 R .51 47 TT -11
COIN  2d 0/0/2   0 .51 35  | 07-07 G .34 37 TT 0 | 07-08 G .68 33 TT 0  ← flat-heavy
F     2d 1/0/1  +70 .85 48  | 07-07 G .71 28 TT 0 | 07-08 G 1.00 68 TT +70* ← corr W
GOOGL 2d 1/1/0  +26 .65 44  | 07-07 G .92 45 TT +42 | 07-08 R .38 44 TT -16  ← shape flip
INTC  2d 0/1/1  -46 .57 36  | 07-07 R .77 48 TT -46 | 07-08 G .38 23 TT 0
IWM   2d 0/1/1   -8 .28 33  | 07-07 R .02 36 TT -8 | 07-08 G .54 30 TT 0
META  2d 2/0/0  +84 .53 50  | 07-07 G .97 39 TT +43 | 07-08 R .08 62 TT +41* ← 2W; A corr
MSFT  2d 0/0/2   0 .52 37  | 07-07 R .27 46 TT 0 | 07-08 G .76 28 TT 0  ← flat-heavy
MU    2d 1/0/1 +166 .85 37  | 07-07 G .87 22 TT 0 | 07-08 G .84 52 TT +166* ← corr W big
NVDA  2d 1/1/0   -3 .77 42  | 07-07 G .61 30 TT +27 | 07-08 G .94 54 TT -30  ← A′ loss
PFE   2d 0/1/1  -17 .79 32  | 07-07 G .70 37 TT -17 | 07-08 R .88 27 TT 0
PLTR  2d 1/1/0  +33 .45 50  | 07-07 R .49 42 TT -24 | 07-08 R .42 58 TT +57  ← shape flip
QQQ   2d 1/1/0   +9 .67 27  | 07-07 R .53 27 TT -8 | 07-08 G .80 27 TT +17  ← colour flip
SOXL  2d 1/0/1 +233 .83 26  | 07-07 G .76 22 TT 0 | 07-08 G .90 30 TT +233  ← A′ big W
SPY   2d 0/0/2   0 .67 20  | 07-07 R .53 17 TT 0 | 07-08 G .81 22 TT 0  ← flat-heavy
T     2d 1/0/1  +42 .62 38  | 07-07 G .80 40 TT +42 | 07-08 R .44 35 TT 0
TSLA  2d 0/1/1  -16 .44 27  | 07-07 R .08 31 TT -16 | 07-08 R .80 23 TT 0
```

**Symbol tags (2d max — revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **2W** | META | Jul7 G +43, Jul8 R +41 corr |
| **big Jul8** | SOXL, MU, AMD, F, PLTR | +233/+166/+85/+70/+57 |
| **shape flip** | GOOGL, NVDA, PLTR, QQQ | |
| **flat-heavy** | COIN, MSFT, SPY | 0 nf both days |
| **RED win** | AAPL, PLTR, META | RED TT long winners |
| **A′ watch** | AMD, SOXL, NVDA | 2W/1L |
| **deadline-bug** | MU, F, META | Jul8 corr only |
| **swap-candidate** | COIN, MSFT, SPY | |

**Tags (2d):** **W** AAPL,AMD,F,GOOGL,META,MU,NVDA,PLTR,QQQ,SOXL,T | **L** INTC,PFE,TSLA,AMZN,BAC,GOOGL,NVDA,IWM

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-08 SOXL G .90 .88 30 A' +233
2026-07-08 MU    G .84 .80 52 D  +166 *
2026-07-08 AMD   G .87 .77 40 A'  +85
2026-07-08 F     G 1.00 .56 68 D   +70 *
2026-07-08 PLTR  R .42 .20 58 C   +57
2026-07-07 META  G .97 .60 39 D   +43
2026-07-07 GOOGL G .92 .45 45 D   +42
2026-07-07 T     G .80 .73 40 D   +42
2026-07-08 META  R .08 .81 62 A   +41 *
2026-07-07 NVDA  G .61 .10 30 D   +27
2026-07-08 AAPL  R .03 .95 47 A   +23
2026-07-08 QQQ   G .80 .80 27 D   +17
2026-07-07 IWM   R .02 .53 36 B    -8
2026-07-07 QQQ   R .53 .01 27 C    -8
2026-07-08 BAC   R .51 .14 47 C   -11
2026-07-07 AMZN  R .34 .45 37 C   -15
2026-07-07 TSLA  R .08 .48 31 D   -16
2026-07-08 GOOGL R .38 .03 44 C   -16
2026-07-07 PFE   G .70 .65 37 D   -17
2026-07-07 PLTR  R .49 .05 42 C   -24
2026-07-08 NVDA  G .94 .91 54 A'  -30
2026-07-07 INTC  R .77 .22 48 C   -46
```

`*` = OPEN_DEADLINE exit fill missing; PnL from TP + price path. 18 flat omitted.

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-07 | 1 | 20 | 4/7 | +20 | seed, live IB **TT mode** |
| 2026-07-07–08 | 2 | 40 | 12/10 | **+655** | Jul8 +635 corr; 3× OPEN_DEADLINE bug; GREEN 9W/2L |

---
*Agent: north star = §Symbol strategy. US ≠ HK — separate totals/modes. **Respect §Operator status** — do not assume §Recommended config is live. Ingest day → check **`open_deadline_entry_only`** (§PnL data quality); tag `draft_mode`, `u2-would-skip` on RED cp≥.50; update §Inv-switch, §Symbol roster, §Symbol tags. Also: Symbols, Totals, Patterns, Guard rails, Recommended config, Days, Trades, Validation log. Keep terse.*
