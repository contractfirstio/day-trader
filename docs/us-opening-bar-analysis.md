# US 15m Opening Bar — Running Log

**Extend:** *"Add today's US day data to `docs/us-opening-bar-analysis.md"`*  
**Updated:** 2026-07-16 | **Source:** `~/Library/.../interactive-brokers/sessions` SMART live IB  
**Jul 6–8 baseline:** n=3d, 60 sym-days, 34 nf (16W/18L), PnL **+638** USD (corr) | ran **TT**  
**+ Jul 14–15 (TT parity):** n=2d, 40 sym-days, 26 nf (4W/22L), PnL **−598** USD  
**All ingested:** n=5d, 100 sym-days, 60 nf (20W/40L), PnL **+40** | gap: Jul 9–10, 13 not yet ingested  
**Live:** §Operator status — **TT parity week** (US days logged: Jul 14–15; Jul 13 sessions exist, pending ingest)  
**Roster:** §Symbol roster — **SPY hit 5F swap gate**; next-week swaps planned (week of Jul 20); live shortlist target **2026-08-11**  
**Cross-mkt:** §US→HK lag — this doc is the **lead**; HK doc holds the full pairing table

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
| 2026-07-14 | BAC | 0 | **−8** | SHORT deadline mid |
| 2026-07-14 | META | 0 | **+1** | SHORT deadline mid |
| 2026-07-14 | PFE | 0 | **−9** | LONG deadline mid |

**Ingest rule:** Pre-fix days → tag `open_deadline_entry_only`, apply **corr*** (TP when confirmed, else deadline quote). **Post-fix days** → trust `recordedPnl`; flag if `entry_only` still appears (regression). **Jul14:** BAC/META/PFE still entry_only → corr* via deadline mid (regression). **Jul15:** all nf `roundTrips=complete` — **no entry_only**; trust `recordedPnl`.

## Symbol strategy (target — north star)

**At 09:45 ET, per US symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) + symbol guard rails.  
Calibrate **separately from HK** — thresholds and mode bias may differ.

**Roster:** Hold **20 active US symbols**. Flat-heavy names (never fill or rarely trade) are swap candidates — see §Symbol roster.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .65 | .51 | +.14 |
| b | .59 | .49 | +.10 |
| atr% | 46 | 46 | 0 |
| range | 8.95 | 5.88 | — |

**By color (TT):** RED LONG 29t **6W/23L** −411 | GREEN SHORT 31t **14W/17L** +451  
**ATR buckets:** <40% 24t 5W/19L −154 | 40–60% 27t 12W/15L +234 | ≥60% 9t 3W/6L −40  
**RED cp buckets:** ≤.15 10t 2W/8L −211 | .15–.25 5t 2W/3L −71 | .25–.35 4t 1W/3L −3 | .35–.50 6t 1W/5L −52 | .50+ 4t 0W/4L −74  
**Archetype (incl flat):** A 2W/4L/2F | B 2W/3L/1F | C 2W/12L/12F | D-R 0W/4L/2F | A′ 3W/6L/0 | D-G 11W/11L/23F

## Patterns (n=60 — hypothesis only; Jul 9–10/13 gap)

- **GREEN TT short >> RED TT long** — still holds on PnL: 14W/17L +451 vs 6W/23L −411 (both books bled Jul14–15)
- **cp separates W/L** — W cp≈.65 vs L cp≈.51
- **Two-day bleed Jul14–15** — 4W/22L **−598**; worst stretch in log
- **Jul15 wipeout** — 1W/12L **−343**; only winner META soft-B +24; GREEN shorts 0W/6L
- **RED A TT long cracked Jul15** — INTC/MU/SOXL all A lost (−37/−39/−68); cumulative A now **2W/4L/2F** (was 2W/1L)
- **A′ euphoria TT short weak** — 3W/6L; Jul15 AAPL −22, GOOGL −35 (3rd straight GOOGL A′/near-A′ L)
- **RED cp≥.50 TT long poor** — 0W/4L −74 — **U2 still holds** (no new U2 cases Jul15)
- **Wide bars win** — W range 8.95 vs L 5.88
- **Symbol > day** — META still 5W; SOXL/AMD/COIN flip hard by shape
- **Fill-drain Jul15 clean** — all nf `complete`; Jul14 entry_only not repeated
- **SPY 5F** — swap gate met
- **Not enough n** for hard gates beyond U2; finish parity week — **no midweek config change**
- **US→HK lag:** Jul15 US **weak** → lean HK Jul16 **inv**; Jul14 weak → Jul15 HK bad aligned. See §US→HK lag + HK doc.

## US → HK lag (lead-day tags)

**Role:** US = **lead** (always TT here). Score next HK in **TT space**: if HK ran inv, flip recorded day quality for H₀.  
**H₀:** US high nf win% → next HK TT-good; US low → next HK TT-poor.  
**Tag:** **strong** ≥60% WR or outlier +PnL | **mixed** 40–59% | **weak** <40% or heavy −PnL.

### Operator read (2026-07-16)

- **Inverse week:** Mon inv good (= TT-equiv **bad**). Tue–Wed inv bled while US mixed→**strong** (= those HK days TT-equiv **won**). First week **supports** H₀ after flip.
- **TT parity:** Jul14 weak US → Jul15 bad HK (op). Jul15 US **worse** (8%/−343) → lean HK Jul16 **inv**.
- **Still need:** US Jul13 vs HK Jul14 crush; HK Jul16 result to score 2nd live TT↔TT lag.

| US day | WR/PnL | lead | → HK | HK ran | recorded | TT-equiv | for H₀? |
|--------|--------|------|------|--------|----------|----------|---------|
| 07-06 | 33%/−17 | weak | 07-07 | inv | soft | **strong** (flip) | soft |
| 07-07 | 36%/+20 | mixed | 07-08 | inv | bad | **strong** (flip) | **yes** would-win |
| 07-08 | 73%/+635 | **strong** | 07-09 | — | gap | — | — |
| 07-10 | — | — | 07-13 | TT | −71 | = | need US |
| 07-13 | — | — | 07-14 | TT | +3623 | = | **priority** |
| 07-14 | 23%/−255 | **weak** | 07-15 | TT | **bad** (op) | = | **align** live TT↔TT |
| 07-15 | 8%/−343 | **weak** | 07-16 | TT? | pending | = | lean HK **inv** |

**Use:** after US close, tag lead qual → HK morning **soft tilt** only (strong US → lean HK **TT**; weak US → lean HK **inv**). Not a hard gate. Holding ~67% TT-space (n=3) + pending Jul16. See HK §US→HK lag · Use.

**Ingest priority:** US Jul **13**, HK Jul16 nf. Inv-flip rows soft until replayed.

## Mode draft (UNVALIDATED)

Jul 6–8 ran TT on all. Draft formula (HK parity) tags bars that *would* use inv if we split modes.

| Bar | Draft mode | Note (n=60) |
|-----|------------|--------------|
| GREEN (esp cp≥.60) | **TT** short | 14W/17L +451; Jul15 GREEN 0W/6L |
| GREEN archetype **A′** (cp≥.85, b≥.70) | **TT** short | 3W/6L; Jul15 AAPL/GOOGL L |
| RED archetype **A** (cp≤.15, b≥.70) | **TT** long | **2W/4L/2F** — Jul15 A cluster failed |
| RED cp≥.50 | **inv** or skip? | 0W/4L −74 TT long (U2) |
| else RED | **monitor** | Jul14 soft-B + Jul15 META B win only |

```
draft_mode = TT if (RED and cp<=0.15 and b>=0.70) or (GREEN and cp>=0.85 and b>=0.70) else inv
# US currently runs TT on all — inv column is counterfactual target
```

## Guard rails (UNVALIDATED — n=60, TT mode)

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **U1** | TT ∧ RED (all RED longs) | 6W/23L −411 | META +41/+3/+24, AAPL +23, PLTR +57, COIN +66 |
| **U2** | TT ∧ RED ∧ **cp≥.50** | 0W/4L −74 | none |
| **U3** | TT ∧ GREEN ∧ **cp<.50** | 0W/0L | none (n=0) |

**Counterfactual (all ingested, corrected):** U2 → 56t PnL **+114** (vs +40); skips INTC, QQQ, BAC, PFE. Jul15 adds **0** U2 cases. U1 skips META +24 Jul15 + other RED winners — **reject U1**. **Apply U2 when promoted.**

**Apply first:** U2 when promoted. See §Recommended config.

## Operator status (memory — update when decisions change)

**Decision (2026-07-09):** Keep **TT on all 20 US symbols** (`invertTradeSide: false`). Three-day corrected **+638** (Jul6 −17, Jul7 +20, Jul8 +635). GREEN TT short validated. **No cp gate yet.**

**Schedule (2026-07-09):** **This week (through Sun)** — **no config change**; keep live preset below. **From Monday** — switch HK + US to **§TT parity week** preset (after OPEN_DEADLINE fix branch merged).

**OPEN_DEADLINE fix (2026-07-09):** Exit fill drain **fixed on another branch** — merge before Monday parity-week start. Jul 6–8 stats stay corrected; sessions from Monday use `recordedPnl`.

**Planned — TT parity week (from Monday):** HK + US both **Touch Turn** (`invertTradeSide: false`), **TP:SL 2.0**, liq 0.25, **no cp gate**, deadline 90m, trailing OFF — one calendar week, compare markets on identical preset. Revert HK to inverse after week unless stats justify split.

**Operator update (2026-07-14):** TT parity US day — **3W/10L −255** (corr*). RED soft-B + A′ fades. **No midweek config change.**

**Operator update (2026-07-16):** Jul15 US — **1W/12L −343** (clean `recordedPnl`). Worst day in log. RED A cluster failed (SOXL/MU/INTC); GREEN book 0W/6L; only META +24. **Two-day parity bleed −598.** Fill-drain **clean** Jul15 (no entry_only). **SPY 5F — swap gate met.** **Still no midweek config change** — finish parity week; U2 unchanged (0 new cases).

**OPEN_DEADLINE (2026-07-14 vs 15):** Jul14 BAC/META/PFE `entry_only` (corr*). Jul15 all filled trades `complete` — drain looks healthy on this day; Jul14 still stains trust until more clean deadline exits.

**Why wait on U2 deploy:** n=60 with gap days; U2 saves −74 lifetime but Jul14–15 pain was mostly **non-U2** (A/A′/soft shapes). Finish parity week, then re-run.

**Promotion gate (revisit when met):** Parity week complete → re-run U2 on `recordedPnl`; deploy **`US Touch Turn + U2`** if RED cp≥.50 still 0W. (Fill-drain Jul15 clean; still want ≥1 more clean deadline-exit day.)

**Ingest gap:** Jul **9–10, 13** US sessions present on disk — not in this log yet (add next; Jul13 = lag priority).

### Live config (what is running now)

All 20 SMART deployments — **Touch Turn, no cp gate**.

| Setting | Live value | Note |
|---------|------------|------|
| invertTradeSide | **OFF** (TT) | RED→LONG, GREEN→SHORT |
| liquidityRangeDailyAtr | **ON**, 0.25 | |
| closePositionGate | **OFF** | U2 not deployed |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | Jul15 clean completes; Jul14 still had entry_only |

### Watch list while collecting

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| GREEN cp≥.60 TT short | Keep; wounded | 14W/17L +451 (Jul15 0W/6L GREEN) |
| GREEN A′ (cp≥.85, b≥.70) | Keep TT short; **pain** | 3W/6L (AAPL/GOOGL Jul15 L) |
| RED cp≤.15 TT long (A) | Keep; **damaged** | 2W/4L/2F — Jul15 A cluster −144 |
| RED soft B (cp≤.25, b≥.50) | Monitor | Jul14 SOXL/AMD L; Jul15 META B **+24** only win |
| RED cp≥.50 TT long | Monitor U2 | 0W/4L −74 (no Jul15 cases) |
| OPEN_DEADLINE entry_only | Tag + corr PnL | Jul6+8 (8) + Jul14 BAC/META/PFE; Jul15 clean |
| Symbol shape flips | Track per §Symbols | META 5W; SOXL/COIN/AMD flip |
| Roster swap (week of Jul 20) | **SPY 5F gate met**; AMZN/IWM/TSLA | §Symbol roster |
| US→HK lead tag | After US day → HK soft TT/inv tilt | Jul15 weak → lean HK Jul16 **inv** |

### Inv-switch candidates (TT → inverse per symbol)

| Sym | inv-draft / ingested | TT PnL on inv-draft days | Tier | Notes |
|-----|----------------------|--------------------------|------|-------|
| *most* | high | mixed | stay TT | GREEN book +451 still carries (shrunk) |
| INTC | high | −2*, −46, flat, flat, **−37 A** | watch | Jul15 A long lost |
| AMZN | high | flat, −15, flat, flat, **−27** | watch | |
| TSLA | high | −5, −16, flat, flat, flat | watch | |
| SOXL | inv Jul14–15 | −88 soft-B, **−68 A** | watch | 2-day −156 vs Jul8 +233 |
| MU | TT draft A Jul15 | **−39 A** | watch | with INTC/SOXL A cluster |

**Do not inv-flip yet:** finish parity week; GREEN +451 still net-positive despite Jul14–15.

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

**Counterfactual anchor (Jul 6–8 corrected):** U2 → **+703** vs actual **+638**; U1 → **+705** (worse than U2).

### Jul 14 synthesis (TT parity — ingested day)

| Metric | Jul 14 (TT) | Jul 6–8 avg/day |
|--------|-------------|----------------|
| nf W/L | 3W/10L | ~5.3W/6L |
| PnL | **−255** | **+213** |
| Bar mix | 11R / 9G | variable |
| Fill rate | 13/20 (65%) | ~57% |

**Config:** All fills ran **TT**. 3× `entry_only` at OPEN_DEADLINE → corr* (BAC −8, META +1, PFE −9).

**Conclusions:** RED soft-B/C bleed; A′ cracked; U2 +PFE −9*; NVDA A no-fill; MSFT broke flat; fill-drain dirty.

### Jul 15 synthesis (TT parity — ingested day)

| Metric | Jul 15 (TT) | Jul 14 | Jul 6–8 avg/day |
|--------|-------------|-------|----------------|
| nf W/L | **1W/12L** | 3W/10L | ~5.3W/6L |
| PnL | **−343** | −255 | **+213** |
| Bar mix | 9R / 11G | 11R/9G | variable |
| Fill rate | 13/20 (65%) | 65% | ~57% |
| Avg atr% | 37 | 45 | ~42 |

**Config:** All fills ran **TT**. **No** `entry_only` — all nf `roundTrips=complete`. Trust `recordedPnl`.

**Conclusions (n=13 — second consecutive wipeout):**

1. **Only winner META soft-B RED long +24** — same shape class that bled Jul14 (SOXL/AMD); symbol > shape that day.
2. **RED A cluster disaster** — SOXL −68, MU −39, INTC −37 (QQQ A no-liq flat); cumulative A **2W/4L/2F**. Draft “A → TT long” **under pressure**.
3. **GREEN book flat-zero** — 0W/6L (−158); A′ AAPL −22 / GOOGL −35. GREEN edge carried the 3d baseline; parity week erased much of it.
4. **U2 idle** — no RED cp≥.50 filled; filter would not have saved the day.
5. **SPY 5F** — swap gate met (liq fail again, atr% 16).
6. **Fill-drain clean** — strengthens trust vs Jul14; still finish week before promoting U2.
7. **Two-day parity −598** — do **not** midweek-flip to inv globally; wait for week close + Jul13 ingest.

**Counterfactual anchor (all ingested, corrected):** U2 → **+114** vs actual **+40**.

### Symbol roster (swap policy — memory)

**Flat-heavy:** `SPY` **5F/5d** — **swap gate met**.  
**Low activity / weak:** `AMZN` (0W/2L/3F −42), `TSLA` (0W/2L/3F), `IWM` (0W/3L/2F).  
**Keep (core):** `META` (**5W** +112), `F` (+97), `MU` (+92), `SOXL` (+77 shape-flip), `COIN` (+32), `AAPL`.

**Swap gate (suggested):** ≥**5 consecutive US sessions** flat **and** bar often fails liquidity → drop; log replacement in Validation log. **SPY = 5/5 — execute drop when replacement chosen.**

### Next-week roster plan (week of Jul 20 — planned 2026-07-15)

**Goal:** free dead/weak slots before **2026-08-11** live cutover; paper stays on remaining 16 + new trials.

| Priority | Drop | 5d W/L/F | PnL | Why |
|----------|------|----------|-----|-----|
| 1 | **SPY** | 0/0/5 | 0 | **gate met** — 5F, often no-liq |
| 2 | **AMZN** | 0/2/3 | −42 | burns slot (Jul15 −27) |
| 3 | **IWM** | 0/3/2 | −16 | low activity / weak |
| 4 | **TSLA** | 0/2/3 | −21 | low activity / weak |

**Hold (do not swap next week):** INTC / PFE / BAC (U2 data); META (5W); SOXL / AMD / PLTR / GOOGL / NVDA / QQQ / T / MSFT (shape or watch).

**Replacements:** TBD — prefer liquid SMART single names that clear liq 0.25 ATR often; avoid another SPY/IWM-class placeholder. Log each as `roster: dropped X → Y (date)` in Validation log when executed.

**Aug 11 live shortlist (draft — revise after swaps + parity week):** Core `META, F, MU, COIN, AAPL` ± probation `SOXL` if A/soft-B pain contained; AMD probation weaker after Jul14–15.

## Recommended US config (all 20 SMART deployments)

**Preset name:** `US Touch Turn + U2` | **Status:** hypothesis — **not deployed**; apply uniformly when promotion gate met (§Operator status)  
**Maps research →** `TouchTurnRuleConfig` cp gate (`redSkipClosePositionAbove`). US runs **TT** (`invertTradeSide` OFF) — **not** HK inverse + G1. **Based on Jul 6–8+14+15 corrected (n=60; gap 9–10/13).**

### Triggers

| Setting | Value | Note |
|---------|-------|------|
| Require minimum range (× daily ATR) | **ON** | |
| Liquidity range (× ATR) | **0.25** | do not raise |
| Skip when bar is green | **OFF** | GREEN TT short still +451 net |
| Skip when bar is red | **OFF** | RED winners exist (META) |
| Close position (cp) gate | **ON** | |
| Green — skip if cp at or below | *(empty)* | U3 n=0 |
| Green — skip if cp at or above | *(empty)* | **never** — kills A′ |
| Red — skip if cp at or below | *(empty)* | **never** — would skip A (even if Jul15 hurt) |
| Red — skip if cp at or above | **0.50** | **U2** — 0W/4L −74 over ingested days |
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

### Counterfactual (Jul 6–8+14+15, corrected, cp gate as configured)

`redSkipClosePositionAbove=0.50` → skip 4 nf (0W: INTC −46, QQQ −8, BAC −11, PFE −9*); kept 56t **+114** vs actual **+40**. Jul15 unchanged by U2.

**Do not use U1** (skip all RED) — skips META +24 Jul15 + PLTR/AAPL/COIN RED winners historically; Jul14–15 bleed makes U1 look better on paper but destroys the only structural RED edge (META).

**Do not use HK G1** (`redSkipClosePositionBelow=0.15`) — skips RED-A; Jul15 A cluster would have “looked” good to skip but also deletes META-class / prior A wins — do not promote from two bad days.

### Leave off (explicit)

| Setting | Why |
|---------|-----|
| `redSkipClosePositionBelow = 0.15` | blocks RED-A TT long wins (despite Jul15 A pain) |
| `greenSkipClosePositionAbove = 0.85` | blocks A′ TT short |
| `skipGreenLiquidityBar` / `skipRedLiquidityBar` | colour-only; disproved |
| G2 atr 40–60% skip | US mid-atr still best bucket (+234) |
| `invertTradeSide = ON` | inverts GREEN book |
| `fiveMinuteConfirmation` | N/A on current TT path |

### Caveats

- n=60 with Jul 9–10/13 gap; 8× Jul6+8 corr + 3× Jul14 corr*; **Jul15 clean**.
- Jul14–15 **−598** dominates recent variance; U2 still 0W but does not stop A/A′ wipeouts.
- Roster: **SPY 5F gate met**; META sole consistency (5W).

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
| 2026-07-09..10,13 | — | — | — | — | **gap** — sessions on disk, not ingested |
| 2026-07-14 | 20 | 3/10 | −255 | 45 | TT parity; SOXL −88; 3× entry_only corr*; BAC atr% 197 |
| 2026-07-15 | 20 | 1/12 | **−343** | 37 | TT parity; META +24 only; A cluster −144; fills clean |

## Symbols (US registry)

`sym days W/L/F pnl avgcp avratr` — day: `MM-DD col cp atr% [ran_mode] pnl` — `*` = corr PnL  
*(Jul 9–10/13 omitted — gap)*

```
AAPL  5d 2/2/1   +9 .58 45  | 07-06 G .76 32 TT -3 | 07-07 R .35 60 TT 0 | 07-08 R .03 47 TT +23 | 07-14 G .78 41 TT +11 | 07-15 G .96 43 TT -22  ← A'
AMD   5d 1/3/1   +2 .64 46  | 07-06 G .97 73 TT -10 | 07-07 G .73 33 TT 0 | 07-08 G .87 40 TT +85 | 07-14 R .14 56 TT -50 | 07-15 R .47 28 TT -23
AMZN  5d 0/2/3  -42 .46 45  | 07-06 R .06 53 TT 0 | 07-07 R .34 37 TT -15 | 07-08 R .83 48 TT 0 | 07-14 G .62 46 TT 0 | 07-15 G .43 42 TT -27  ← swap
BAC   5d 0/2/3  -19 .72 91  | 07-06 G .86 83 TT 0 | 07-07 G .53 56 TT 0 | 07-08 R .51 47 TT -11 | 07-14 G .84 197 TT -8* | 07-15 G .84 74 TT 0
COIN  5d 2/1/2  +32 .41 43  | 07-06 G .65 45 TT +1* | 07-07 G .34 37 TT 0 | 07-08 G .68 33 TT 0 | 07-14 R .25 45 TT +66 | 07-15 R .10 53 TT -35
F     5d 2/1/2  +97 .63 40  | 07-06 G 1.00 53 TT +46* | 07-07 G .71 28 TT 0 | 07-08 G 1.00 68 TT +70* | 07-14 R .33 27 TT -19 | 07-15 R .10 24 TT 0  ← no-liq
GOOGL 5d 1/4/0  -29 .66 46  | 07-06 R .20 40 TT -3 | 07-07 G .92 45 TT +42 | 07-08 R .38 44 TT -16 | 07-14 G .86 33 TT -17 | 07-15 G .93 67 TT -35  ← A'
INTC  5d 0/3/2  -85 .51 33  | 07-06 G .84 44 TT -2* | 07-07 R .77 48 TT -46 | 07-08 G .38 23 TT 0 | 07-14 R .42 18 TT 0 | 07-15 R .12 32 TT -37  ← A L
IWM   5d 0/3/2  -16 .50 33  | 07-06 G .95 42 TT -1* | 07-07 R .02 36 TT -8 | 07-08 G .54 30 TT 0 | 07-14 G .84 31 TT 0 | 07-15 R .17 28 TT -7  ← swap
META  5d 5/0/0 +112 .43 47  | 07-06 R .18 58 TT +3 | 07-07 G .97 39 TT +43 | 07-08 R .08 62 TT +41* | 07-14 G .77 43 TT +1* | 07-15 R .18 30 TT +24  ← 5W
MSFT  5d 0/2/3  -52 .51 42  | 07-06 R .37 60 TT 0 | 07-07 R .27 46 TT 0 | 07-08 G .76 28 TT 0 | 07-14 G .92 39 TT -28 | 07-15 G .22 37 TT -24
MU    5d 1/2/2  +92 .56 30  | 07-06 R .78 18 TT 0 | 07-07 G .87 22 TT 0 | 07-08 G .84 52 TT +166* | 07-14 R .26 28 TT -35 | 07-15 R .07 27 TT -39  ← A L
NVDA  5d 1/1/3   -3 .58 42  | 07-06 G .93 24 TT 0 | 07-07 G .61 30 TT +27 | 07-08 G .94 54 TT -30 | 07-14 R .09 66 TT 0 | 07-15 G .34 38 TT 0
PFE   5d 0/4/1  -70 .61 44  | 07-06 R .11 76 TT -22 | 07-07 G .70 37 TT -17 | 07-08 R .88 27 TT 0 | 07-14 R .68 42 TT -9* | 07-15 G .68 38 TT -22
PLTR  5d 2/2/1  -21 .59 55  | 07-06 G .94 74 TT +1* | 07-07 R .49 42 TT -24 | 07-08 R .42 58 TT +57 | 07-14 G 1.00 62 TT -55 | 07-15 G .10 37 TT 0
QQQ   5d 1/1/3   +9 .53 24  | 07-06 G .85 22 TT 0 | 07-07 R .53 27 TT -8 | 07-08 G .80 27 TT +17 | 07-14 R .47 20 TT 0 | 07-15 R .01 23 TT 0  ← A no-liq
SOXL  5d 1/2/2  +77 .59 27  | 07-06 G .93 24 TT 0 | 07-07 G .76 22 TT 0 | 07-08 G .90 30 TT +233 | 07-14 R .19 30 TT -88 | 07-15 R .14 28 TT -68  ← A L
SPY   5d 0/0/5    0 .58 19  | 07-06 R .54 20 TT 0 | 07-07 R .53 17 TT 0 | 07-08 G .81 22 TT 0 | 07-14 G .90 22 TT 0 | 07-15 G .13 16 TT 0  ← 5F GATE
T     5d 1/3/1  -32 .57 44  | 07-06 R .40 72 TT -22 | 07-07 G .80 40 TT +42 | 07-08 R .44 35 TT 0 | 07-14 R .35 31 TT -24 | 07-15 G .87 43 TT -28
TSLA  5d 0/2/3  -21 .47 34  | 07-06 G .76 53 TT -5 | 07-07 R .08 31 TT -16 | 07-08 R .80 23 TT 0 | 07-14 R .23 22 TT 0 | 07-15 G .48 39 TT 0  ← swap
```

**Symbol tags (5d ingested — revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **5W** | META | +3/+43/+41/+1*/+24 — only consistency |
| **2W** | F, COIN | COIN Jul15 −35 after Jul14 +66 |
| **big Jul8 / bled** | SOXL, MU, AMD | SOXL Jul14–15 −156 |
| **shape flip** | GOOGL, NVDA, PLTR, QQQ, META, SOXL, AMD, COIN | |
| **flat-heavy / gate** | SPY | **5F/5d — swap** |
| **swap-candidate** | SPY, AMZN, IWM, TSLA | next-week plan |
| **RED win** | META (Jul15), AAPL, PLTR, COIN | |
| **A′ watch** | GOOGL, AAPL, PLTR | Jul15 A′ L |
| **A watch** | SOXL, MU, INTC | Jul15 A cluster L |
| **u2-would-skip** | INTC, QQQ, BAC, PFE | RED cp≥.50 (no new Jul15) |
| **deadline-bug** | Jul6+8 set + Jul14 BAC/META/PFE | Jul15 clean |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-15 META  R .18 .61 30 B   +24
2026-07-15 IWM   R .17 .15 28 D    -7
2026-07-15 AAPL  G .96 .91 43 A'  -22
2026-07-15 PFE   G .68 .55 38 D   -22
2026-07-15 AMD   R .47 .30 28 C   -23
2026-07-15 MSFT  G .22 .00 37 D   -24
2026-07-15 AMZN  G .43 .43 42 D   -27
2026-07-15 T     G .87 .63 43 D   -28
2026-07-15 COIN  R .10 .43 53 D   -35
2026-07-15 GOOGL G .93 .90 67 A'  -35
2026-07-15 INTC  R .12 .77 32 A   -37
2026-07-15 MU    R .07 .82 27 A   -39
2026-07-15 SOXL  R .14 .83 28 A   -68
2026-07-14 COIN  R .25 .28 45 C   +66
2026-07-14 AAPL  G .78 .33 41 D   +11
2026-07-14 META  G .77 .53 43 D    +1 *
2026-07-14 BAC   G .84 .80 197 D   -8 *
2026-07-14 PFE   R .68 .08 42 C    -9 *  ← u2
2026-07-14 GOOGL G .86 .86 33 A'  -17
2026-07-14 F     R .33 .42 27 C   -19
2026-07-14 T     R .35 .13 31 C   -24
2026-07-14 MSFT  G .92 .06 39 D   -28
2026-07-14 MU    R .26 .46 28 C   -35
2026-07-14 AMD   R .14 .56 56 B   -50
2026-07-14 PLTR  G 1.00 .76 62 A' -55
2026-07-14 SOXL  R .19 .75 30 B   -88
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

`*` = OPEN_DEADLINE exit fill missing (corr via deadline mid). Jul6–8: 26 flat; Jul14: 7 flat; Jul15: 7 flat omitted (F/QQQ/SPY no-liq; BAC/NVDA/PLTR/TSLA deadline no-fill).

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-07 | 1 | 20 | 4/7 | +20 | seed, live IB **TT mode** |
| 2026-07-07–08 | 2 | 40 | 12/10 | +655 | omitted Jul6 initially |
| 2026-07-06–08 | 3 | 60 | 16/18 | **+638** | Jul6 ingested; 8× deadline corr; U2 cf +703 |
| 2026-07-14 | 1 | 20 | 3/10 | **−255** | TT parity; 3× entry_only corr*; SOXL −88; U2 +PFE |
| 2026-07-06–08+14 | 4 | 80 | 19/28 | **+383** | gap Jul9–10/13; U2 cf +457 |
| 2026-07-15 | 1 | 20 | 1/12 | **−343** | TT parity; META +24 only; A cluster −144; fills **clean**; SPY 5F gate |
| 2026-07-06–08+14–15 | 5 | 100 | 20/40 | **+40** | gap Jul9–10/13; U2 cf +114; parity 2d −598 |
| 2026-07-15 | — | — | — | — | **roster plan:** week of Jul 20 drop SPY (gate), AMZN, IWM, TSLA; replacements TBD; live target 2026-08-11 |
| 2026-07-16 | — | — | — | — | **US→HK lag:** Jul15 weak (8%/−343) → lean HK Jul16 **inv**; Jul14→Jul15 aligned weak↔weak |

---
*Agent: north star = §Symbol strategy. US ≠ HK — separate totals/modes. **Respect §Operator status** — do not assume §Recommended config is live. Ingest day → check **`open_deadline_entry_only`**; tag `draft_mode`, `u2-would-skip` on RED cp≥.50; tag **US→HK lead qual** and update HK §US→HK lag pairing row for next HK session. Update §Inv-switch, §Symbol roster, §Symbol tags. Also: Symbols, Totals, Patterns, Guard rails, Recommended config, Days, Trades, Validation log. **Next:** ingest Jul 9–10, **13** (lag priority) gap days; execute SPY drop when replacement chosen; score HK Jul16 vs Jul15 US weak lead. Keep terse.*
