# US 15m Opening Bar — Running Log

**Extend:** *"Add today's US day data to `docs/us-opening-bar-analysis.md"`*  
**Updated:** 2026-07-07 | **Source:** `~/Library/.../interactive-brokers/sessions` SMART live IB  
**n=1 day, 20 sym-days, 20 symbols, 11 non-flat (4W/7L), 9 flat, PnL +20 USD** | ran: **Touch Turn** (`invertTradeSide` absent/false — RED→LONG, GREEN→SHORT)

## Legend

Same as HK doc: `cp`, `b`, `atr%`, archetypes **A/B/C/D/A′**, W/L/Flat.  
**Mode draft:** `TT` | `inv` — see §Mode draft. US Jul 7 **ran TT** on all symbols.  
Dedupe: 1 session per (date,symbol). Bar: `historical.jsonl` closed refetch. PnL: `session_closed.recordedPnl` (USD).

## Symbol strategy (target — north star)

**At 09:45 ET, per US symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) + symbol guard rails.  
Calibrate **separately from HK** — thresholds and mode bias may differ.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .82 | .42 | +.41 |
| b | .47 | .34 | +.13 |
| atr% | 38 | 37 | +1 |
| range | 4.19 | 3.42 | — |

**By color (TT):** RED LONG 6t **0W/6L** 0% | GREEN SHORT 5t **4W/1L** 80%  
**ATR buckets:** <40% 7t 2W/5L +6 | 40–60% 4t 2W/2L +15 | ≥60% 0t  
**RED cp buckets:** ≤.15 2t 0W/2L −24 | .25–.35 1t 0W/1L −15 | .35–.50 1t 0W/1L −24 | .50+ 2t 0W/2L −54  
**Archetype (incl flat):** A 0W/0L/0 | B 0W/1L/0 | C 0W/4L/3 | D-R 0W/1L/0 | A′ 0W/0L/0 | D-G 4W/1L/9

## Patterns (n=11 — hypothesis only)

- **GREEN TT short >> RED TT long** — 4W/1L vs 0W/6L (one day; may be regime not rule)
- **All 4 wins were GREEN shorts** — GOOGL, META, T, NVDA; cp .61–.97
- **RED TT long lost every fill** — incl high cp INTC (.77 −45), low cp IWM/TSLA
- **atr%** — no HK-style wide-bar edge; W/L both ~37–38% avg
- **Not enough n** for cp/ATR guard rails; HK G1/G2 not tested on US TT

## Mode draft (UNVALIDATED)

Jul 7 suggests **TT on GREEN, question TT on RED** — counterfactual only until replay proves inv on same bars.

| Bar | Draft mode | Jul 7 note |
|-----|------------|------------|
| GREEN (esp cp≥.60) | **TT** short | 4W/1L |
| RED archetype A | **TT** long | no A fills; IWM/TSLA low cp still lost |
| RED cp≥.50 | **inv** or skip? | 0W/2L TT long (QQQ, INTC) |
| else RED | **monitor** | 0W/6L all TT longs |

## Guard rails (UNVALIDATED — n=11, TT mode)

| ID | Skip when | Evidence | Cost |
|----|-----------|----------|------|
| **U1** | TT ∧ RED (all RED longs) | 0W/6L −154 | none (1 day) |
| **U2** | TT ∧ RED ∧ **cp≥.50** | 0W/2L −54 | none |
| **U3** | TT ∧ GREEN ∧ **cp<.50** | 0W/0L | skips no nf trades |

**Do not port HK G1/G2 blindly** — US ran TT not inv. Re-derive per mode.

## Days

| date | n | nf W/L | PnL | avg atr% | note |
|------|---|--------|-----|----------|------|
| 2026-07-07 | 20 | 4/7 | +20 | 35 | all wins GREEN short |

## Symbols (US registry)

`sym days W/L/F pnl avgcp avratr` — day: `MM-DD col cp atr% [ran_mode] pnl`

```
AAPL  1d 0/0/1 0 .35 59  | 07-07 R .35 59 TT 0
AMD   1d 0/0/1 0 .73 33  | 07-07 G .73 33 TT 0
AMZN  1d 0/1/0 -15 .34 36 | 07-07 R .34 36 TT -15
BAC   1d 0/0/1 0 .53 55  | 07-07 G .53 55 TT 0
COIN  1d 0/0/1 0 .34 37  | 07-07 G .34 37 TT 0
F     1d 0/0/1 0 .71 27  | 07-07 R .71 27 TT 0
GOOGL 1d 1/0/0 +41 .92 45 | 07-07 G .92 45 TT +41  ← W
INTC  1d 0/1/0 -45 .77 48 | 07-07 R .77 48 TT -45  ← high cp RED loss
IWM   1d 0/1/0 -7 .02 35  | 07-07 R .02 35 TT -7
META  1d 1/0/0 +42 .97 38 | 07-07 G .97 38 TT +42  ← W
MSFT  1d 0/0/1 0 .27 46  | 07-07 R .27 46 TT 0
MU    1d 0/0/1 0 .87 21  | 07-07 G .87 21 TT 0
NVDA  1d 1/0/0 +26 .61 29 | 07-07 G .61 29 TT +26  ← W
PFE   1d 0/1/0 -16 .70 37 | 07-07 G .70 37 TT -16  ← only G loss
PLTR  1d 0/1/0 -23 .49 42 | 07-07 R .49 42 TT -23
QQQ   1d 0/1/0 -8 .53 27  | 07-07 R .53 27 TT -8
SOXL  1d 0/0/1 0 .76 22  | 07-07 G .76 22 TT 0
SPY   1d 0/0/1 0 .53 17  | 07-07 R .53 17 TT 0
T     1d 1/0/0 +42 .80 40 | 07-07 G .80 40 TT +42  ← W
TSLA  1d 0/1/0 -16 .08 30 | 07-07 R .08 30 TT -16
```

**Tags (1d):** **W** GOOGL,META,NVDA,T | **L** INTC,PLTR,PFE,TSLA,AMZN,QQQ,IWM | **RED 0W** all 6 nf | **GREEN 4W** 5 nf

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-07 INTC R .77 .22 48 C -45
2026-07-07 PLTR R .48 .05 42 C -23
2026-07-07 PFE  G .70 .65 37 D -16
2026-07-07 TSLA R .08 .48 30 D -16
2026-07-07 AMZN R .33 .45 36 C -15
2026-07-07 QQQ  R .53 .01 27 C -8
2026-07-07 IWM  R .02 .53 35 B -7
2026-07-07 NVDA G .61 .10 29 D +26
2026-07-07 GOOGL G .92 .45 45 D +41
2026-07-07 T    G .80 .73 40 D +42
2026-07-07 META G .97 .60 38 D +42
```

9 flat omitted.

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-07 | 1 | 20 | 4/7 | +20 | seed, live IB **TT mode** |

---
*Agent: north star = §Symbol strategy. US ≠ HK — separate totals/modes. Ingest day → Symbols, Totals, Patterns, Guard rails, Days, Trades, Validation log. Keep terse.*
