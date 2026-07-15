# HK 15m Opening Bar — Running Log

**Extend:** *"Add today's HK day data to `docs/hk-opening-bar-analysis.md`"*  
**Updated:** 2026-07-15 | **Source:** `~/Library/.../interactive-brokers/sessions` SEHK live IB  
**Inverse baseline (Jul 6–8):** n=3d, 63 sym-days, 32 nf (11W/21L), PnL −2531 HKD | ran inverse  
**TT parity week (Jul 13–14):** n=2d, 42 sym-days, 15 nf (10W/5L), 27 flat, PnL **+3552** HKD | ran **TT** (`invertTradeSide:OFF`, TP:SL **2.0**)  
**Live:** §Operator status — **TT parity week** (day 2 complete)  
**Roster:** §Symbol roster — **next-week swaps planned** (week of Jul 20); 6 never-fills at gate  
**Cross-mkt:** §US→HK lag — hypothesis tracking (US day D → HK next session)

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

## Patterns (n=32 inverse Jul6–8 — hypothesis only; TT week separate)

- **cp separates W/L** — winners close higher; W cp≈.68 vs L cp≈.34
- **RED cp≤.15 inverse short poor** — 1W/8L −2040 (counter: 00148 Jul6 +1809; Jul8 cp=.02 lost −1103)
- **Wide bars win** — W atr 80% vs L 52%; 40–60% atr% dead zone 3W/9L −2334
- **GREEN inverse long > RED short** — 8W/9L vs 3W/12L; Jul8 mostly GREEN bars, still net −3511
- **A′ euphoria strong** — 4W/0L/1F +1675 (00939 Jul8, 02318 Jul8, 00700/03690 Jul7)
- **Not:** colour alone, day-level switch, grind(B) as filter
- **Symbol > day** — same sym, different bar → different outcome (00148, 01888, 03690); track per §Symbols
- **TT week add (Jul13–14):** RED A → TT long **5W/0L +3601** on Jul14 fills; soft B cp=.15 b<.70 lost. Mode draft formula gaining support under live TT.
- **US→HK lag:** inv week = mode-flip (inv lose ≈ TT win); **1st live TT↔TT** US Jul14 weak → HK Jul15 bad (op). See §US→HK lag.

## US → HK lag (cross-market hypothesis)

**H₀:** US high nf win% → next HK morning good **under the same side convention (TT)**; US low → next HK poor.  
**Lag:** US RTH **D** → next HK session.  
**Mode-adjust:** If HK ran **inverse**, day quality for H₀ is roughly **flipped** vs recorded: inv strong ≈ TT-equiv **weak**, inv bleed ≈ TT-equiv **strong**. (Not exact −1× — Jul13 replay same 3W/4L both modes — but day PnL / “would have won” direction is the working prior.)  
**Metrics:** US nf WR + PnL (always TT in this window). HK: use **live** when TT; use **TT-equiv** when inv. Qual: strong / mixed / weak as before.

### Operator read (2026-07-16)

1. **Inverse week:** only Monday recorded good (+2061). That Monday under TT would likely have **lost**. Tue–Wed recorded bleed while US improved→**strong** — under TT those HK mornings would likely have **won**, matching the US lead. So first week **supports H₀ after mode-flip**, not contradicts it.
2. **TT parity (live, no flip needed):** US Jul14 **bad** → HK Jul15 **bad** (op). First clean same-algo weak→weak.
3. **US Jul15 ingested:** 8%/−343 **weak** → **lean HK Jul16 inverse** (soft tilt). Score after Jul16 close.
4. **Still open:** US Jul13 → HK Jul14 crush; HK Jul16 result.

### Pairing table

