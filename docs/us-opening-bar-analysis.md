# US 15m Opening Bar — Running Log

**Extend:** *"Add today's US day data to `docs/us-opening-bar-analysis.md"`*  
**Updated:** 2026-07-20 (eve, post US close) | **Source:** `~/Library/.../interactive-brokers/sessions` SMART live IB  
**Jul 6–8 baseline:** n=3d, 60 sym-days, 34 nf (16W/18L), PnL **+638** USD (corr) | ran **TT**  
**TT parity week (Jul 14–17):** n=4d, 80 sym-days, 51 nf (18W/33L), PnL **−197** USD | ran **TT**  
**Post-parity (Jul 20):** n=1d, 20 sym-days, 10 nf (**5W/5L**), PnL **+185** USD | ran **TT** with **wrong shape gate** (body≥0.5, not U2 cp≥0.50)  
**All ingested:** n=8d, 160 sym-days, 95 nf (39W/56L), PnL **+626** | gap: Jul 9–10, 13 not yet ingested  
**Live:** §Operator status — **fix body gate ASAP**; promote true **U2** (cp); Mon **5W/5L +185** (A′ led)  
**Roster:** §Symbol roster — SPY still thin (1/0/7); COIN **5W**; META **6W/2L**; swaps still planned  
**Cross-mkt:** §US→HK lag — Jul17 mixed→Jul20 HK inv **−6188**; Jul20 US **mixed** (+185) → HK Jul21 soft only

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
| 2026-07-16 | T | 0 | **+10** | SHORT deadline mid |

**Ingest rule:** Pre-fix days → tag `open_deadline_entry_only`, apply **corr*** (TP when confirmed, else deadline quote). **Post-fix days** → trust `recordedPnl`; flag if `entry_only` still appears (regression). **Jul14:** BAC/META/PFE still entry_only → corr* via deadline mid (regression). **Jul15:** all nf `roundTrips=complete` — **no entry_only**; trust `recordedPnl`. **Jul16:** T `entry_only` again → corr* +10 (regression intermittent). **Jul17:** all nf `complete` — **no entry_only**; trust `recordedPnl`. **Jul20:** all nf `complete` — trust `recordedPnl`.

## Symbol strategy (target — north star)

**At 09:45 ET, per US symbol:** `(symbol history) + (15m bar shape) + (atr% vs daily ATR)` → **mode** (TT / inv / skip) + symbol guard rails.  
Calibrate **separately from HK** — thresholds and mode bias may differ.

**Roster:** Hold **20 active US symbols**. Flat-heavy names (never fill or rarely trade) are swap candidates — see §Symbol roster.

## Totals (non-flat unless noted)

| | W avg | L avg | Δ |
|--|-------|-------|---|
| cp | .46 | .51 | −.05 |
| b | .61 | .51 | +.10 |
| atr% | 48 | 47 | +1 |
| range | 7.73 | 5.38 | — |

**By color (TT):** RED LONG 50t **19W/31L** +106 | GREEN SHORT 45t **20W/25L** +520  
**ATR buckets:** <40% 32t 8W/24L −155 | 40–60% 36t 19W/17L +516 | ≥60% 17t 7W/10L +80 *(Jul20 not fully rebucketed)*  
**RED cp buckets:** ≤.15 25t 13W/12L +281 | .15–.25 6t 3W/3L −5 | .25–.35 5t 1W/4L −54 | .35–.50 6t 1W/5L −43 | .50+ **5t 0W/5L −96**  
**Archetype (incl flat):** A 7W/7L/5F | B 6W/4L/7F | C 4W/16L/15F | D-R 2W/4L/5F | A′ **7W/7L/4F** | D-G 13W/18L/27F

## Patterns (n=95 — hypothesis only; Jul 9–10/13 gap)

- **GREEN TT short leads hard after Mon** — 20W/25L **+520** vs RED 19W/31L +106; Jul20 GREEN book **+184** (A′ INTC +159)
- **A′ euphoria TT short revived Jul20** — was 4W/7L/1F; now **7W/7L/4F** after INTC/GOOGL/QQQ (+203 filled A′)
- **Wrong body gate live Jul20** — `redSkipBodyRatioAbove=0.5` blocked **AAPL/F/TSLA** (shape-skip); true **U2** (cp≥.50) would have blocked only **META −22**
- **U2 still holds** — RED cp≥.50 now **0W/5L −96** (META Jul20 adds −22); cf all-ingest **+648** vs **+626**
- **cp W/L flipped slightly** — W .46 vs L .51; treat cp as **mode/color-conditional**, not pooled
- **Parity week complete** — 18W/33L **−197** (Jul14–17). Post-parity Mon **+185**
- **META 6W/2L** — second L (−22 U2-shape C); still core (**+107**)
- **COIN 5W** — Jul20 C +56; best win count
- **Wide bars win** — W range 7.73 vs L 5.38 *(pre-Jul20)*
- **Deadline no-fills Mon** — 4/20 (AMD/IWM/NVDA/PLTR); plus 3 shape-skips + 3 no-liq
- **SPY thin** — Jul20 no-liq again; **1 fill / 8d** — swap still on
- **US→HK lag:** Jul17 mixed→Jul20 HK inv **−6188**. Jul20 US **mixed** (+185) → HK Jul21 soft only.

## US → HK lag (lead-day tags)

**Role:** US = **lead** (always TT here). Score next HK in **TT space**: if HK ran inv, flip recorded day quality for H₀.  
**H₀:** US high nf win% → next HK TT-good; US low → next HK TT-poor.  
**Tag:** **strong** ≥60% WR or outlier +PnL | **mixed** 40–59% | **weak** <40% or heavy −PnL.

### Operator read (2026-07-20 eve, post US)

- **Inverse week:** Mon inv good (= TT-equiv **bad**). Tue–Wed inv bled while US mixed→**strong** (= those HK days TT-equiv **won**). First week **supports** H₀ after flip.
- **TT parity live:** weak→weak **2/2** (Jul14→15, Jul15→16). Jul16 **strong** → Jul17 HK **−7165** = **miss**. Live TT↔TT **2/3**.
- **Jul17 US mixed** → HK Jul20 inv **−6188** (soft; TT-equiv would-win) — logged.
- **Jul20 US mixed** (5W/5L +185) → HK Jul21 **soft** only.
- **Still need:** US Jul13 vs HK Jul14 crush.

| US day | WR/PnL | lead | → HK | HK ran | recorded | TT-equiv | for H₀? |
|--------|--------|------|------|--------|----------|----------|---------|
| 07-06 | 33%/−17 | weak | 07-07 | inv | soft | **strong** (flip) | soft |
| 07-07 | 36%/+20 | mixed | 07-08 | inv | bad | **strong** (flip) | **yes** would-win |
| 07-08 | 73%/+635 | **strong** | 07-09 | — | gap | — | — |
| 07-10 | — | — | 07-13 | TT | −71 | = | need US |
| 07-13 | — | — | 07-14 | TT | +3623 | = | **priority** |
| 07-14 | 23%/−255 | **weak** | 07-15 | TT | **−3010** | = | **align** live TT↔TT |
| 07-15 | 8%/−343 | **weak** | 07-16 | TT | **−4368** | = | **align**; tilt inv ignored |
| 07-16 | 64%/+256 | **strong** | 07-17 | TT | **−7165** | = | **miss** strong→weak |
| 07-17 | 45%/+145 | **mixed** | 07-20 | inv | **−6188** | **strong** (flip) | soft — inv bled; TT-equiv would-win |
| 07-20 | 50%/+185 | **mixed** | 07-21 | — | pending | — | soft tilt only |

**Use:** after US close, tag lead qual → HK morning **soft tilt** only (strong US → lean HK **TT**; weak US → lean HK **inv**). Not a hard gate — Jul17 strong→TT wipe proves it. TT-space ~**60%** (n=5) + live TT↔TT **2/3**. See HK §US→HK lag · Use.

**Ingest priority:** US Jul **13** (lag vs HK Jul14). Inv-flip rows soft until replayed.

## Mode draft (UNVALIDATED)

Jul 6–8 ran TT on all. Draft formula (HK parity) tags bars that *would* use inv if we split modes.

| Bar | Draft mode | Note (n=95) |
|-----|------------|--------------|
| GREEN (esp cp≥.60) | **TT** short | 20W/25L **+520**; Jul20 GREEN +184 |
| GREEN archetype **A′** (cp≥.85, b≥.70) | **TT** short | **7W/7L/4F** — Jul20 INTC +159 / GOOGL +29 / QQQ +15 |
| RED archetype **A** (cp≤.15, b≥.70) | **TT** long | **7W/7L/5F** — Jul20 AAPL shape-skipped (wrong body gate) |
| RED cp≥.50 | **inv** or skip? | **0W/5L −96** TT long (U2) — Jul20 META −22 |
| else RED | **monitor** | Jul20 COIN C +56; BAC/PFE L |

```
draft_mode = TT if (RED and cp<=0.15 and b>=0.70) or (GREEN and cp>=0.85 and b>=0.70) else inv
# US currently runs TT on all — inv column is counterfactual target
```

## Guard rails (UNVALIDATED — n=95, TT mode)

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **U1** | TT ∧ RED (all RED longs) | 19W/31L +106 | Jul16–17 RED + Jul20 COIN +56 |
| **U2** | TT ∧ RED ∧ **cp≥.50** | **0W/5L −96** | none |
| **U3** | TT ∧ GREEN ∧ **cp<.50** | 0W/0L | none (n=0) |
| **≠U2** | TT ∧ RED ∧ **body≥.50** | **wrong** — Jul20 blocked AAPL/F/TSLA | would-trade A/B shapes |

**Counterfactual (all ingested, corrected):** U2 → 90t PnL **+648** (vs +626); skips prior 4 + **META −22**. Jul20 body gate ≠ U2. U1 would skip COIN +56 — **reject U1**. **Apply true U2 (cp) when fixed.**

**Apply first:** Fix live gate to **U2 cp≥0.50** (not body). See §Recommended config.

## Operator status (memory — update when decisions change)

**Decision (2026-07-09):** Keep **TT on all 20 US symbols** (`invertTradeSide: false`). Three-day corrected **+638** (Jul6 −17, Jul7 +20, Jul8 +635). GREEN TT short validated. **No cp gate yet.**

**Schedule (2026-07-09):** **This week (through Sun)** — **no config change**; keep live preset below. **From Monday** — switch HK + US to **§TT parity week** preset (after OPEN_DEADLINE fix branch merged).

**OPEN_DEADLINE fix (2026-07-09):** Exit fill drain **fixed on another branch** — merge before Monday parity-week start. Jul 6–8 stats stay corrected; sessions from Monday use `recordedPnl`.

**Planned — TT parity week (from Monday):** HK + US both **Touch Turn** (`invertTradeSide: false`), **TP:SL 2.0**, liq 0.25, **no cp gate**, deadline 90m, trailing OFF — one calendar week, compare markets on identical preset. Revert HK to inverse after week unless stats justify split.

**Operator update (2026-07-14):** TT parity US day — **3W/10L −255** (corr*). RED soft-B + A′ fades. **No midweek config change.**

**Operator update (2026-07-16 morning):** Jul15 US — **1W/12L −343** (clean `recordedPnl`). Worst day in log. RED A cluster failed (SOXL/MU/INTC); GREEN book 0W/6L; only META +24. **Two-day parity bleed −598.** Fill-drain **clean** Jul15. **SPY 5F — swap gate met.** **No midweek config change.**

**Operator update (2026-07-16 eve):** Jul16 US — **9W/5L +256** (T +10* corr). Strong bounce; parity 3d **−342**. RED A cluster **recovered** (PLTR/COIN/META/MSFT); META **6W**; SPY **broke 5F** (+7). GREEN still soft. T `entry_only` regression. U2 idle. **No midweek config change** — finish parity week / Fri.

**Operator update (2026-07-17 eve):** Jul17 US — **5W/6L +145** (fills **clean**). Parity week **complete** −197. Soft-B AMD/INTC +179; META **broke 6W** (−17 A); PLTR A +42; GREEN 1W/4L (−83, only PFE +39). **8/20 deadline no-fills**. U2 idle. **Promotion gate open** — re-run U2 next.

**Operator update (2026-07-20 eve):** Jul20 US — **5W/5L +185** (fills **clean**). Post-parity Mon. **GREEN A′ led** (INTC +159, GOOGL +29, QQQ +15). **Config bug:** live had **`redSkipBodyRatioAbove=0.5`** (not U2 cp) — blocked **AAPL/F/TSLA** shape-skips; true U2 would only skip **META −22**. COIN +56; AMZN broke 0W (+24). SPY no-liq thin. **Fix gate to cp U2 before next session.**

**OPEN_DEADLINE (2026-07-14..20):** Jul14 BAC/META/PFE `entry_only` (corr*). Jul15 all `complete`. Jul16 T `entry_only`. Jul17+20 all nf `complete`.

**Why wait on U2 deploy:** was n/gap; now parity week done + Mon evidence. U2 **0W/5L −96**; Jul20 wrong **body** gate hurt A/B shapes. **Promote true U2 (cp)** — do **not** leave body≥0.5 on.

**Promotion gate (met):** Parity week complete. U2 still 0W on RED cp≥.50. **Tonight's live gate was wrong field** — fix to `redSkipClosePositionAbove=0.50` + clear body skip.

**Ingest gap:** Jul **9–10, 13** US sessions present on disk — not in this log yet (add next; Jul13 = lag priority).

### Live config (what is running now)

All 20 SMART deployments — **Touch Turn**; **shape gate MISCONFIGURED** (body, not cp) as of Jul20 session.

| Setting | Live value (Jul20 ran) | Note |
|---------|------------------------|------|
| invertTradeSide | **OFF** (TT) | RED→LONG, GREEN→SHORT |
| liquidityRangeDailyAtr | **ON**, 0.25 | |
| closePositionGate | **ON** but **wrong field** | body≥0.5 SKIP — **not U2** |
| redSkipBodyRatioAbove | **0.5** SKIP | **REMOVE** — blocked AAPL/F/TSLA |
| redSkipClosePositionAbove | *(empty / not U2)* | **SET 0.50** for true U2 |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | Jul20 nf clean; 4 deadline no-fills |

### Watch list while collecting

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| GREEN cp≥.60 TT short | **Keep — strong** | 20W/25L +520 (Jul20 GREEN +184) |
| GREEN A′ (cp≥.85, b≥.70) | **Keep TT short — revived** | **7W/7L/4F** (Jul20 INTC +159) |
| RED cp≤.15 TT long (A) | Keep; even | 7W/7L/5F — Jul20 AAPL shape-skipped |
| RED soft B (cp≤.25, b≥.50) | Keep | Jul20 TSLA shape-skipped (wrong gate) |
| RED cp≥.50 TT long | **Fix+promote U2** | **0W/5L −96** (META Jul20 −22) |
| Body≥0.5 RED skip | **Disable** — not U2 | Jul20 blocked 3 would-trade bars |
| OPEN_DEADLINE entry_only | Tag + corr PnL | Jul6+8 (8) + Jul14×3 + Jul16 T; Jul17+20 nf clean |
| Deadline no-fill rate | Track | Jul20 **4/20** + 3 shape-skips |
| Symbol shape flips | Track per §Symbols | COIN 5W; META 6W/2L; AMZN broke 0W |
| Roster swap (week of Jul 20) | SPY/AMZN/IWM/TSLA | AMZN +24 softens drop; SPY still thin |
| US→HK lead tag | After US day → HK soft TT/inv tilt | Jul20 **mixed** → HK Jul21 soft |

### Inv-switch candidates (TT → inverse per symbol)

| Sym | inv-draft / ingested | TT PnL on inv-draft days | Tier | Notes |
|-----|----------------------|--------------------------|------|-------|
| *most* | high | mixed | stay TT | GREEN **+520** + A′ Mon |
| INTC | high | … **+86 B**, **+159 A′** | stay TT | Mon A′ crush |
| AMZN | high | … −27, **+24** | watch | broke 0W; softens swap |
| TSLA | high | … flat, shape-skip | watch | swap; Jul20 body-blocked |
| SOXL | inv Jul14–15 | −88, −68, flat×3 | watch | |
| MU | TT draft A Jul15 | −39 A, no-fill×3 | watch | |
| IWM | A′/GREEN | −11, −17, no-fill | watch | swap |
| META | C Jul20 U2 | −17 A, **−22** U2 | stay TT | 6W/2L; U2 would skip 2nd L |
| COIN | C Jul20 | **+56** | stay TT | **5W** |

**Do not inv-flip yet:** lifetime **+626**; GREEN/A′ carrying. **Fix body→cp U2** first.

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

**Counterfactual anchor (all ingested through Jul15, corrected):** U2 → **+114** vs actual **+40**.

### Jul 16 synthesis (TT parity — ingested day)

| Metric | Jul 16 (TT) | Jul 15 | Jul 14 | Jul 6–8 avg/day |
|--------|-------------|-------|-------|----------------|
| nf W/L | **9W/5L** | 1W/12L | 3W/10L | ~5.3W/6L |
| PnL | **+256** | −343 | −255 | **+213** |
| Bar mix | 16R / 4G | 9R/11G | 11R/9G | variable |
| Fill rate | 14/20 (70%) | 65% | 65% | ~57% |
| Avg atr% | 45 | 37 | 45 | ~42 |

**Config:** All fills ran **TT**. T `entry_only` → corr* +10 (deadline mid). AMZN/F/GOOGL deadline no-fill; AAPL/MU/SOXL no-liq.

**Conclusions (n=14 — bounce after wipeout):**

1. **RED A cluster recovered** — PLTR +92, COIN +76, META +34, MSFT +28; INTC −30 / TSLA −16. Cumulative A **6W/6L/2F**. Draft “A → TT long” **rehabilitated** after Jul15.
2. **META 6W** — only undefeated name; A long +34 continues streak.
3. **GREEN still soft** — IWM A′ −11, PFE −31; T A′ +10*. Book did not lead the day.
4. **SPY broke 5F** — +7 on soft-B RED long; consecutive-flat gate reset (still thin / swap-candidate).
5. **U2 idle** — AAPL/F U2-shape but flat (no-liq / no-fill); filter unchanged 0W/4L.
6. **Fill-drain intermittent** — T entry_only after Jul15 clean day.
7. **Parity 3d −342** — Jul16 erased most of −598 two-day bleed; still do **not** midweek-flip; finish week + Jul13 ingest.

**Counterfactual anchor (all ingested, corrected):** U2 → **+370** vs actual **+296**.

### Jul 17 synthesis (TT parity — ingested day; week complete)

| Metric | Jul 17 (TT) | Jul 16 | Jul 15 | Jul 14 | Jul 6–8 avg/day |
|--------|-------------|-------|-------|-------|----------------|
| nf W/L | **5W/6L** | 9W/5L | 1W/12L | 3W/10L | ~5.3W/6L |
| PnL | **+145** | +256 | −343 | −255 | **+213** |
| Bar mix | 13R / 7G | 16R/4G | 9R/11G | 11R/9G | variable |
| Fill rate | 11/20 (55%) | 70% | 65% | 65% | ~57% |
| Avg atr% | 51 | 45 | 37 | 45 | ~42 |

**Config:** All fills ran **TT**. **No** `entry_only` — all nf `roundTrips=complete`. Trust `recordedPnl`. **8/20** brackets at OPEN_DEADLINE with **no entry fill**.

**Conclusions (n=11 — Fri close; parity week done):**

1. **Soft-B RED led** — AMD +93, INTC +86; only filled B winners. A mixed: PLTR +42 / META −17; NVDA/TSLA A no-fill.
2. **META broke 6W** — first loss (−17 A). Still core (**6W/1L +129**).
3. **GREEN soft again** — 1W/4L (−83); only PFE +39. F −53 worst of day.
4. **U2 idle** — no RED cp≥.50; filter unchanged 0W/4L −74. Cf all-ingest **+515** vs **+441**.
5. **Deadline no-fill tax** — 40% of book never entered despite bracket submit; SPY no-liq thin again.
6. **Parity week −197** — not a wipeout week after Thu–Fri recovery; **no global inv flip**. Promote **U2** next.
7. **US→HK:** Jul16 strong→Jul17 HK miss already logged; Jul17 **mixed** → Jul20 soft only.

**Counterfactual anchor (all ingested, corrected):** U2 → **+515** vs actual **+441**.

### Jul 20 synthesis (post-parity — TT, wrong body gate)

| Metric | Jul 20 (TT*) | Jul 17 | Jul 16 | Jul 6–8 avg/day |
|--------|-------------|-------|-------|----------------|
| nf W/L | **5W/5L** | 5W/6L | 9W/5L | ~5.3W/6L |
| PnL | **+185** | +145 | +256 | **+213** |
| Bar mix | **7R / 13G** | 13R/7G | 16R/4G | variable |
| Fill rate | 10/14 placed (50% of book) | 55% | 70% | ~57% |
| Avg atr% | 35 | 51 | 45 | ~42 |

**Config:** TT. **Wrong gate:** `redSkipBodyRatioAbove=0.5` (not U2 cp). All nf `complete`. Trust `recordedPnl`.

**Shape-skips (body gate):** AAPL R .15/.79, F R .39/.61, TSLA R .16/.75 — true U2 would **allow** all three.  
**U2 would-skip:** META R .74/−22 only among fills.  
**No-liq:** MU/SOXL/SPY. **Deadline no-fill:** AMD/IWM/NVDA/PLTR.

**Conclusions (n=10):**

1. **A′ TT short revived** — INTC +159, GOOGL +29, QQQ +15 (**+203**); PLTR A′ no-fill. Cumulative A′ **7W/7L/4F**.
2. **GREEN book led** — 4W/2L **+184** vs RED 1W/3L +1 (only COIN +56).
3. **Wrong body gate ≠ U2** — blocked 3 RED A/B/C shapes; let META U2-loser through. Fix field before claiming U2 live.
4. **U2 still holds** — 0W/5L **−96**; cf all-ingest **+648** vs **+626**.
5. **COIN 5W** / **META 6W/2L** (−22). AMZN broke 0W (+24) — softens swap case.
6. **SPY 1 fill / 8d** — still thin; swap list stands (AMZN less urgent).
7. **US→HK:** Jul20 **mixed** → HK Jul21 soft only.

**Counterfactual anchor (all ingested, corrected):** U2 → **+648** vs actual **+626**.

### Symbol roster (swap policy — memory)

**Flat-heavy / thin:** `SPY` 1W/0L/7F +7 — Jul20 no-liq; **1 fill / 8d**.  
**Low activity / weak:** `TSLA` (0W/3L/5F −37), `IWM` (0W/5L/3F −44). `AMZN` **1W/3L/4F −45** (broke 0W Mon) — demote urgency.  
**Keep (core):** `COIN` (**5W/1L** +203), `META` (**6W/2L** +107), `INTC` (+130 after A′), `PLTR` (+113), `AMD` (+132), `MU` (+92), `SOXL` (+77).

**Swap gate (suggested):** ≥**5 consecutive US sessions** flat **and** bar often fails liquidity → drop. **SPY still meets thinness** — keep on drop list.

### Next-week roster plan (week of Jul 20 — in progress)

**Goal:** free dead/weak slots before **2026-08-11** live cutover; paper stays on remaining 16 + new trials.

| Priority | Drop | 8d W/L/F | PnL | Why |
|----------|------|----------|-----|-----|
| 1 | **SPY** | 1/0/7 | +7 | thin (1 fill / 8d); Jul20 no-liq |
| 2 | **IWM** | 0/5/3 | −44 | weak; no Mon fill |
| 3 | **TSLA** | 0/3/5 | −37 | weak / flat-heavy; Jul20 shape-skip |
| 4 | **AMZN** | 1/3/4 | −45 | **softened** (+24 Mon) — optional |

**Hold:** INTC (A′ Mon +159); META; COIN **5W**; PFE/BAC (U2 data); SOXL/AMD/PLTR/GOOGL/NVDA/QQQ/T/MSFT/F.

**Replacements:** TBD. Log `roster: dropped X → Y (date)` when executed.

**Aug 11 live shortlist (draft):** Core `COIN, META, INTC, PLTR, AMD, MU` ± `AAPL` / `GOOGL` / `QQQ` (A′ Mon); probation `SOXL`/`F`.

## Recommended US config (all 20 SMART deployments)

**Preset name:** `US Touch Turn + U2` | **Status:** promotion gate **met** — Jul20 ran **wrong field** (body); deploy true **cp** U2 before next session  
**Maps research →** `TouchTurnRuleConfig` cp gate (`redSkipClosePositionAbove`). US runs **TT** (`invertTradeSide` OFF). **Based on Jul 6–8+14–17+20 (n=95; gap 9–10/13).**

### Triggers

| Setting | Value | Note |
|---------|-------|------|
| Require minimum range (× daily ATR) | **ON** | |
| Liquidity range (× ATR) | **0.25** | do not raise |
| Skip when bar is green | **OFF** | GREEN TT short **+520** |
| Skip when bar is red | **OFF** | RED winners exist (COIN 5W; soft-B) |
| Close position (cp) gate | **ON** | |
| Green — skip if cp at or below | *(empty)* | U3 n=0 |
| Green — skip if cp at or above | *(empty)* | **never** — kills A′ (Jul20 +203) |
| Red — skip if cp at or below | *(empty)* | **never** — would skip A |
| Red — skip if cp at or above | **0.50** | **U2** — **0W/5L −96** |
| Red — skip if body at or above | *(empty)* | **never** — Jul20 body≥0.5 blocked AAPL/F/TSLA |
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
| RTH open deadline | **ON**, **90** min | Jul20 nf clean |

### Compact reference

```
Triggers:    liq ON 0.25 | skipGreen OFF | skipRed OFF | cpGate ON
             green cp below/above: — / —
             red cp below: — | red cp above: 0.50
             red body above: —   # NOT 0.50 — that was the Jul20 bug