| US day | US WR/PnL | US qual | → HK | HK ran | recorded | TT-equiv (for H₀) | align? |
|--------|-----------|---------|------|--------|----------|-------------------|--------|
| 07-06 | 33%/−17 | weak | 07-07 | inv | soft −1081 | **strong** (flip) | ? weak→would-win |
| 07-07 | 36%/+20 | mixed→ok | 07-08 | inv | bad −3511 | **strong** (flip) | **yes** US ok / HK would-win |
| 07-08 | 73%/+635 | **strong** | 07-09 | — | gap | — | — |
| *(prior Fri?)* | — | — | 07-06 | inv | **+2061** | **weak** (flip) | Mon-only inv win = TT-equiv loss |
| 07-10 | — | — | 07-13 | TT | −71 | = recorded | need US |
| 07-13 | — | — | 07-14 | TT | **+3623** | = recorded | need US (strong→strong?) |
| 07-14 | 23%/−255 | **weak** | 07-15 | TT | **bad** (op) | = recorded | **yes** weak→weak — 1st live TT↔TT |
| 07-15 | 8%/−343 | **weak** | 07-16 | TT? | pending | = | lean **inv** (tilt) |

**Verdict (n=3 scorable):** TT-space **2/3 ≈ 67%** follow; live TT↔TT **1/1** (+ Jul16 pending). Holds as a **small edge**, not a proof.

### Use (intended — soft tilt only)

**After US RTH close → next morning HK mode lean** (market-wide or as a tie-break when per-symbol is unclear):

| Prior US day (TT) | HK lean next session |
|-------------------|----------------------|
| **strong** / high nf WR | bias **TT** |
| **weak** / low nf WR | bias **inverse** (or skip aggressively if already on TT and book soft) |

- **Not a hard gate** — small decision edge only; bar shape + per-symbol still primary (§Symbol strategy).
- **Current lean (from US Jul15 weak):** bias **inverse** for HK Jul16; do not auto-flip all 21 on US alone.
- Log whether the tilt was used in Validation log (`lag-tilt: TT|inv|ignored`).

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
00148  5d 1/2/2  -2 .38 34  | 07-06 R .12 76 inv +1809 | 07-07 R .31 27 inv -708 | 07-08 R .02 29 TT -1103 | 07-13 R .74 19 inv flat-liq | 07-14 G .69 20 inv flat-liq
00388  5d 2/3/0 -599 .37 60  | 07-06 R .11 46 inv -437 | 07-07 G .14 35 inv -239 | 07-08 G .76 54 inv -279 | 07-13 G .76 82 inv +257 | 07-14 R .06 81 TT +99  ← TT long A
00700  5d 2/0/3 +559 .62 71  | 07-06 G .62 95 inv +193 | 07-07 G .90 84 TT +366 | 07-08 G .62 80 inv 0 | 07-13 G .91 48 inv flat no-fill | 07-14 R .04 48 TT flat no-fill
00939  5d 2/3/0 -751 .50 61  | 07-06 R .36 48 inv -582 | 07-07 R .10 46 inv -351 | 07-08 G 1.00 108 TT +360 | 07-13 G .75 53 inv -270 | 07-14 R .27 49 inv +92
00992  5d 0/3/2 -1655 .28 63  | 07-06 R .06 61 TT -601 | 07-07 R .19 57 inv 0 | 07-08 R .27 60 inv -489 | 07-13 G .72 79 inv flat-max$ | 07-14 R .15 60 inv -565  ← soft B only L
01299  5d 0/1/4 -370 .51 73  | 07-06 R .50 44 inv 0 | 07-07 R .06 81 TT -370 | 07-08 G .63 61 inv 0 | 07-13 G .87 109 inv flat no-fill | 07-14 G .48 71 inv flat no-fill
01347  5d 0/0/5 0 .42 54  | 07-06 R .26 61 inv 0 | 07-07 G .93 63 TT 0 | 07-08 G .12 59 inv 0 | 07-13 G .67 48 inv flat-max$ | 07-14 R .14 41 inv flat-max$
01810  5d 1/2/2 -424 .44 71  | 07-06 R .22 75 inv 0 | 07-07 G .70 92 inv -697 | 07-08 G .68 60 inv 0 | 07-13 G .59 61 inv -679 | 07-14 R .02 66 TT +952  ← TT long A (shape flip)
01888  5d 2/0/3 +2942 .42 50  | 07-06 R .19 122 inv +2421 | 07-07 R .67 30 inv +521 | 07-08 R .08 44 TT 0 | 07-13 G .49 27 inv flat no-fill | 07-14 G .65 25 inv flat-liq
02318  5d 1/2/2 -166 .46 54  | 07-06 G .56 41 inv -46 | 07-07 R .10 50 inv -197 | 07-08 G .94 57 TT +77 | 07-13 G .48 57 inv flat no-fill | 07-14 R .20 64 inv flat no-fill
02628  5d 1/4/0 -904 .29 49  | 07-06 G .48 39 inv -554 | 07-07 R .11 47 inv -345 | 07-08 G .67 33 inv -366 | 07-13 R .11 36 TT -402 | 07-14 R .08 91 TT +763  ← wide A flip vs Jul13
02899  5d 0/0/5 0 .49 34  | 07-06 G .80 58 inv 0 | 07-07 R .36 34 inv 0 | 07-08 G .52 32 inv 0 | 07-13 R .45 26 inv flat-max$ | 07-14 R .31 22 inv flat-liq
03033  5d 3/2/0 +495 .48 62  | 07-06 R .16 53 inv -142 | 07-07 G .79 77 inv +291 | 07-08 G .64 66 inv -456 | 07-13 G .79 65 inv +554 | 07-14 R .03 51 TT +248
03690  5d 1/1/3 +97 .49 66  | 07-06 R .24 55 inv 0 | 07-07 G 1.00 127 TT +873 | 07-08 G .61 60 inv -776 | 07-13 G .26 49 inv flat no-fill | 07-14 R .36 38 inv flat no-fill
03750  5d 0/0/5 0 .39 42  | 07-06 R .13 60 inv 0 | 07-07 R .14 33 inv 0 | 07-08 R .25 44 inv 0 | 07-13 G .77 55 inv flat-max$ | 07-14 G .66 20 inv flat-liq
06869  5d 0/0/5 0 .22 42  | 07-06 R .12 109 TT 0 | 07-07 R .27 19 inv 0 | 07-08 R .10 40 TT 0 | 07-13 R .33 23 inv flat-liq | 07-14 R .29 18 inv flat-liq
07709  5d 0/0/5 0 .35 19  | 07-06 R .00 18 TT 0 | 07-07 G 1.00 18 inv 0 | 07-08 R .20 23 inv 0 | 07-13 R .25 16 inv flat-liq | 07-14 R .30 21 inv flat-liq
07747  5d 0/0/5 0 .41 20  | 07-06 R .08 17 inv 0 | 07-07 G .99 24 inv 0 | 07-08 R .25 24 inv 0 | 07-13 R .25 14 inv flat-liq | 07-14 R .46 19 inv flat-liq
09618  5d 0/3/2 -1559 .39 59  | 07-06 R .50 56 inv 0 | 07-07 R .07 42 inv -443 | 07-08 G .84 85 inv -672 | 07-13 G .53 48 inv -444 | 07-14 R .00 64 TT flat no-fill
09988  5d 4/0/1 +2865 .43 60  | 07-06 R .27 57 inv 0 | 07-07 G .51 49 inv +218 | 07-08 G .72 49 inv +193 | 07-13 G .57 83 inv +914 | 07-14 R .06 62 TT +1540  ← **4W streak**
09992  5d 1/2/2 -594 .23 64  | 07-06 R .06 65 TT -601 | 07-07 R .19 56 inv 0 | 07-08 R .27 60 inv -489 | 07-13 G .60 69 inv flat no-fill | 07-14 R .04 70 inv +496
```

**Symbol tags (revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **4W** | 09988 | Jul 14 +1540 extends streak (TT short Jul13 → TT long Jul14) |
| **3W** | 03033 | Jul 14 A long +248 |
| **2W** | 00700, 01888, 00388 | 00388 +99 A long today |
| **3L** | 00992, 09618 | 00992 −565 soft B only loser today |
| **shape flip** | 00148, 03033, 00939, 01299, 01810, 02628 | 01810/02628 A TT long won Jul14 after prior losses |
| **flat-heavy** | 00992 | fills when liq but 0/3/2 — watch |
| **swap-candidate** | 01347, 02899, 03750, 06869, 07709, 07747 | **0W/0L, 5F/5d** — **swap gate met** (≥5 consec flat) |
| **tt-switch-strong** | 00148, 06869, 01810, 02628, 09988, 00388, 03033 | Jul14 flooded RED-A TT-draft; 01810/02628/09988 A wins |
| **tt-switch-A′** | 00700, 00939, 03690, 02318, 01347, 01299 | no A′ today (4G only, max cp=.69) |
| **tt-switch-RED-A** | 00148, 09992, 01299, 07709, 07747, 01888, 02628, 01810, 09988, 00388, 03033, 00700, 09618 | Jul14 A TT long **5W/0L +3601** among fills |
| **inv ok wide** | 01888 | still no Jul14 fill |
| **post-euphoria risk** | 03690 | 2F streak after Jul8 loss |
| **TT-week winner** | 09988, 03033, 01810, 02628, 09992, 00388, 00939 | Jul 14 TT longs |

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

**Decision (2026-07-13):** **TT parity week started** — HK ran Touch Turn (not inverse) per planned preset below. Jul 6–8 inverse baseline preserved separately; do not merge TT-week PnL into inverse totals.

**Operator rationale (2026-07-13):** Inverse **Monday Jul 6 was a heady win** (+2061) but **Tue–Fri inverse was poor** for HK (−1081 Jul 7, −3511 Jul 8; net −2531 over 3d). Jul 13 (this Monday, TT) was **near breakeven (−71)** on a similar GREEN-heavy open — operator view: **TT-not-inverse for the full week is the right controlled test**, not a panic flip. Hold TT through Sun; one good inverse Monday does not outweigh four bad inverse days. End-of-week review decides stay vs revert — not mid-week.

**Operator update (2026-07-13 eve):** Replay of Jul 13 captures with **inverse** → **−658, same 3W/4L, same 7 symbols** as live TT (−71). **Global mode ≈ neutral on this day**; symbol-level outcomes diverge sharply (09988 +914 TT vs −725 inv; 01810 opposite). **Next focus: per-symbol mode + roster**, not endless HK-wide TT/inv debate. Parity week continues for uniform data collection.

**Operator update (2026-07-14):** TT parity **day 2** — RED-heavy open (17R/4G), TT longs crushed it: **+3623, 7W/1L**. Archetype **A** fills **5W/0L +3601**. Under inverse, G1 would have *skipped* most of these winners; under TT they are the intended fade. Strongest day-level support yet for §Mode draft A→TT long. Swap-gate met for 6 never-fill names (5F/5d). Continue TT through Sun.

**Schedule:** Jul 6–8 inverse → Jul 9–12 no full HK roster (partial sessions only; excluded) → **Jul 13–14 TT parity week days 1–2**. Continue TT through Sun; end-of-week review vs inverse baseline.

**Live — TT parity week (HK, from 2026-07-13):**

| Setting | Value |
|---------|-------|
| invertTradeSide | **OFF** (Touch Turn) |
| takeProfitToStopLossRatio | **2.0** |
| liquidityRangeDailyAtr | **ON**, 0.25 |
| closePositionGate | **OFF** |
| adjustableTrailingStop | **OFF** |
| openDeadline | **ON**, 90 min |
| entryInwardOffsetRatioOfRange | **0.0** |

Log per sym-day: `recordedPnl`, colour, cp, atr%, `draft_mode`, **actual mode** (TT/inv from bracket side). End of week: compare HK TT vs Jul 6–8 inverse; decide revert vs per-market mode split.

**Why wait on G1:** n=32 inverse-only; G1 applies to inverse short on RED cp≤.15 — not evaluated on TT week fills. Re-run G1 **on inverse history** only if HK reverts to inverse after parity week.

**Promotion gate (revisit when met):** Parity week complete → choose HK **TT stay**, **inverse revert**, or **per-symbol mode**; US evaluated separately (see `us-opening-bar-analysis.md`).

### Live config (what is running now)

All 21 SEHK deployments — **Touch Turn parity preset** (confirmed Jul 13 from bracket sides: GREEN→SHORT, RED→LONG).

| Setting | Live value | Note |
|---------|------------|------|
| invertTradeSide | **OFF** | TT reversal |
| liquidityRangeDailyAtr | **ON**, 0.25 | |
| closePositionGate | **OFF** | G1 not deployed |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | |
| entryInwardOffsetRatioOfRange | **0.0** | |
| takeProfitToStopLossRatio | **2.0** | confirmed from bracket geometry (09988) |

### Watch list while collecting (log in §Days notes)

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| RED cp≤.15 | Under TT = long (not G1 skip). Tag `g1-would-skip` for inv cf | Inv 1W/8L −2040; **Jul14 TT A longs 5W/0L +3601**; Jul13 02628 A −402 (narrow atr%) |
| GREEN A′ (cp≥.85, b≥.70) | Keep trading; monitor fill rate under TT | 01299 cp=.87 Jul13 no-fill; **no A′ Jul14** |
| Ordinary GREEN (not A′) | Monitor; no knob yet | Jul13 TT 3W/3L +331; Jul14 only 4G all flat |
| RED cp .15–.25 | Trade; do not widen G1 to 0.20 | Jul14 00992 soft B cp=.15 −565 only L |
| Symbol repeat losers | 00992, 09618 | 09618 still no win; 01810/02628 flipped Jul14 |
| post-euphoria | 03690 Jul7→Jul8 | 2F streak |
| TT parity week | Log actual mode + draft_mode | Jul13 −71 + Jul14 **+3623** = **+3552** (10W/5L) |
| Fill rate drop | Track brackets-placed-no-fill | Jul14: 5 no-fill / 8 liq-skip / 1 max$ |
| Roster swap (week of Jul 20) | Drop all 6 never-fills; add trials | §Symbol roster — planned 2026-07-15 |
| US→HK lag | Soft tilt only: strong US→HK TT; weak US→HK inv | §US→HK lag · Use; ~67% (2/3); Jul14 weak→Jul15 bad |

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

### Jul 13 synthesis (TT parity week — day 1)

| Metric | Jul 13 live (TT) | Jul 13 replay (inverse) | Jul 8 live (inverse) |
|--------|------------------|-------------------------|----------------------|
| nf W/L | 3W/4L | **3W/4L** | 3W/7L |
| PnL | **−71** | **−658** | **−3511** |
| Fill set | 7 sym | **same 7 sym** | 10 sym |
| Fill rate | 7/21 (33%) | 7/21 | ~11/21 |
| Bar mix | 15G / 6R | same bars | 14G / 7R |

**Replay counterfactual (2026-07-13 evening):** Operator replayed today's captures with **inverse** settings (TP:SL likely 1.0–1.5 per inverse preset). Result: **−658 HKD, 3W/4L — same symbols filled, same W/L count as live TT (−71)**. Both near breakeven; neither resembles Jul 8 inverse bleed (−3511).

**Per-symbol mode flips (live TT vs replay inverse, same bar):**

| Sym | Live TT | Replay inv | Δ |
|-----|---------|------------|---|
| 09988 | +914 | −725 | mode-sensitive **winner** under TT |
| 03033 | +554 | −541 | mode-sensitive **winner** under TT |
| 00388 | +257 | −261 | mode-sensitive **winner** under TT |
| 01810 | −679 | +522 | mode-sensitive **winner** under inv |
| 09618 | −444 | +275 | mode-sensitive **winner** under inv |
| 02628 | −402 | +210 | capitulation: TT long lost, inv short won |
| 00939 | −270 | −138 | loser both modes |

**Config confirmed:** All 7 live fills ran **Touch Turn** (GREEN→SHORT, RED→LONG). Replay ran **inverse** (GREEN→LONG, RED→SHORT). TP:SL **2.0** on live from bracket geometry.

**Conclusions (n=7 — day 1 only, not promotion-grade):**

1. **Global mode ≈ neutral on this day** — TT −71 vs replay inverse −658; **same 7 fills, same 3W/4L**. Day-level PnL is similar; **per-symbol PnL flips sign** on 6/7 names (only 00939 loses both modes). **Implication:** after parity week, priority may shift from HK-wide TT vs inv to **per-symbol mode + roster** (§Symbol strategy north star).
2. **Both modes beat Jul 8 inverse** — Jul 13 TT/replay-inv (−71/−658) vs Jul 8 inverse −3511 on comparable GREEN skew. Jul 13 bar set may simply be easier — or TP:SL 2.0 / different week matters. Do not attribute solely to TT.
3. **Wide bars still separate W/L** — winners avg atr% **77** vs losers **49**; ≥60% bucket 3W/1L **+1045**; 40–60% dead zone 0W/2L **−714**. Pattern from inverse baseline **holds under TT**.
4. **Symbol-level mode registry candidates** — 09988/03033/00388 → TT bias; 01810/09618 → inv bias; 02628 capitulation → inv short (+210) beats TT long (−402). Log `best_mode` per sym when replay diverges.
5. **Fill rate dropped** — 14 flat: 6 brackets placed no fill, 4 `insufficient_max_dollars`, 4 `not_liquidity`. Replay uses same bars → fill set identical across modes today.
6. **Biggest movers** — 09988 +914 TT (but −725 inv replay); 01810 −679 TT (+522 inv replay). **09988 3W streak under live config** — symbol skill > global mode.
7. **G1 / mode-draft** — draft_mode tags 02628 TT; replay inv short won. Per-symbol mode draft formula gains support over global switch.
8. **Do not over-read** — 1 day, 7 fills. Continue TT parity week for clean logging; **end state may be per-symbol mode split**, not global TT or global inverse.

**Operator read (day 1):** Jul 6 inverse +2061 was an outlier Monday; Jul 7–8 inverse bleed is the problem regime. Jul 13 TT −71 is encouraging, but **replay inverse −658 on same bars** means global mode is not the whole story — **focus next on symbols that consistently fit TT vs inv** (and drop never-fill names). Parity week still worth running for uniform logging; decision at week end may be **per-symbol mode** not market-wide flip.

**North star update (2026-07-13):** §Symbol strategy — `(symbol history) + bar shape → mode/skip` promoted from hypothesis to **primary promotion path** after replay showed day-level mode cancellation with symbol-level divergence.

### Jul 14 synthesis (TT parity week — day 2)

| Metric | Jul 14 live (TT) | Jul 13 live (TT) | Jul 6 live (inverse) |
|--------|-----------------|-----------------|----------------------|
| nf W/L | **7W/1L** | 3W/4L | 3W/6L |
| PnL | **+3623** | −71 | +2061 |
| Fill set | 8 sym | 7 sym | ~9 sym |
| Fill rate | 8/21 (38%) | 7/21 (33%) | ~43% |
| Bar mix | **17R / 4G** | 15G / 6R | mostly mixed |

**Config:** All 8 fills ran **Touch Turn** (RED→LONG). No GREEN fills (4G bars all flat: 3 liq, 1 no-fill).

**Winners (all RED TT long):** 09988 +1540, 01810 +952, 02628 +763, 09992 +496, 03033 +248, 00388 +99, 00939 +92  
**Only loser:** 00992 −565 (cp=.15, b=.64, arch **B** — soft capitulation, not full A)

**Archetype A (cp≤.15 ∧ b≥.70) fills:** 5W/0L **+3601** (09988, 01810, 02628, 03033, 00388). Two more A bars flat no-fill (00700, 09618).

**Conclusions (n=8 — day 2, still not promotion-grade alone):**

1. **Best TT day so far** — +3623 on a capitulation-heavy RED open. Opposite colour skew from Jul 13; TT handled both regimes without mid-week mode flip.
2. **Mode draft A→TT long strongly supported** — filled A bars 5W/0L +3601. Under **inverse**, G1 would have *skipped* these (same RED cp≤.15 bars). Under TT they are the fade. Jul 14 is the clearest day-level separation of "G1 skip inverse short" vs "TT long" yet.
3. **Wide A beats narrow A** — 02628 Jul13 A atr%36 −402 vs Jul14 A atr%91 +763 (same shape family, ATR size matters). Do not treat all capitulation bars equal.
4. **Soft B ≠ A** — 00992 cp=.15 b=.64 lost −565; only loser. Keep A body gate ≥.70.
5. **09988 continues to print** — +1540 TT long after +914 TT short Jul13; **4W streak**, colour/mode-side flips with bar — symbol skill.
6. **01810 shape flip** — Jul13 GREEN TT short −679 (replay inv +522) → Jul14 RED A TT long +952. Mode registry must be **per bar shape**, not sticky symbol label.
7. **TT week cumulative** — 2d, 15 nf, **10W/5L, +3552**. Already exceeds Jul 6–8 inverse 3d (−2531) in absolute PnL; different bar regimes — do not declare victory mid-week.
8. **Roster** — 6 swap-candidates now **5F/5d** (swap gate met). Prioritize replacements over more global mode debate.
9. **Do not over-read** — one exceptional RED day; need Wed–Fri before end-of-week call. A′ still untested under TT fills this week.

**Operator read (day 2):** RED open + TT longs delivered the upside the mode draft predicted for capitulation bars. Hold TT; use evening to shortlist roster replacements for the 6 never-fills.

**Recommended preset:** documented in §Recommended config — **not live** until promotion gate met.

### Symbol roster (swap policy — memory)

**Intent (2026-07-08):** May **remove low-activity symbols and add new ones** instead of (or before) rule changes. Priority: names that **never get a fill** — they consume a deployment slot but contribute no shape/PnL data.

**Swap candidates (Jul 6–14, 0 fills all 5 days — gate met):** `01347`, `02899`, `03750`, `06869`, `07709`, `07747` (6 of 21).

| Sym | 5d W/L/F | PnL | Why candidate |
|-----|----------|-----|---------------|
| 01347 | 0/0/5 | 0 | never opened; had A′ Jul7 + A-ish Jul14 (max$) |
| 02899 | 0/0/5 | 0 | never opened |
| 03750 | 0/0/5 | 0 | never opened |
| 06869 | 0/0/5 | 0 | never opened; wide bar Jul6 still flat |
| 07709 | 0/0/5 | 0 | never opened |
| 07747 | 0/0/5 | 0 | never opened |

**Action (2026-07-14):** Swap gate **met** (≥5 consecutive flat).

### Next-week roster plan (week of Jul 20 — planned 2026-07-15)

**Drop all 6** (gate met — execute together when replacements ready):

| Drop | Replace with | Status |
|------|--------------|--------|
| **01347** | TBD | planned |
| **02899** | TBD | planned |
| **03750** | TBD | planned |
| **06869** | TBD | planned |
| **07709** | TBD | planned |
| **07747** | TBD | planned |

**If staged:** prefer first batch `02899, 03750, 07709, 07747`; hold `01347/06869` one more week only if still stress-testing wide-bar-no-fill.

**Hold (do not swap):** 00700, 01888, 09988, 03033 (winners); 00148, 00939, 01810, 02628 (shape-flip); 00388, 09992 (fill data); 00992, 01299, 09618, 02318, 03690 (need more days).

**Swap gate (suggested):** ≥**5 consecutive HK sessions** with `positionOpened=false` (or 3/3 flat if fewer days available) **and** bar often fails liquidity **or** brackets placed but never filled → drop; add replacement with similar sector/liquidity (log new sym in registry from day 1).

**On swap:** Remove deployment; add new symbol; note `roster: dropped XXXX → YYYY (date)` in Validation log. Recompute totals — dropped symbols don't affect traded PnL but reduce wasted sym-days.

**Replacements:** TBD — prefer liquid SEHK names with opening-range ≥25% ATR more often than swap list. Fill TBD cells above when chosen.

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
| 2026-07-13 | 21 | 3/4 | −71 | 55 | **TT parity d1**; 15G/6R; 09988 +914, 01810 −679; 7 fills / 14 flat |
| 2026-07-14 | 21 | 7/1 | +3623 | 66 | **TT parity d2**; 17R/4G; A longs crush; 09988 +1540, 01810 +952; only L 00992 −565 |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-14 09988 R .06 .91 62 A +1540
2026-07-14 01810 R .02 .79 66 A +952
2026-07-14 02628 R .08 .90 91 A +763
2026-07-14 09992 R .04 .67 70 B +496
2026-07-14 03033 R .03 .87 51 A +248
2026-07-14 00388 R .06 .84 81 A +99
2026-07-14 00939 R .27 .64 49 C +92
2026-07-14 00992 R .15 .64 60 B -565
2026-07-13 09988 G .57 .48 83 D-G +914
2026-07-13 03033 G .79 .42 65 D-G +554
2026-07-13 00388 G .76 .67 82 D-G +257
2026-07-13 02628 R .11 .70 36 A -402
2026-07-13 00939 G .75 .67 53 D-G -270
2026-07-13 09618 G .53 .32 48 D-G -444
2026-07-13 01810 G .59 .28 61 D-G -679
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
| 2026-07-13 | 1 | 21 | 3/4 | −71 | **TT parity d1**; replay inv **−658** same fills; symbol-level mode divergence |
| 2026-07-14 | 1 | 21 | 7/1 | +3623 | **TT parity d2**; 17R/4G; A TT longs 5W/0L +3601; swap gate 5F/5d |
| 2026-07-13–14 | 2 | 42 | 10/5 | +3552 | TT week to date; do not merge with inverse baseline |
| 2026-07-15 | — | — | — | — | **roster plan:** week of Jul 20 drop 01347, 02899, 03750, 06869, 07709, 07747; replacements TBD |
| 2026-07-15 | — | — | — | — | **US→HK lag:** inv week = mode-flip (Mon inv-win≈TT-loss; Tue–Wed inv-bleed≈TT-win w/ US strength); live TT↔TT Jul14→15; need Jul15 nf + US Jul13 |

---
*Agent: north star = §Symbol strategy. **Respect §Operator status** — do not assume recommended config is live. Ingest day → tag `g1-would-skip`, **`draft_mode` TT/inv** per sym-day; update **§TT-switch candidates** counts/tiers and **§Symbol roster** flat streaks. After US ingest or HK day close → update **§US→HK lag** pairing table (US D → next HK). Log roster drops/adds and per-sym TT flips in Validation log. Also: Symbols, Totals, Patterns, Mode draft, Guard rails, Recommended config, Days, Trades, Validation log. **Next:** execute §Next-week roster plan when replacements chosen; ingest US gap Jul10/13 for lag test. Keep terse.*