Execution:   invert OFF (TT) | entryInward 0.0 | TP:SL 2.0
Post-entry:  trailing OFF
Session:     deadline ON 90m
```

### Pseudocode (TT mode)

```
if not liq: skip
if color==RED and cp>=0.50: skip   # U2 (cp — NOT body)
# else: trade TT (RED→long, GREEN→short)
```

### Counterfactual (Jul 6–8+14–17+20, corrected, cp gate as configured)

`redSkipClosePositionAbove=0.50` → skip 5 nf (0W incl META −22); kept 90t **+648** vs actual **+626**.

**Do not use U1** (skip all RED) — skips COIN + soft-B/A winners.

**Do not use body≥0.5 RED skip** — Jul20 bug; blocks A/B shapes U2 would keep.

**Do not use HK G1** (`redSkipClosePositionBelow=0.15`) — skips RED-A.

### Leave off (explicit)

| Setting | Why |
|---------|-----|
| `redSkipClosePositionBelow = 0.15` | blocks RED-A TT long wins |
| `greenSkipClosePositionAbove = 0.85` | blocks A′ TT short (Jul20 +203) |
| `redSkipBodyRatioAbove = 0.5` | **Jul20 misconfig** — not U2 |
| `skipGreenLiquidityBar` / `skipRedLiquidityBar` | colour-only; disproved |
| G2 atr 40–60% skip | US mid-atr still best bucket |
| `invertTradeSide = ON` | inverts GREEN book |
| `fiveMinuteConfirmation` | N/A on current TT path |

### Caveats

- n=95 with Jul 9–10/13 gap; corr* on older entry_only days; Jul15+17+20 nf clean.
- Parity week **−197**; post-parity Mon **+185**. U2 still 0W on cp≥.50.
- Roster: SPY thin; COIN **5W**; META **6W/2L**.

### Live vs recommended (summary)

| | **Live Jul20 (bug)** | **Recommended now** |
|--|----------------------|---------------------|
| invertTradeSide | OFF | OFF |
| closePositionGate | ON (wrong field) | **ON** |
| red **body** above | **0.5** | **— (clear)** |
| red **cp** above | — | **0.50** |
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
| 2026-07-16 | 20 | 9/5 | **+256** | 45 | TT parity bounce; A cluster +184; T +10*; SPY broke 5F |
| 2026-07-17 | 20 | 5/6 | **+145** | 51 | TT parity Fri; soft-B +179; META broke 6W; 8 deadline no-fills; week −197 |
| 2026-07-20 | 20 | 5/5 | **+185** | 35 | post-parity; A′ +203; **wrong body gate** skipped AAPL/F/TSLA; META −22 u2; fills clean |

## Symbols (US registry)

`sym days W/L/F pnl avgcp avratr` — day: `MM-DD col cp atr% [ran_mode] pnl` — `*` = corr PnL  
*(Jul 9–10/13 omitted — gap; 8d ingested)*

```
AAPL  8d 2/2/4   +9 .53 42  | 07-06 G .76 32 TT -3 | 07-07 R .35 60 TT 0 | 07-08 R .03 47 TT +23 | 07-14 G .78 41 TT +11 | 07-15 G .96 43 TT -22 | 07-16 R .50 23 TT 0 | 07-17 G .71 43 TT 0 | 07-20 R .15 45 TT 0 ← shape-skip (body gate)
AMD   8d 3/3/2 +132 .54 43  | 07-06 G .97 73 TT -10 | 07-07 G .73 33 TT 0 | 07-08 G .87 40 TT +85 | 07-14 R .14 56 TT -50 | 07-15 R .47 28 TT -23 | 07-16 R .13 28 TT +37 | 07-17 R .10 57 TT +93 | 07-20 G .87 29 TT 0 ← deadline no-fill
AMZN  8d 1/3/4  -45 .51 47  | 07-06 R .06 53 TT 0 | 07-07 R .34 37 TT -15 | 07-08 R .83 48 TT 0 | 07-14 G .62 46 TT 0 | 07-15 G .43 42 TT -27 | 07-16 R .19 73 TT 0 | 07-17 G .63 49 TT -27 | 07-20 G 1.00 29 TT +24 ← broke 0W
BAC   8d 1/4/3  -23 .64 82  | 07-06 G .86 83 TT 0 | 07-07 G .53 56 TT 0 | 07-08 R .51 47 TT -11 | 07-14 G .84 197 TT -8* | 07-15 G .84 74 TT 0 | 07-16 R .28 75 TT +39 | 07-17 G .97 70 TT -25 | 07-20 R .30 51 TT -18
COIN  8d 5/1/2 +203 .31 43  | 07-06 G .65 45 TT +1* | 07-07 G .34 37 TT 0 | 07-08 G .68 33 TT 0 | 07-14 R .25 45 TT +66 | 07-15 R .10 53 TT -35 | 07-16 R .00 69 TT +76 | 07-17 R .06 36 TT +39 | 07-20 R .37 29 TT +56 ← 5W
F     8d 2/2/4  +44 .63 50  | 07-06 G 1.00 53 TT +46* | 07-07 G .71 28 TT 0 | 07-08 G 1.00 68 TT +70* | 07-14 R .33 27 TT -19 | 07-15 R .10 24 TT 0 | 07-16 R .50 47 TT 0 | 07-17 G .95 108 TT -53 | 07-20 R .39 43 TT 0 ← shape-skip (body gate)
GOOGL 8d 2/4/2   +0 .56 53  | 07-06 R .20 40 TT -3 | 07-07 G .92 45 TT +42 | 07-08 R .38 44 TT -16 | 07-14 G .86 33 TT -17 | 07-15 G .93 67 TT -35 | 07-16 R .10 59 TT 0 | 07-17 R .05 63 TT 0 | 07-20 G .96 75 TT +29 ← A' W
INTC  8d 2/4/2 +130 .43 34  | 07-06 G .84 44 TT -2* | 07-07 R .77 48 TT -46 | 07-08 G .38 23 TT 0 | 07-14 R .42 18 TT 0 | 07-15 R .12 32 TT -37 | 07-16 R .03 29 TT -30 | 07-17 R .04 46 TT +86 | 07-20 G .88 38 TT +159 ← A' W
IWM   8d 0/5/3  -44 .58 36  | 07-06 G .95 42 TT -1* | 07-07 R .02 36 TT -8 | 07-08 G .54 30 TT 0 | 07-14 G .84 31 TT 0 | 07-15 R .17 28 TT -7 | 07-16 G .86 39 TT -11 | 07-17 G .79 61 TT -17 | 07-20 G .48 26 TT 0 ← deadline no-fill swap
META  8d 6/2/0 +107 .37 43  | 07-06 R .18 58 TT +3 | 07-07 G .97 39 TT +43 | 07-08 R .08 62 TT +41* | 07-14 G .77 43 TT +1* | 07-15 R .18 30 TT +24 | 07-16 R .00 47 TT +34 | 07-17 R .10 37 TT -17 | 07-20 R .74 28 TT -22 ← u2
MSFT  8d 1/4/3  -59 .45 41  | 07-06 R .37 60 TT 0 | 07-07 R .27 46 TT 0 | 07-08 G .76 28 TT 0 | 07-14 G .92 39 TT -28 | 07-15 G .22 37 TT -24 | 07-16 R .02 52 TT +28 | 07-17 R .36 40 TT -15 | 07-20 G .70 29 TT -20
MU    8d 1/2/5  +92 .48 29  | 07-06 R .78 18 TT 0 | 07-07 G .87 22 TT 0 | 07-08 G .84 52 TT +166* | 07-14 R .26 28 TT -35 | 07-15 R .07 27 TT -39 | 07-16 G .12 24 TT 0 | 07-17 R .10 40 TT 0 | 07-20 G .86 25 TT 0 ← no-liq
NVDA  8d 2/1/5  +28 .48 47  | 07-06 G .93 24 TT 0 | 07-07 G .61 30 TT +27 | 07-08 G .94 54 TT -30 | 07-14 R .09 66 TT 0 | 07-15 G .34 38 TT 0 | 07-16 R .09 51 TT +31 | 07-17 R .07 79 TT 0 | 07-20 G .80 36 TT 0 ← deadline no-fill
PFE   8d 1/6/1  -77 .64 48  | 07-06 R .11 76 TT -22 | 07-07 G .70 37 TT -17 | 07-08 R .88 27 TT 0 | 07-14 R .68 42 TT -9* | 07-15 G .68 38 TT -22 | 07-16 G .91 60 TT -31 | 07-17 G .88 64 TT +39 | 07-20 R .30 39 TT -15
PLTR  8d 4/2/2 +113 .50 54  | 07-06 G .94 74 TT +1* | 07-07 R .49 42 TT -24 | 07-08 R .42 58 TT +57 | 07-14 G 1.00 62 TT -55 | 07-15 G .10 37 TT 0 | 07-16 R .05 64 TT +92 | 07-17 R .08 42 TT +42 | 07-20 G .94 50 TT 0 ← deadline no-fill
QQQ   8d 2/2/4  +14 .46 28  | 07-06 G .85 22 TT 0 | 07-07 R .53 27 TT -8 | 07-08 G .80 27 TT +17 | 07-14 R .47 20 TT 0 | 07-15 R .01 23 TT 0 | 07-16 R .05 36 TT -10 | 07-17 R .04 47 TT 0 | 07-20 G .96 25 TT +15 ← A' W
SOXL  8d 1/2/5  +77 .51 26  | 07-06 G .93 24 TT 0 | 07-07 G .76 22 TT 0 | 07-08 G .90 30 TT +233 | 07-14 R .19 30 TT -88 | 07-15 R .14 28 TT -68 | 07-16 R .06 21 TT 0 | 07-17 R .12 31 TT 0 | 07-20 G .93 15 TT 0 ← no-liq
SPY   8d 1/0/7   +7 .51 22  | 07-06 R .54 20 TT 0 | 07-07 R .53 17 TT 0 | 07-08 G .81 22 TT 0 | 07-14 G .90 22 TT 0 | 07-15 G .13 16 TT 0 | 07-16 R .07 28 TT +7 | 07-17 R .13 22 TT 0 | 07-20 G .92 25 TT 0 ← no-liq thin
T     8d 2/4/2  -45 .62 45  | 07-06 R .40 72 TT -22 | 07-07 G .80 40 TT +42 | 07-08 R .44 35 TT 0 | 07-14 R .35 31 TT -24 | 07-15 G .87 43 TT -28 | 07-16 G .91 50 TT +10* | 07-17 G .86 62 TT 0 | 07-20 G .36 29 TT -23
TSLA  8d 0/3/5  -37 .33 33  | 07-06 G .76 53 TT -5 | 07-07 R .08 31 TT -16 | 07-08 R .80 23 TT 0 | 07-14 R .23 22 TT 0 | 07-15 G .48 39 TT 0 | 07-16 R .03 31 TT -16 | 07-17 R .07 30 TT 0 | 07-20 R .16 32 TT 0 ← shape-skip (body gate) swap
```

**Symbol tags (8d ingested — revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **6W/2L** | META | −22 U2 Mon; still core +107 |
| **5W** | COIN | Jul20 C +56 |
| **4W** | PLTR | |
| **3W** | AMD | |
| **2W** | F, NVDA, T, GOOGL, INTC, QQQ, AAPL | INTC A′ +159 Mon |
| **big Jul8 / bled** | SOXL, MU | Jul16–20 flat-heavy |
| **shape flip** | GOOGL, INTC, QQQ, AMZN, META, COIN | A′ Mon + GREEN |
| **flat-heavy / thin** | SPY | 1 fill / 8d |
| **swap-candidate** | SPY, IWM, TSLA (± AMZN) | AMZN softened |
| **A′ W Mon** | INTC, GOOGL, QQQ | +203 |
| **u2-would-skip** | INTC, QQQ, BAC, PFE, **META** | META Jul20 −22 |
| **deadline-bug** | Jul6+8 set + Jul14×3 + Jul16 T | Jul15+17+20 nf clean |
| **shape-skip (body bug)** | Jul20 AAPL/F/TSLA | wrong gate |
| **deadline no-fill** | Jul20 AMD/IWM/NVDA/PLTR | |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-20 INTC  G .88 .72 38 A' +159
2026-07-20 COIN  R .37 .19 29 C   +56
2026-07-20 GOOGL G .96 .90 75 A'  +29
2026-07-20 AMZN  G 1.00 .65 29 D   +24
2026-07-20 QQQ   G .96 .86 25 A'  +15
2026-07-20 PFE   R .30 .35 39 C   -15
2026-07-20 BAC   R .30 .48 51 C   -18
2026-07-20 MSFT  G .70 .18 29 D   -20
2026-07-20 META  R .74 .26 28 C   -22  ← u2
2026-07-20 T     G .36 .18 29 D   -23
2026-07-17 AMD   R .10 .64 57 B   +93
2026-07-17 INTC  R .04 .55 46 B   +86
2026-07-17 PLTR  R .08 .75 42 A   +42
2026-07-17 COIN  R .06 .05 36 D   +39
2026-07-17 PFE   G .88 .70 64 D   +39
2026-07-17 MSFT  R .36 .01 40 C   -15
2026-07-17 META  R .10 .76 37 A   -17
2026-07-17 IWM   G .79 .63 61 D   -17
2026-07-17 BAC   G .97 .29 70 D   -25
2026-07-17 AMZN  G .63 .43 49 D   -27
2026-07-17 F     G .95 .52 108 D   -53
2026-07-16 PLTR  R .05 .82 64 A   +92
2026-07-16 COIN  R .00 .78 69 A   +76
2026-07-16 BAC   R .28 .70 75 C   +39
2026-07-16 AMD   R .13 .08 28 D   +37
2026-07-16 META  R .00 .75 47 A   +34
2026-07-16 NVDA  R .09 .67 51 B   +31
2026-07-16 MSFT  R .02 .92 52 A   +28
2026-07-16 T     G .91 .85 50 A'  +10 *
2026-07-16 SPY   R .07 .63 28 B    +7
2026-07-16 QQQ   R .05 .66 36 B   -10
2026-07-16 IWM   G .86 .84 39 A'  -11
2026-07-16 TSLA  R .03 .95 31 A   -16
2026-07-16 INTC  R .03 .77 29 A   -30
2026-07-16 PFE   G .91 .62 60 D   -31
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

`*` = OPEN_DEADLINE exit fill missing (corr via deadline mid). Jul6–8: 26 flat; Jul14: 7 flat; Jul15: 7 flat; Jul16: 6 flat omitted. Jul17: 9 flat omitted. Jul20: 10 flat omitted (AAPL/F/TSLA shape-skip body gate; MU/SOXL/SPY no-liq; AMD/IWM/NVDA/PLTR deadline no-fill).

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
| 2026-07-16 | — | — | — | — | **US→HK lag:** Jul15 weak → HK Jul16 **−4368** align (2/2 live TT↔TT); tilt inv ignored |
| 2026-07-16 | 1 | 20 | 9/5 | **+256** | TT parity bounce; A cluster +184; META 6W; SPY broke 5F; T +10* entry_only |
| 2026-07-06–08+14–16 | 6 | 120 | 29/45 | **+296** | gap Jul9–10/13; U2 cf +370; parity 3d −342; Jul16 strong → HK Jul17 lean TT |
| 2026-07-17 | — | — | — | — | **US→HK lag:** Jul16 strong → HK Jul17 **−7165 miss** (live TT↔TT 2/3) |
| 2026-07-17 | 1 | 20 | 5/6 | **+145** | TT parity Fri; soft-B AMD/INTC +179; META broke 6W; 8 deadline no-fills; fills clean |
| 2026-07-06–08+14–17 | 7 | 140 | 34/51 | **+441** | gap Jul9–10/13; U2 cf +515; **parity week −197**; Jul17 mixed → HK Jul20 soft |
| 2026-07-20 | 1 | 20 | 5/5 | **+185** | post-parity; A′ INTC/GOOGL/QQQ +203; **body gate bug** skipped AAPL/F/TSLA; META −22 u2; fills clean |
| 2026-07-20 | — | — | — | — | **config:** clear `redSkipBodyRatioAbove`; set U2 `redSkipClosePositionAbove=0.50` before next session |
| 2026-07-06–08+14–17+20 | 8 | 160 | 39/56 | **+626** | gap Jul9–10/13; U2 cf **+648**; Jul20 mixed → HK Jul21 soft |

---
*Agent: north star = §Symbol strategy. US ≠ HK — separate totals/modes. **Respect §Operator status** — Jul20 ran **wrong body gate**; fix to true **U2 cp≥0.50** before next session (clear body skip). Ingest day → check **`open_deadline_entry_only`**; tag `draft_mode`, `u2-would-skip` on RED cp≥.50; tag **US→HK lead qual**. Update §Inv-switch, §Symbol roster, §Symbol tags. Also: Symbols, Totals, Patterns, Guard rails, Recommended config, Days, Trades, Validation log. **Next:** fix U2 field; ingest Jul 9–10, **13**; roster SPY/IWM/TSLA; HK Jul21 soft after mixed US. Keep terse.*
