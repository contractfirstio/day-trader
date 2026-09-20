# HK 15m Opening Bar — Running Log

**Extend:** *"Add today's HK day data to `docs/hk-opening-bar-analysis.md`"*  
**Updated:** 2026-07-27 | **Source:** `~/Library/.../interactive-brokers/sessions` SEHK live IB  
**Inverse baseline (Jul 6–8):** n=3d, 63 sym-days, 32 nf (11W/21L), PnL −2531 HKD | ran inverse  
**TT parity week (Jul 13–17):** n=5d, 105 sym-days, 36 nf (11W/25L), PnL **−10991** HKD | ran **TT** (`invertTradeSide:OFF`, TP:SL **2.0**)  
**Post-parity inv (Jul 20–24):** n=5d, 80 sym-days, 19 nf (**3W/16L**), PnL **−6579** HKD | ran **inverse**; G1 fields active; **atrLiq 0.7 from Jul22**; Thu–Fri **0 nf** (liq + gross P:L)  
**Inv week-2 (Jul 27):** n=1d, 16 sym-days, 1 nf (**1W/0L**), PnL **+5213** HKD | **first 0.7 fill** (01810); still inv + atrLiq **0.7** + TP:SL **2.33** + minPL **1.4**  
**Live:** §Operator status — **Inverse + atrLiq 0.7** held into Jul27; first wide fill printed; TP:SL **2.33**; minPL **1.4**  
**Roster:** §Symbol roster — **16** active; 01888 **3W +4925**; 01810 Jul27 **+5213**; replacements TBD  
**Cross-mkt:** §US→HK lag — Jul21–23 → Jul22–24 **0 nf** nulls; Jul24 US **0 nf** → Jul27 HK **+5213** (null lag prior)

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

**Roster:** Hold **16 active HK symbols** (was 21; 5 never-fills dropped Jul 20 — replacements TBD). Symbols that **never open a position** (flat every session) are prime candidates to **drop and replace**. Track in §Operator status · §Symbol roster; do not remove winners/shape-flip names without evidence.

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
- **TT week (Jul13–17):** Mon −71 → Tue +3623 → **Wed–Fri wipe −14543** (−3010/−4368/−7165). Week net **−10991** (11W/25L).
- **RED A → TT long cracked Jul15+17** — Jul14 A 5W/0L +3601; Jul15 A **0W/2L −1315**; Jul17 A **0W/4L −5455** (07747 −3577, 00700 −1153). Wide A ≠ free win; **do not promote A→TT**.
- **GREEN A′ → TT short failed Jul16** — first live A′ under TT: **0W/3L −3318** (03033 −2156, 01810 −959, 09992 −203). Inverse A′ was 4W/0L — **do not assume TT fade matches**.
- **US→HK lag:** live TT↔TT weak→weak **2/2**, then Jul16 strong→Jul17 **miss** (−7165). Soft tilt **not** a hard gate. See §US→HK lag.
- **Jul20 inverse Mon bleed:** 13G/3R, **1W/9L −6188** under **inverse**. Only W 00939 +349 (wide atr%106). Contrast Jul6 inv Mon +2061 — GREEN open ≠ free win.
- **A′ inverse long cracked Jul20** — 01299/03033 **0W/2L −1185** (draft TT, ran inv). Inverse baseline A′ was 4W/0L — **do not treat A′ inv long as free**.
- **Jul21 inv mild bleed:** 11G/5R, **2W/7L −391**. Winners **01888 +1983 / 00148 +1345** (narrow GREEN). G1 skipped 09988/09992 (RED soft-B). Better than Mon but still red.
- **Jul22 atrLiq 0.7 (intentional):** RED-heavy 3G/13R, **0W/0L / 0 fills**. Commission-driven raise — trade only very wide opens. Only 00939 cleared liq (atr%110) then no-fill; G1 skipped 00700/01810 A. **Expect sparse days**; score fill quality not fill count.
- **Jul23–24 atrLiq 0.7:** both **0 fills**; one wide clear each day killed by **gross P:L** (00992 ratio 1.49&lt;1.8; 02318 ratio 0.49&lt;1.4 after SEHK fees). Three straight 0-nf days Wed–Fri.
- **Jul27 first 0.7 fill:** **01810 +5213** (GREEN D-G atr%**107**, inv LONG). Gate ratio **1.412** ≥ minPL **1.4** (bare pass). Two other liq clears **gross-PL-skip** (01299 ratio **0.75**; 03690 **1.23**). Exit early at open deadline (fill 28.36→28.58), not full TP.

## US → HK lag (cross-market hypothesis)

**H₀:** US high nf win% → next HK morning good **under the same side convention (TT)**; US low → next HK poor.  
**Lag:** US RTH **D** → next HK session.  
**Mode-adjust:** If HK ran **inverse**, day quality for H₀ is roughly **flipped** vs recorded: inv strong ≈ TT-equiv **weak**, inv bleed ≈ TT-equiv **strong**. (Not exact −1× — Jul13 replay same 3W/4L both modes — but day PnL / “would have won” direction is the working prior.)  
**Metrics:** US nf WR + PnL (always TT in this window). HK: use **live** when TT; use **TT-equiv** when inv. Qual: strong / mixed / weak as before.

### Operator read (2026-07-22, post HK Wed)

1. **Inverse week:** only Monday recorded good (+2061). That Monday under TT would likely have **lost**. Tue–Wed recorded bleed while US improved→**strong** — under TT those HK mornings would likely have **won**, matching the US lead. So first week **supports H₀ after mode-flip**, not contradicts it.
2. **TT parity weak→weak (live):** US Jul14 **weak** → HK Jul15 **−3010**. US Jul15 **weak** → HK Jul16 **−4368**. Still **2/2** on the weak side.
3. **Jul16 lag-tilt ignored** cost −4368 (inv lean right that day).
4. **Jul16 US strong → Jul17 HK:** lean **TT** used (`lag-tilt: TT`). Result **0W/8L −7165** — **strong→strong failed**. Worst day in the HK log. H₀ does **not** hold as a hard rule.
5. **Jul17 US mixed → Jul20 HK:** soft (`lag-tilt: soft`). Inv **1W/9L −6188** — TT-equiv would-win.
6. **Jul20 US mixed → Jul21 HK:** soft. Inv **2W/7L −391** — milder; TT-equiv soft-strong. G1 skipped two RED soft-B.
7. **Jul21 US weak → Jul22 HK:** lean inv + **atrLiq 0.7** → **0 fills** — **null for H₀** this day (filter too tight for the open, not a mode miss).
8. **Jul22 US weak → Jul23 HK:** lean inv + 0.7 → **0 fills** (00992 wide then minPL 1.8 skip) — null.
9. **Jul23 US mixed → Jul24 HK:** soft + 0.7 → **0 fills** (02318 commission-kill) — null.
10. **Jul24 US 0 nf → Jul27 HK:** null prior → HK **1W/0L +5213** under inv 0.7 — **not a lag score** (no US lead).
11. **Still open:** US Jul13 → HK Jul14 crush (need US ingest).

### Pairing table

| US day | US WR/PnL | US qual | → HK | HK ran | recorded | TT-equiv (for H₀) | align? |
|--------|-----------|---------|------|--------|----------|-------------------|--------|
| 07-06 | 33%/−17 | weak | 07-07 | inv | soft −1081 | **strong** (flip) | ? weak→would-win |
| 07-07 | 36%/+20 | mixed→ok | 07-08 | inv | bad −3511 | **strong** (flip) | **yes** US ok / HK would-win |
| 07-08 | 73%/+635 | **strong** | 07-09 | — | gap | — | — |
| *(prior Fri?)* | — | — | 07-06 | inv | **+2061** | **weak** (flip) | Mon-only inv win = TT-equiv loss |
| 07-10 | — | — | 07-13 | TT | −71 | = recorded | need US |
| 07-13 | — | — | 07-14 | TT | **+3623** | = recorded | need US (strong→strong?) |
| 07-14 | 23%/−255 | **weak** | 07-15 | TT | **−3010** | = recorded | **yes** weak→weak |
| 07-15 | 8%/−343 | **weak** | 07-16 | TT | **−4368** | = recorded | **yes** weak→weak — tilt said inv, ignored |
| 07-16 | 64%/+256 | **strong** | 07-17 | TT | **−7165** | = recorded | **no** strong→weak — tilt **TT** used |
| 07-17 | 45%/+145 | **mixed** | 07-20 | inv | **−6188** | **strong** (flip) | soft only — inv bled; TT-equiv would-win |
| 07-20 | 50%/+185 | **mixed** | 07-21 | inv | **−391** | **strong** (flip) | soft — mild inv bleed |
| 07-21 | 17%/−357 | **weak** | 07-22 | inv | **0** (0 nf) | null | atrLiq **0.7** — no wide enough bar filled |
| 07-22 | 23%/−432 | **weak** | 07-23 | inv | **0** (0 nf) | null | atrLiq 0.7 + minPL 1.8; 00992 wide then gross-PL skip |
| 07-23 | 33%/+782 | **mixed** | 07-24 | inv | **0** (0 nf) | null | atrLiq 0.7 + minPL 1.4; only 02318 cleared then commission-kill |
| 07-24 | 0%/0 (0 nf) | **null** | 07-27 | inv | **+5213** (1W) | null prior | first 0.7 fill (01810); no US lead to score |

**Verdict (n=7 scorable):** TT-space ~**5/7** if counting Jul20–21 flips; live TT↔TT still **2/3**. Jul22–24 null under **intentional** atrLiq 0.7 (+ gross P:L); Jul27 fill under null US prior. Soft tilt only — **do not promote to hard gate**. Sparse HK days are expected; don’t force lag reads on 0-fill sessions.

### Use (intended — soft tilt only)

**After US RTH close → next morning HK mode lean** (market-wide or as a tie-break when per-symbol is unclear):

| Prior US day (TT) | HK lean next session |
|-------------------|----------------------|
| **strong** / high nf WR | bias **TT** (Jul17 showed this can still fail hard) |
| **weak** / low nf WR | bias **inverse** (or skip aggressively if already on TT and book soft) |

- **Not a hard gate** — Jul17 strong→TT wipe proves it. Bar shape + per-symbol still primary (§Symbol strategy).
- **Jul16 lesson:** weak US → lean inv was correct; staying on TT for parity cost **−4368**.
- **Jul17 lesson:** strong US → lean TT still **−7165** on RED A cluster. Lag is a weak prior, not a shield.
- **Jul20 lesson:** mixed US → soft + **inverse** still **−6188** on GREEN open; A′ inv longs lost. Soft ≠ safe.
- **Jul21 lesson:** mixed→soft inv **−391** milder; G1 skipped 09988/09992 (fields active without explicit master flag).
- **Jul22 lesson:** atrLiq **0.7** is the live HK gate (commission / rare-wide). 0-fill days are valid outcomes; lag tilt still soft when no trades print.
- **Jul23–24 lesson:** back-to-back 0 nf under 0.7; each day **one** wide clear then **gross P:L skip** (commission math) — filter chain works; still **zero fill sample** going into Jul27.
- **Jul27 lesson:** first **0.7 fill** landed (**01810 +5213**); minPL still cuts mid/high-priced wide clears (01299/03690). One win ≠ promote — keep collecting under 0.7.
- Log whether the tilt was used in Validation log (`lag-tilt: TT|inv|ignored|soft`).

## Mode draft (UNVALIDATED — per bar, future per-symbol)

Pick mode at 09:45 from closed bar only. **Jul 6–8 + Jul 20 ran inverse**; Jul 13–17 ran TT. TT column on inv days is counterfactual target.

| Bar | Draft mode | Rationale |
|-----|------------|-----------|
| RED archetype **A** (capitulation) | **TT** long | Inverse short 1W/8L on cp≤.15; Jul14 TT 5W/0L — **Jul15 0W/2L −1315; Jul17 0W/4L −5455**. **Weakened — do not promote** |
| GREEN archetype **A′** (euphoria) | **TT** short | Fade blow-off — **Jul16 live TT 0W/3L −3318**; inverse long was 4W/0L then **Jul20 inv 0W/2L −1185**. **Weakened both modes** |
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
00148 11d 2/2/7 +1343 .41 31  | 07-06 R .12 76 inv +1809 | 07-07 R .31 27 inv -708 | 07-08 R .02 29 TT -1103 | 07-13 R .74 19 inv flat-liq | 07-14 G .69 20 inv flat-liq | 07-15 R .35 35 inv flat no-fill | 07-16 R .73 33 inv flat no-fill | 07-17 G .55 20 inv flat-liq | 07-20 R .19 20 inv flat-liq | 07-21 G .66 29 inv +1345 | 07-22 R .21 23 inv 0 ← no-liq
00388 11d 2/6/3 -1742 .43 59  | 07-06 R .11 46 inv -437 | 07-07 G .14 35 inv -239 | 07-08 G .76 54 inv -279 | 07-13 G .76 82 inv +257 | 07-14 R .06 81 TT +99 | 07-15 R .15 51 inv -222 | 07-16 R .71 54 inv -186 | 07-17 G .38 63 inv flat no-fill | 07-20 G .81 68 inv -735 | 07-21 G .63 77 inv 0 ← no-fill | 07-22 R .65 53 inv 0 ← no-liq
00700 11d 2/2/7 -1383 .59 66  | 07-06 G .62 95 inv +193 | 07-07 G .90 84 TT +366 | 07-08 G .62 80 inv 0 | 07-13 G .91 48 inv flat no-fill | 07-14 R .04 48 TT flat no-fill | 07-15 R .72 66 inv flat no-fill | 07-16 G .77 52 inv flat no-fill | 07-17 R .03 68 TT -1153  ← A TT long L; OPEN_DEADLINE | 07-20 G .71 55 inv -789 | 07-21 R .21 39 inv 0 ← no-fill | 07-22 R .14 75 TT 0 ← g1
00939 11d 3/5/3 -1625 .59 58  | 07-06 R .36 48 inv -582 | 07-07 R .10 46 inv -351 | 07-08 G 1.00 108 TT +360 | 07-13 G .75 53 inv -270 | 07-14 R .27 49 inv +92 | 07-15 R .43 31 inv -302 | 07-16 G .63 37 inv flat no-fill | 07-17 G .80 48 inv flat no-fill | 07-20 G 1.00 106 inv +349  ← only Jul20 W | 07-21 R .40 46 inv -921 | 07-22 G .83 110 inv 0 ← no-fill
00992 11d 0/3/8 -1655 .39 54  | 07-06 R .06 61 TT -601 | 07-07 R .19 57 inv 0 | 07-08 R .27 60 inv -489 | 07-13 G .72 79 inv flat-max$ | 07-14 R .15 60 inv -565 | 07-15 R .24 43 inv flat no-fill | 07-16 R .84 56 inv flat no-fill | 07-17 G .44 31 inv flat no-fill | 07-20 G .59 39 inv flat no-fill | 07-21 G .68 57 inv 0 ← no-fill | 07-22 R .16 39 inv 0 ← no-liq
01299 11d 0/4/7 -1894 .57 73  | 07-06 R .50 44 inv 0 | 07-07 R .06 81 TT -370 | 07-08 G .63 61 inv 0 | 07-13 G .87 109 inv flat no-fill | 07-14 G .48 71 inv flat no-fill | 07-15 R .08 76 inv -463 | 07-16 G .73 51 inv flat no-fill | 07-17 G .84 78 inv flat no-fill | 07-20 G .97 86 TT -581  ← A′ inv long L | 07-21 G .83 48 inv -480 | 07-22 R .60 56 inv 0 ← no-liq
01810 11d 1/7/3 -4990 .51 65  | 07-06 R .22 75 inv 0 | 07-07 G .70 92 inv -697 | 07-08 G .68 60 inv 0 | 07-13 G .59 61 inv -679 | 07-14 R .02 66 TT +952 | 07-15 R .36 51 inv -709 | 07-16 G .92 76 TT -959  ← A′ TT short L | 07-17 R .26 56 inv -818 | 07-20 G .86 52 inv -1159 | 07-21 G .36 51 inv -921 | 07-22 R .13 73 TT 0 ← g1
01888 11d 3/0/8 +4925 .38 43  | 07-06 R .19 122 inv +2421 | 07-07 R .67 30 inv +521 | 07-08 R .08 44 TT 0 | 07-13 G .49 27 inv flat no-fill | 07-14 G .65 25 inv flat-liq | 07-15 R .33 61 inv flat no-fill | 07-16 R .58 41 inv flat no-fill | 07-17 R .25 21 inv flat-liq | 07-20 R .19 19 inv flat-liq | 07-21 G .66 29 inv +1983 | 07-22 R .04 25 TT 0 ← no-liq
02318 11d 1/6/4 -877 .43 52  | 07-06 G .56 41 inv -46 | 07-07 R .10 50 inv -197 | 07-08 G .94 57 TT +77 | 07-13 G .48 57 inv flat no-fill | 07-14 R .20 64 inv flat no-fill | 07-15 R .00 39 TT -149  ← A TT long L | 07-16 R .23 34 inv -101 | 07-17 G .33 45 inv flat no-fill | 07-20 G 1.00 85 inv -404 | 07-21 G .65 29 inv -57 | 07-22 G .54 42 inv 0 ← no-liq
02628 11d 2/7/2 -2198 .33 54  | 07-06 G .48 39 inv -554 | 07-07 R .11 47 inv -345 | 07-08 G .67 33 inv -366 | 07-13 R .11 36 TT -402 | 07-14 R .08 91 TT +763 | 07-15 R .09 143 TT -1166  ← wide A L | 07-16 R .25 27 inv +57 | 07-17 R .39 32 inv -181 | 07-20 G .79 40 inv flat invert-stop | 07-21 G .63 27 inv -4 | 07-22 G .73 45 inv 0 ← no-liq
03033 11d 3/6/2 -2998 .52 58  | 07-06 R .16 53 inv -142 | 07-07 G .79 77 inv +291 | 07-08 G .64 66 inv -456 | 07-13 G .79 65 inv +554 | 07-14 R .03 51 TT +248 | 07-15 R .32 34 inv flat no-fill | 07-16 G .90 71 TT -2156  ← A′ worst L | 07-17 R .04 62 TT -294  ← A TT long L | 07-20 G .97 43 TT -605  ← A′ inv long L | 07-21 R .25 36 inv -438 | 07-22 R .23 45 inv 0 ← no-liq
03690 11d 1/5/5 -2741 .50 64  | 07-06 R .24 55 inv 0 | 07-07 G 1.00 127 TT +873 | 07-08 G .61 60 inv -776 | 07-13 G .26 49 inv flat no-fill | 07-14 R .36 38 inv flat no-fill | 07-15 R .48 65 inv flat no-fill | 07-16 G .70 64 inv -819 | 07-17 R .03 66 TT -431  ← A TT long L | 07-20 G .84 52 inv -690 | 07-21 G .49 47 inv -898 | 07-22 R .62 39 inv 0 ← no-liq
07747 11d 0/1/10 -3577 .31 22  | 07-06 R .08 17 inv 0 | 07-07 G .99 24 inv 0 | 07-08 R .25 24 inv 0 | 07-13 R .25 14 inv flat-liq | 07-14 R .46 19 inv flat-liq | 07-15 R .02 13 TT flat-liq | 07-16 R .30 14 inv flat-liq | 07-17 R .04 50 TT -3577  ← A TT long L; first fill (was swap) | 07-20 R .37 20 inv flat-liq | 07-21 G .77 18 inv 0 ← no-liq | 07-22 R .56 7 inv 0 ← no-liq
09618 11d 0/4/7 -2168 .48 65  | 07-06 R .50 56 inv 0 | 07-07 R .07 42 inv -443 | 07-08 G .84 85 inv -672 | 07-13 G .53 48 inv -444 | 07-14 R .00 64 TT flat no-fill | 07-15 R .44 41 inv flat no-fill | 07-16 G .73 126 inv flat no-fill | 07-17 G .25 60 inv flat no-fill | 07-20 G .96 60 inv -609 | 07-21 G .41 87 inv 0 ← no-fill | 07-22 R .35 42 inv 0 ← no-liq
09988 11d 4/1/6 +2496 .44 59  | 07-06 R .27 57 inv 0 | 07-07 G .51 49 inv +218 | 07-08 G .72 49 inv +193 | 07-13 G .57 83 inv +914 | 07-14 R .06 62 TT +1540 | 07-15 R .52 61 inv flat no-fill | 07-16 G .45 60 inv flat no-fill | 07-17 R .00 52 inv -369  ← 4W streak broken | 07-20 G .85 60 inv flat no-fill | 07-21 R .08 63 inv 0 ← g1 | 07-22 R .54 41 inv 0 ← no-liq
09992 11d 1/5/5 -2105 .38 70  | 07-06 R .06 65 TT -601 | 07-07 R .19 56 inv 0 | 07-08 R .27 60 inv -489 | 07-13 G .60 69 inv flat no-fill | 07-14 R .04 70 inv +496 | 07-15 R .41 34 inv flat no-fill | 07-16 G .85 123 TT -203  ← A′ TT short L | 07-17 R .08 74 inv -342 | 07-20 G .93 79 inv -966 | 07-21 R .07 61 inv 0 ← g1 | 07-22 R .03 45 TT 0 ← no-liq
01347  8d 0/0/8 0 .48 50  | DROPPED 07-20 | 07-06..17 never filled
02899  8d 0/0/8 0 .47 49  | DROPPED 07-20 | 07-06..17 never filled
03750  8d 0/0/8 0 .34 45  | DROPPED 07-20 | 07-06..17 never filled
06869  8d 0/0/8 0 .37 45  | DROPPED 07-20 | 07-06..17 never filled
07709  8d 0/0/8 0 .33 21  | DROPPED 07-20 | 07-06..17 never filled
```

**Symbol tags (revise as n grows):**

| Tag | Symbols | Note |
|-----|---------|------|
| **4W** | 09988 | Jul17 broke streak; Jul21 g1 skip |
| **3W** | 01888, 00939, 03033 | **01888 +4925** best inv; Jul21 +1983 |
| **2W** | 00700, 00148 | 00148 Jul21 +1345 |
| **3L+** | 01810, 01299, 03690, 09992, 02318, 02628, 00388, 00992, 09618 | 01810 **7L −4990** |
| **g1-skip** | 09988/09992 Jul21; 00700/01810 Jul22 | fields firing |
| **flat-heavy** | 00992, 07747, 01888 | 01888 still flat-heavy despite 3W |
| **dropped** | 01347, 02899, 03750, 06869, 07709 | roster-dropped 07-20 |
| **inv ok** | 01888 | narrow GREEN + wide RED history |
| **post-euphoria risk** | 03690 | Jul21 −898 continues |
| **Jul22 sparse** | all 16 | atrLiq 0.7 intentional — 0 fills |
| **tt-switch-RED-A** | … | **do not promote** after TT week |

## Guard rails (UNVALIDATED — n=32)

Do **not** submit brackets when ALL of a rule's conditions match at 09:45 (closed bar). Revisit after each doc update.

| ID | Skip when | Evidence | Cost (W skipped) |
|----|-----------|----------|------------------|
| **G1** | inverse ∧ RED ∧ **cp≤.15** | 1W/8L −2040 | 00148 +1809 |
| **G2** | inverse ∧ **atr% 40–60** | 3W/9L −2334 | 09988 +218/+193, 02318 +77 |
| **G3** | inverse ∧ RED ∧ **cp≤.15 ∧ b≥.70** (A) | 1W/3L −266 | 00148 +1809 |
| **G4** | inverse ∧ GREEN ∧ **cp<.20 ∧ atr%<40** | 0W/1L −239 | none (n=1, monitor) |

**Apply first:** G1. G3 = stricter G1 (fewer skips, same winner cost). G2 independent of colour. G4 monitor only.

**Do not add yet:** colour-only, day-of-week, grind(B). **Reconsider:** euphoria A′ skip under inv — Jul6–8 was 4W/0L; **Jul20 0W/2L −1185**. **G1 fields fired Jul21–22** (09988/09992; 00700/01810) even when `enableClosePositionGate` absent from JSON.

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

**Operator update (2026-07-15):** TT parity **day 3** — **all-RED open** (21R/0G), **0W/6L −3010**. A TT longs that crushed Tue **failed** (02628 atr%143 −1166; 02318 −149). No winners. US Jul14 weak→HK weak aligned.

**Operator update (2026-07-16):** TT parity **day 4** — mixed 10R/11G, **1W/6L −4368**. First live **A′ TT shorts 0W/3L −3318** (03033 −2156). Only winner 02628 +57. US Jul15 weak→HK weak aligned; **lag-tilt inv ignored** (parity). Week net then **−3826**.

**Operator update (2026-07-17):** TT parity **day 5 (Fri) — week complete**. RED-heavy 13R/8G, **0W/8L −7165** — worst day in log. All fills RED TT longs. Archetype **A 0W/4L −5455** (07747 −3577 first fill; 00700 −1153 deadline). US Jul16 strong→lean TT **failed** (`lag-tilt: TT`). Week net **−10991** (11W/25L). **End-of-week call:** global TT parity lost badly vs inverse baseline (−2531 / 3d); do **not** promote A/A′→TT; prioritize **roster swaps + per-symbol mode**; soft lag tilt stays soft (strong side miss).

**Operator update (2026-07-20):** **Post-parity Mon — Inverse live**; roster **16** (5 never-fills dropped). GREEN-heavy **13G/3R**, **1W/9L −6188**. Only W **00939 +349**. **A′ inv longs 0W/2L −1185**. US Jul17 mixed → `lag-tilt: soft`.

**Operator update (2026-07-21):** Inv day 2 — **2W/7L −391**. GREEN-heavy **11G/5R**. Winners **01888 +1983 / 00148 +1345** (narrow D-G). G1 **fired** (09988/09992 close-position skipped) despite `enableClosePositionGate` absent from JSON — red 0.15/SKIP fields active in practice. US Jul20 mixed → `lag-tilt: soft`. Post-parity inv 2d **−6579**.

**Operator update (2026-07-22):** Inv day 3 — **0W/0L / 0 fills**. **atrLiquidityRatio raised to 0.7** (intentional — HK commissions need rare/high-quality wide bars, not 0.25 frequency). 13/16 no-liq; G1 skipped 00700/01810 A; only 00939 cleared liq (atr%110) then no-fill. RED-heavy **3G/13R**. US Jul21 weak → `lag-tilt: inv`; no trades to score. **Keep atrLiq 0.7.**

**Operator update (2026-07-23):** Inv day 4 — **0W/0L / 0 fills**. 15 atrLiq-skip; only **00992** cleared (atr%92, D-G) then **gross P:L skip** (ratio 1.49 &lt; minPL **1.8**). GREEN-heavy **9G/4R/3 doji**. US Jul22 weak → `lag-tilt: inv` null. **minPL 1.8 / TP:SL 2.33** live.

**Operator update (2026-07-24):** Inv day 5 — hold week **complete**. **0W/0L / 0 fills** again. 15 atrLiq-skip (avg atr% **38**); only **02318** cleared (atr%72, D-G inv LONG) then **gross P:L skip** (ratio **0.49** &lt; minPL **1.4**) — SEHK RT fees ~187 HKD flip a correct 2.33:1 geometry. Near-miss **01810** atr%66. US Jul23 mixed → null. **Three straight 0-nf days under 0.7** (Wed–Fri).

**Operator update (2026-07-27):** Inv week-2 day 1 — **first atrLiq 0.7 fill**. **1W/0L +5213** (**01810** GREEN D-G atr%**107**, inv LONG breakout). Gate **bare pass** (net ratio **1.412** ≥ minPL **1.4**). Liq clears **3/16**; **01299** / **03690** gross-PL-skip (ratios **0.75** / **1.23**). 13 atrLiq-skip. Exit **open deadline** (BOT 28.36 → SLD 28.58), not full TP 28.89 — live maxP/maxL ~19k/~17k after fill slip. US Jul24 **0 nf** → `lag-tilt: soft/null`. **Keep atrLiq 0.7 + inv**; one wide win is the sample we needed, not a mode flip.

**Decision (2026-07-20):** Hold **HK inverse** through this week (Jul 20–24). **No mid-week global mode flip.** Revisit **Jul 27**. **atrLiq 0.7 is live policy** (not a bug).

**Decision (2026-07-27):** **Hold Inverse + atrLiq 0.7** (no global mode flip). First 0.7 fill is encouraging; continue collecting wide-only fills. Revisit minPL/fib only if mid-priced board lots keep dying at the gate while 01810-class names stay rare.

**Schedule:** Jul 6–8 inverse → Jul 9–12 partial → **Jul 13–17 TT parity complete** → **Jul 20–24 inverse hold complete** (16-sym; atrLiq **0.7 from Jul22**) → **Jul 27+ inv + 0.7** (first fill +5213). Next: more 0.7 fill sample; replacements TBD; keep lag soft.

**Live — Inverse (HK, from 2026-07-20; atrLiq 0.7 from Jul22):**

| Setting | Value |
|---------|-------|
| invertTradeSide | **ON** (inverse) |
| takeProfitToStopLossRatio | **2.33** (live Jul23–24) |
| minProfitToLossRatio | **1.4** (Jul24; was **1.8** Jul23) |
| liquidityRangeDailyAtr | **ON**, **0.7** | intentional — commission / rare-wide filter |
| closePositionGate / G1 | red 0.15/SKIP **fires** (Jul21–22); master flag often absent in JSON |
| adjustableTrailingStop | **OFF** |
| openDeadline | **ON**, 90 min |
| entryInwardOffsetRatioOfRange | **0.0** |

Log per sym-day: `recordedPnl`, colour, cp, atr%, `draft_mode`, **actual mode** (TT/inv from bracket side). Tag `wide-enough` vs `atrLiq-skip` vs `gross-PL-skip`.

**G1 status:** Fields active in practice (Jul21–22 skips). Treat as **effectively on** for RED cp≤.15; confirm master flag deliberately at Jul27 review.

**Why atrLiq 0.7:** HK round-trip commissions are large relative to typical opening-range moves at 0.25. Target **infrequent, high-conviction** fills (very wide atr%) even if many sessions print 0 nf. Jul6–8 / Jul20–21 totals at 0.25 are **not** the live filter going forward — keep those baselines separate when judging 0.7.

**Promotion gate (revisit when met):** Parity week **complete**; inv hold **complete** (**−6579** / 5d recorded; Wed–Fri 0 nf under 0.7). Jul27 **first 0.7 fill** (**01810 +5213**). Still open: does 0.7 produce enough **winning** wide fills over weeks?; G1 formalize?; **per-symbol mode**; replacements; A′ handling; minPL vs TP:SL on mid-priced lots (01299/03690 still skip). US separate (`us-opening-bar-analysis.md`).

### Live config (what is running now)

All **16** SEHK deployments — **Inverse** + **atrLiq 0.7**.

| Setting | Live value | Note |
|---------|------------|------|
| invertTradeSide | **ON** | inverse continuation |
| liquidityRangeDailyAtr | **ON** | |
| atrLiquidityRatio | **0.7** | commission gate — rare/wide only |
| minProfitToLossRatio | **1.4** | Jul24 (was 1.8 Jul23); commission-aware |
| closePositionGate | *(often unset)* | G1 fields still skip |
| redSkipClosePositionBelow | **0.15** SKIP | fired Jul21–22 |
| skipGreen/Red liquidity bar | **OFF** | |
| adjustableTrailingStop | **OFF** | |
| openDeadline | **ON**, 90 min | |
| entryInwardOffsetRatioOfRange | **0.0** | |
| takeProfitToStopLossRatio | **2.33** | live Jul23–24 (recommended table still 1.5) |

### Watch list while collecting (log in §Days notes)

| Signal | Action | Evidence so far |
|--------|--------|-----------------|
| RED cp≤.15 | G1 **firing** | Jul21 09988/09992 skip; Jul22 00700/01810 A skip |
| GREEN A′ (cp≥.85, b≥.70) | **Caution both modes** | Jul16 TT 0W/3L; Jul20 inv 0W/2L −1185 |
| Ordinary GREEN (not A′) | Monitor | Jul21 01888/00148 narrow W +3328; Jul27 **01810 wide W +5213** (atr%107) under 0.7 |
| atrLiq **0.7** | Keep — rare/wide | Jul22–24 **0 nf** (3d); need wide-fill sample |
| Symbol repeat losers | 01810, 09992, 03690, 01299 | Jul21 −921/−898/−480 |
| post-euphoria | 03690 | Jul21 −898 continues |
| Fill rate | Track | Jul21: 9 fills; Jul22–24: **0 fills** each |
| gross P:L gate | Track | Jul23 00992 ratio 1.49&lt;1.8; Jul24 02318 ratio 0.49&lt;1.4 (fees) |
| Roster | 5 dropped; need replacements | **16 active**; 01888 **3W** |
| US→HK lag | Soft tilt only | Jul22–24 null (atr / gross-PL) |

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

### Jul 15 synthesis (TT parity week — day 3)

| Metric | Jul 15 live (TT) | Jul 14 live (TT) | Jul 13 live (TT) |
|--------|-----------------|-----------------|-----------------|
| nf W/L | **0W/6L** | 7W/1L | 3W/4L |
| PnL | **−3010** | +3623 | −71 |
| Fill set | 6 sym | 8 sym | 7 sym |
| Fill rate | 6/21 (29%) | 8/21 (38%) | 7/21 (33%) |
| Bar mix | **21R / 0G** | 17R / 4G | 15G / 6R |

**Config:** All fills **Touch Turn** (RED→LONG). All-RED open.

**Losers:** 02628 −1166 (A, atr%143), 01810 −709 (C), 01299 −463 (D), 00939 −302 (C), 00388 −222 (D), 02318 −149 (A)

**Archetype A fills:** 0W/2L **−1315** — reverses Jul14’s A narrative on the next RED day.

**Conclusions:**

1. **Worst TT day so far** — 0 winners; gave back most of Tue’s +3623.
2. **A→TT long is not automatic** — same shape family that crushed Jul14 lost Jul15, including **wide** 02628 (atr%143). ATR size alone does not save A.
3. **US→HK:** Jul14 US weak → Jul15 HK −3010 — **aligned** (1st fully scored live TT↔TT).
4. **All-RED ≠ Jul14** — colour skew similar to Tue but outcomes opposite; bar quality / follow-through differed.
5. **Do not midweek-flip** — still collecting parity data; note the crack in A support.

**Operator read (day 3):** Capitulation fade failed on a pure-RED open. Hold TT for logging; roster swaps still the clean action item.

### Jul 16 synthesis (TT parity week — day 4)

| Metric | Jul 16 live (TT) | Jul 15 live (TT) | Jul 14 live (TT) |
|--------|-----------------|-----------------|-----------------|
| nf W/L | **1W/6L** | 0W/6L | 7W/1L |
| PnL | **−4368** | −3010 | +3623 |
| Fill set | 7 sym | 6 sym | 8 sym |
| Fill rate | 7/21 (33%) | 6/21 (29%) | 8/21 (38%) |
| Bar mix | **10R / 11G** | 21R / 0G | 17R / 4G |

**Config:** All 7 fills **Touch Turn** (RED→LONG / GREEN→SHORT). `lag-tilt: inv ignored — parity week`.

**Only winner:** 02628 +57 (R D, atr%27 — scrap)  
**Losers:** 03033 −2156 (A′), 01810 −959 (A′), 03690 −819 (D-G), 09992 −203 (A′), 00388 −186 (C), 02318 −101 (D)

**Archetype A′ fills (first under TT):** 0W/3L **−3318**. Mode draft A′→TT short **fails this day**. Inverse baseline A′ was 4W/0L long — opposite side, opposite result quality.

**Conclusions:**

1. **Worst day in the HK log** — −4368 tops Jul8 inverse (−3511).
2. **A′ TT short not validated** — all three euphoria fades lost; largest single L = 03033 −2156.
3. **US→HK:** Jul15 US weak (8%/−343) → Jul16 −4368 — **aligned** (2nd live TT↔TT). Soft inv tilt was the right lean; parity kept TT on.
4. **Two-day wipe** Wed–Thu **−7378** erased Tue and then some. Week-to-date **−3826** (11W/17L).
5. **Mode draft caution** — A (Jul15) and A′ (Jul16) both hurt under TT after looking strong on thinner evidence. Per-symbol + lag tilt > global archetype faith.
6. **Roster** — 6 never-fills now **7F/7d**; still execute week of Jul 20.
7. **End-of-week call** — TT parity no longer “winning”; decide stay / revert inv / per-symbol with eyes open on lag tilt.

**Operator read (day 4):** Weak US lead + ignored inv tilt + A′ TT shorts = expensive day. Continue logging Fri if parity holds; do not promote A′→TT short from this sample.

**Recommended preset:** documented in §Recommended config — **not live** until promotion gate met.

### Jul 17 synthesis (TT parity week — day 5 / week complete)

| Metric | Jul 17 live (TT) | Jul 16 live (TT) | Jul 15 live (TT) |
|--------|-----------------|-----------------|-----------------|
| nf W/L | **0W/8L** | 1W/6L | 0W/6L |
| PnL | **−7165** | −4368 | −3010 |
| Fill set | 8 sym | 7 sym | 6 sym |
| Fill rate | 8/21 (38%) | 7/21 (33%) | 6/21 (29%) |
| Bar mix | **13R / 8G** | 10R/11G | 21R/0G |

**Config:** All 8 fills **Touch Turn** (RED→LONG). `lag-tilt: TT` (US Jul16 strong). GREEN bars: 6 no-fill + 2 liq — **zero GREEN fills**.

**Losers (all RED TT long):** 07747 −3577 (A), 00700 −1153 (A, OPEN_DEADLINE), 01810 −818 (C), 03690 −431 (A), 09988 −369 (D), 09992 −342 (B), 03033 −294 (A), 02628 −181 (C)

**Archetype A fills:** 0W/4L **−5455** — third A test under TT this week (Jul14 +3601 → Jul15 −1315 → Jul17 −5455).

**Conclusions:**

1. **Worst day in the HK log** — −7165 tops Jul16 (−4368) and Jul8 inverse (−3511).
2. **A→TT long falsified for promotion** — Jul14 outlier; cumulative A under TT week is dominated by losses.
3. **US→HK strong→TT miss** — first live strong lead failed hard. Soft tilt only; weak→inv half still cleaner.
4. **Wed–Fri wipe −14543** erased Tue and more. Week **−10991** (11W/25L) vs inverse baseline −2531 / 3d — TT parity **lost** as a week-level bet.
5. **07747 first fill** — left never-fill list via −3577 A loss; swap list now **5** symbols.
6. **09988 4W streak broken** (−369).
7. **End-of-week call** — do not stay on blind global TT; prefer **roster + per-symbol mode**; revisit inv/G1 only with eyes on lag weak→inv.

**Operator read (day 5):** Parity week finished ugly. Strong US did not save a RED capitulation open under TT. Next actions: roster swaps (week of Jul 20), per-symbol mode draft, keep lag as soft prior only.

**Recommended preset:** documented in §Recommended config — **not fully live** (Jul20 inverse only; G1 master OFF); TP:SL confirm still open.

### Jul 20 synthesis (post-parity — Inverse, day 1)

| Metric | Jul 20 live (inv) | Jul 17 live (TT) | Jul 6 live (inv) |
|--------|-------------------|-----------------|-----------------|
| nf W/L | **1W/9L** | 0W/8L | 3W/6L |
| PnL | **−6188** | −7165 | +2061 |
| Fill set | 10 sym | 8 sym | ~9 sym |
| Fill rate | 10/16 (63%) | 8/21 (38%) | ~43% |
| Bar mix | **13G / 3R** | 13R/8G | mixed |
| Roster | **16** (5 dropped) | 21 | 21 |

**Config:** All fills **inverse** (GREEN→LONG). `lag-tilt: soft` (US Jul17 mixed). **G1 master OFF** (red 0.15/SKIP fields linger in JSON only). 3R bars all flat-liq.

**Only winner:** 00939 +349 (G D-G, cp=1.00, b=.67, atr%**106** — wide, not full A′)  
**Losers:** 01810 −1159, 09992 −966, 00700 −789, 00388 −735, 03690 −690, 09618 −609, 03033 −605 (A′), 01299 −581 (A′), 02318 −404

**Archetype A′ fills (inv long):** 0W/2L **−1185** — first post-baseline A′ inv test **fails**.

**Flats:** 00148/01888/07747 liq; 00992/09988 no-fill; 02628 `NO_TRADE_INVERT_STOP_WOULD_TRIGGER` (doji b=.00).

**Conclusions:**

1. **Ugly inv Monday** — −6188 vs Jul6 inv Mon +2061 on another GREEN-skew open. Monday ≠ free win.
2. **A′ inv long not safe** — 4W/0L baseline cracked (0W/2L −1185). Mode draft A′→TT short also failed Jul16. **Neither side validated** for euphoria bars.
3. **Ordinary GREEN inv longs bled** — D-G 1W/7L −5003; only wide 00939 saved a scrap.
4. **G1 still not enabled** — master cp gate OFF; do not claim G1 live from leftover JSON fields.
5. **US→HK soft** — mixed lead + soft tilt; inv still wiped. TT-equiv flip says fade would have won — supports per-bar mode over blind inv.
6. **Roster cut executed** — 5 never-fills gone; replacements still TBD.
7. **Do not over-read** — 1 inv day post-parity; keep collecting under inv; decide whether to turn G1 on; prioritize replacements + A′ skip/mode idea.

**Operator read (Jul 20):** Reverted to inverse (not full Inverse+G1 preset). First day reminds that **GREEN continuation is fragile**. Soft US lead did not help. Next: fill roster TBD slots; enable G1 deliberately if desired; reconsider A′ under inv (skip or TT?).


### Jul 21 synthesis (post-parity — Inverse, day 2)

| Metric | Jul 21 live (inv) | Jul 20 live (inv) | Jul 6 live (inv) |
|--------|------------------|------------------|-----------------|
| nf W/L | **2W/7L** | 1W/9L | 3W/6L |
| PnL | **−391** | −6188 | +2061 |
| Fill set | 9 sym | 10 sym | ~9 sym |
| Fill rate | 9/16 (56%) | 63% | ~43% |
| Bar mix | **11G / 5R** | 13G/3R | mixed |
| Roster | **16** | 16 | 21 |

**Config:** Inverse. G1 fields skipped 09988/09992. `lag-tilt: soft` (US Jul20 mixed). atrLiq default **0.25**.

**Winners:** 01888 +1983, 00148 +1345 (both G D-G, atr%29 narrow)  
**Losers:** 00939 −921, 01810 −921, 03690 −898, 01299 −480, 03033 −438, 02318 −57, 02628 −4

**Conclusions:**

1. **Milder inv day** — −391 vs Mon −6188; still red.
2. **Narrow GREEN rescued** — 01888/00148 +3328 on atr%29; wide losers elsewhere.
3. **G1 live in practice** — two RED soft-B skips; no RED cp≤.15 fills.
4. **01888 3W** — best inv name (+4925 lifetime in registry).
5. **US→HK soft** — mixed lead → mild inv bleed; TT-equiv flip soft-strong.
6. **Post-parity inv 2d −6579**.

### Jul 22 synthesis (post-parity — Inverse, day 3 — atrLiq 0.7)

| Metric | Jul 22 live (inv, 0.7) | Jul 21 (0.25) | Jul 20 (0.25) |
|--------|----------------------|---------------|---------------|
| nf W/L | **0W/0L** | 2W/7L | 1W/9L |
| PnL | **0** | −391 | −6188 |
| Fill set | **0** | 9 | 10 |
| Fill rate | **0/16** | 56% | 63% |
| Bar mix | **3G / 13R** | 11G/5R | 13G/3R |
| Cleared liq | **1/16** (00939 atr%110) | most | most |

**Config:** Inverse + **atrLiquidityRatio=0.7** (intentional commission gate) + G1 fields. `lag-tilt: inv` (US Jul21 weak) — no fills to score.

**Flats:** 13 no-liq (incl draft-TT 01888/09992 A at atr%25/45 — fail 0.7); G1 skip 00700/01810 A (would clear 0.7 on atr%75/73 but G1); 00939 bracket no-fill (only liq clear).

**Conclusions:**

1. **0.7 did its job** — blocked mid/narrow opens that 0.25 would have traded (and recently bled).
2. **Sparse ≠ broken** — under commission math, many 0-nf mornings are acceptable; judge by **PnL per fill** and win% on the rare clears, not daily activity.
3. **G1 still firing** on true A bars that cleared width (00700/01810).
4. **First 0.7 day = no sample** — need more sessions before claiming the filter improves expectancy.
5. **Post-parity inv 3d −6579** with Wed contributing 0 (filter change mid-hold).
6. **Baselines diverge** — Jul6–8 / Jul20–21 at 0.25 are not apples-to-apples with live 0.7.

**Operator read (Jul 22):** Keep **atrLiq 0.7**. Inv hold through Jul24. Jul27 review should ask: are wide clears winning often enough to cover HK commissions?

### Jul 23 synthesis (post-parity — Inverse, day 4 — atrLiq 0.7)

| Metric | Jul 23 live (inv, 0.7) | Jul 22 | Jul 21 (0.25) |
|--------|----------------------|--------|---------------|
| nf W/L | **0W/0L** | 0W/0L | 2W/7L |
| PnL | **0** | 0 | −391 |
| Fill set | **0** | 0 | 9 |
| Fill rate | **0/16** | 0/16 | 56% |
| Bar mix | **9G / 4R / 3 doji** | 3G/13R | 11G/5R |
| Cleared liq | **1/16** (00992 atr%92) | 1/16 | most |

**Config:** Inverse + atrLiq **0.7** + TP:SL **2.33** + minPL **1.8**. `lag-tilt: inv` (US Jul22 weak) — no fills to score.

**Flats:** 15 atrLiq-skip; **00992** gross-PL-skip (projected ratio **1.49** &lt; **1.8**; would clear today’s 1.4).

**Conclusions:**

1. **Second 0.7 zero-fill day** — still no winning-wide sample.
2. **Gross P:L gate now binding** — first live reject after liq clear (minPL 1.8 strict).
3. **Draft-TT 00700 A′** atr%40 — blocked by 0.7 (not a mode miss).

**Operator read (Jul 23):** Keep 0.7. Note minPL **1.8** may be rejecting marginal-but-positive net setups (00992).

### Jul 24 synthesis (post-parity — Inverse, day 5 — hold week done)

| Metric | Jul 24 live (inv, 0.7) | Jul 23 | Jul 22 |
|--------|----------------------|--------|--------|
| nf W/L | **0W/0L** | 0W/0L | 0W/0L |
| PnL | **0** | 0 | 0 |
| Fill set | **0** | 0 | 0 |
| Fill rate | **0/16** | 0/16 | 0/16 |
| Bar mix | **10G / 6R** | 9G/4R/3D | 3G/13R |
| Cleared liq | **1/16** (02318 atr%72) | 1/16 | 1/16 |
| avg atr% | **38** | 46 | 48 |

**Config:** Inverse + atrLiq **0.7** + TP:SL **2.33** + minPL **1.4**. `lag-tilt: soft/inv` (US Jul23 mixed +782) — null.

**Flats:** 15 atrLiq-skip (near-miss **01810** atr%66); **02318** gross-PL-skip.

**02318 geometry (correct inverse LONG):** GREEN bar → inv LONG breakout at high `56.96`; TP `57.31` (+0.351 = range×0.382); SL `56.81` (−0.151 = tp/2.33). Gross R:R **2.33**. After SEHK RT fees (~187 HKD on 1000 lot): net max profit **~165** / max loss **~336** → ratio **0.49** ≪ 1.4. **Commission kill, not bad invert math.**

**Conclusions:**

1. **Three straight 0-nf mornings** under atrLiq 0.7 (Wed–Fri) — filter doing its job; Jul27 still lacks fill sample.
2. **minPL lowered 1.8→1.4** overnight — still blocked 02318 (fees dominate small HKD fib move).
3. **Post-parity inv week −6579** with three zero-fill days contributing 0; all PnL from Mon–Tue at 0.25.
4. Jul27 should weigh: keep 0.7 vs ease; whether minPL + stamp makes mid-priced board lots structurally untradeable at fib 0.382.

**Operator read (Jul 24):** Inv hold week **done**. Keep atrLiq **0.7** into weekend. Jul27 call needs **fills**, not more zero days — if next week is also barren, revisit fib/TP:SL/minPL together, not mode flip alone.

### Jul 27 synthesis (inv week-2 day 1 — first atrLiq 0.7 fill)

| Metric | Jul 27 live (inv, 0.7) | Jul 24 | Jul 23 |
|--------|----------------------|--------|--------|
| nf W/L | **1W/0L** | 0W/0L | 0W/0L |
| PnL | **+5213** | 0 | 0 |
| Fill set | **1** (01810) | 0 | 0 |
| Fill rate | **1/16** | 0/16 | 0/16 |
| Bar mix | **8G / 8R** | 10G/6R | 9G/4R/3D |
| Cleared liq | **3/16** (01810/01299/03690) | 1/16 | 1/16 |
| avg atr% | **47** | 38 | 46 |

**Config:** Inverse + atrLiq **0.7** + TP:SL **2.33** + minPL **1.4**. `lag-tilt: soft/null` (US Jul24 **0 nf**).

**Fill — 01810 inv LONG:** GREEN D-G OHLC `26.88/28.32/26.84/27.96` range **1.48** atr%**107** (cp **.76** b **.73** — not A′). Entry breakout high `28.32`; TP `28.885` / SL `28.077`. Net gate **maxP ~21252 / maxL ~15050 → ratio 1.412** (bare ≥1.4). Filled BOT **28.36** @10:20; SLD **28.58** @11:00 **open deadline** (not TP) → **+5213** after ~4687 RT commission. Post-fill live bounds ~**+19.2k / −16.7k**.

**Flats:** 13 atrLiq-skip; **01299** gross-PL-skip (atr%86, qty 1000, ratio **0.75**); **03690** gross-PL-skip (atr%84, qty 800, ratio **1.23**).

**Conclusions:**

1. **0.7 can print** — first fill after three zero-nf days; wide GREEN continuation (inv LONG) worked.
2. **minPL still binds** on other wide clears — 2/3 liq clears rejected (fees on mid/high HKD names).
3. **Bare gate pass + slip** — planned 1.412; fill 28.36 would be ~1.15 if re-checked (guard does not re-run).
4. **Deadline clipped the win** — left ~half the planned TP distance on the table; still the day’s only trade and a clean W.

**Operator read (Jul 27):** Hold **inv + atrLiq 0.7**. Do not ease filters off one win. Watch whether 01810-class wide D-G longs repeat; mid-priced gross-PL skips remain the structural tax.

### Symbol roster (swap policy — memory)

**Intent (2026-07-08):** May **remove low-activity symbols and add new ones** instead of (or before) rule changes. Priority: names that **never get a fill** — they consume a deployment slot but contribute no shape/PnL data.

**Swap executed (2026-07-20):** Dropped `01347`, `02899`, `03750`, `06869`, `07709` (5× 8F/8d never-fill). **No replacements yet** — roster **16**. 07747 kept (has fill data, −3577).

| Sym | Final W/L/F | PnL | Status |
|-----|-------------|-----|--------|
| 01347 | 0/0/8 | 0 | **dropped 07-20** |
| 02899 | 0/0/8 | 0 | **dropped 07-20** |
| 03750 | 0/0/8 | 0 | **dropped 07-20** |
| 06869 | 0/0/8 | 0 | **dropped 07-20** |
| 07709 | 0/0/8 | 0 | **dropped 07-20** |

**Action (2026-07-20):** Drops done. **Choose 5 replacements** (liquid SEHK, opening-range ≥25% ATR more often). Reconsider 07747 on PnL grounds separately.

### Next-week roster plan (week of Jul 20 — **partially done**)

**Dropped 5** (gate met — executed 2026-07-20):

| Drop | Replace with | Status |
|------|--------------|--------|
| **01347** | TBD | **dropped** — need add |
| **02899** | TBD | **dropped** — need add |
| **03750** | TBD | **dropped** — need add |
| **06869** | TBD | **dropped** — need add |
| **07709** | TBD | **dropped** — need add |

**Hold (do not swap):** 00700, 01888, 09988, 03033, 00939 (winners/history); 00148, 01810, 02628 (shape-flip); 00388, 09992 (fill data); 00992, 01299, 09618, 02318, 03690; **07747** (review on PnL).

**Swap gate (suggested):** ≥**5 consecutive HK sessions** with `positionOpened=false` (or 3/3 flat if fewer days available) **and** bar often fails liquidity **or** brackets placed but never filled → drop; add replacement with similar sector/liquidity (log new sym in registry from day 1).

**On swap:** Remove deployment; add new symbol; note `roster: dropped XXXX → YYYY (date)` in Validation log. Recompute totals — dropped symbols don't affect traded PnL but reduce wasted sym-days.

**Replacements:** TBD — prefer liquid SEHK names with opening-range ≥25% ATR more often than swap list. Fill TBD cells above when chosen.

## Recommended HK config (all SEHK deployments)

**Preset name:** `HK Inverse + G1 + atrLiq 0.7` | **Status:** inverse live; G1 fields **firing**; **atrLiq 0.7** (commission / rare-wide) — TP:SL confirm vs 1.5  
**Maps research →** `TouchTurnRuleConfig` cp gate (branch with advanced cp options)

### Triggers

| Setting | Value | Note |
|---------|-------|------|
| Require minimum range (× daily ATR) | **ON** | |
| Liquidity range (× ATR) | **0.7** | intentional — HK commissions; rare/wide only (was 0.25 in Jul6–21 baselines) |
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
Triggers:    liq ON 0.7 | skipGreen OFF | skipRed OFF | cpGate ON
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
| `greenSkipClosePositionAbove = 0.85` | blocked A′ wins Jul6–8; **Jul20 A′ inv lost** — revisit skip vs mode |
| `skipGreenLiquidityBar` / `skipRedLiquidityBar` | colour-only; disproved |
| G2 atr 40–60% | Jul8 mid-atr won; weaker than G1 |
| `fiveMinuteConfirmation` | N/A when invert on |

### Caveats

- Inverse baseline n=32 at atrLiq **0.25**; live from Jul22 is **0.7** — do not merge expectancy.
- Post-parity inv (−6579 / 5d) mixes two 0.25 days + three 0.7 zero-fill days.
- G1 fields fire in practice (Jul21–22); formalize master flag at Jul27.
- Ordinary GREEN still mixed at 0.25 — Jul21 narrow W vs Jul20 wipe; 0.7 should skip most of those narrow greens.
- Mode draft TT on RED A is **not** this preset — we skip, not fade.
- A′ inv long failed Jul20 — baseline 4W/0L broken.
- Live TP:SL **2.33** + minPL **1.4** can still reject wide atr% clears when fib HKD reward ≪ SEHK stamp (02318 Jul24; 01299/03690 Jul27).
- Jul27 **01810** shows 0.7 *can* clear minPL on a very wide low/mid name (ratio 1.412) and still win after deadline clip.

## Days

| date | n | nf W/L | PnL | avg atr% | note |
|------|---|--------|-----|----------|------|
| 2026-07-06 | 21 | 3/6 | +2061 | 60 | 01888 +2421, 00148 +1809 |
| 2026-07-07 | 21 | 5/8 | −1081 | 52 | GREEN 4W/2L; 00700/03690 A′ win |
| 2026-07-08 | 21 | 3/7 | −3511 | 53 | mostly GREEN (14/21); 00148 −1103, 03690 −776, 09618 −672 |
| 2026-07-13 | 21 | 3/4 | −71 | 55 | **TT parity d1**; 15G/6R; 09988 +914, 01810 −679; 7 fills / 14 flat |
| 2026-07-14 | 21 | 7/1 | +3623 | 66 | **TT parity d2**; 17R/4G; A longs crush; 09988 +1540, 01810 +952; only L 00992 −565 |
| 2026-07-15 | 21 | 0/6 | −3010 | 55 | **TT parity d3**; **21R/0G**; A longs fail; 02628 −1166; US lag weak→weak |
| 2026-07-16 | 21 | 1/6 | −4368 | 54 | **TT parity d4**; 10R/11G; A′ TT shorts 0W/3L −3318; only W 02628 +57; lag-tilt inv ignored |
| 2026-07-17 | 21 | 0/8 | −7165 | 49 | **TT parity d5 / week done**; 13R/8G; A TT longs 0W/4L −5455; 07747 −3577; lag-tilt TT; strong→weak miss |
| 2026-07-20 | 16 | 1/9 | **−6188** | 55 | **inv d1**; 13G/3R; A′ inv 0W/2L −1185; only W 00939 +349; roster 16; lag-tilt soft |
| 2026-07-21 | 16 | 2/7 | **−391** | 47 | **inv d2**; 11G/5R; 01888 +1983 / 00148 +1345; G1 skip 09988/09992; lag-tilt soft |
| 2026-07-22 | 16 | 0/0 | **0** | 48 | **inv d3 atrLiq 0.7**; 0 fills (intentional sparse); G1 skip 00700/01810; lag-tilt inv null |
| 2026-07-23 | 16 | 0/0 | **0** | 46 | **inv d4**; 0 fills; 00992 atr%92 gross-PL-skip (1.49&lt;1.8); lag-tilt inv null |
| 2026-07-24 | 16 | 0/0 | **0** | 38 | **inv d5 / hold done**; 0 fills; 02318 atr%72 gross-PL-skip (0.49&lt;1.4 fees); lag null |
| 2026-07-27 | 16 | 1/0 | **+5213** | 47 | **inv w2 d1**; **first 0.7 fill** 01810 +5213 (atr%107); 01299/03690 gross-PL-skip; lag null |

## Trades (non-flat) — `date sym col cp b atr% arch pnl`

```
2026-07-27 01810 G .76 .73 107 D-G +5213
2026-07-21 01888 G .66 .13 29 D-G +1983
2026-07-21 00148 G .66 .08 29 D-G +1345
2026-07-21 02628 G .63 .26 27 D-G -4
2026-07-21 02318 G .65 .50 29 D-G -57
2026-07-21 03033 R .25 .11 36 D -438
2026-07-21 01299 G .83 .25 48 D-G -480
2026-07-21 03690 G .49 .34 47 D-G -898
2026-07-21 01810 G .36 .25 51 D-G -921
2026-07-21 00939 R .40 .60 46 C -921
2026-07-20 00939 G 1.00 .67 106 D-G +349
2026-07-20 02318 G 1.00 .62 85 D-G -404
2026-07-20 01299 G .97 .70 86 A' -581
2026-07-20 03033 G .97 .94 43 A' -605
2026-07-20 09618 G .96 .17 60 D-G -609
2026-07-20 03690 G .84 .26 52 D-G -690
2026-07-20 00388 G .81 .22 68 D-G -735
2026-07-20 00700 G .71 .71 55 D-G -789
2026-07-20 09992 G .93 .52 79 D-G -966
2026-07-20 01810 G .86 .24 52 D-G -1159
2026-07-17 02628 R .39 .57 32 C -181
2026-07-17 03033 R .04 .72 62 A -294
2026-07-17 09992 R .08 .58 74 B -342
2026-07-17 09988 R .00 .38 52 D -369
2026-07-17 03690 R .03 .77 66 A -431
2026-07-17 01810 R .26 .53 56 C -818
2026-07-17 00700 R .03 .97 68 A -1153
2026-07-17 07747 R .04 .94 50 A -3577
2026-07-16 02628 R .25 .25 27 D +57
2026-07-16 02318 R .23 .38 34 D -101
2026-07-16 00388 R .71 .00 54 C -186
2026-07-16 09992 G .85 .83 123 A' -203
2026-07-16 03690 G .70 .64 64 D-G -819
2026-07-16 01810 G .92 .90 76 A' -959
2026-07-16 03033 G .90 .85 71 A' -2156
2026-07-15 02318 R .00 .93 39 A -149
2026-07-15 00388 R .15 .15 51 D -222
2026-07-15 00939 R .43 .00 31 C -302
2026-07-15 01299 R .08 .35 76 D -463
2026-07-15 01810 R .36 .52 51 C -709
2026-07-15 02628 R .09 .74 143 A -1166
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

Jul21: 7 flat omitted (2 g1 + 1 liq + 4 no-fill). Jul22–24: **16/16 flat each** (atrLiq 0.7 ± gross-PL — omitted). Jul27: 15 flat omitted (13 atrLiq + 2 gross-PL). Prior flats omitted (rebuild from captures if needed).

## Validation log

| period | days | sym-days | nf W/L | PnL | notes |
|--------|------|----------|--------|-----|-------|
| 2026-07-06–08 | 3 | 63 | 11/21 | −2531 | live IB inverse; Jul8 worst day; G1 cf −492 |
| 2026-07-13 | 1 | 21 | 3/4 | −71 | **TT parity d1**; replay inv **−658** same fills; symbol-level mode divergence |
| 2026-07-14 | 1 | 21 | 7/1 | +3623 | **TT parity d2**; 17R/4G; A TT longs 5W/0L +3601; swap gate 5F/5d |
| 2026-07-15 | 1 | 21 | 0/6 | −3010 | **TT parity d3**; 21R/0G; A TT longs 0W/2L −1315; US Jul14 weak→align |
| 2026-07-16 | 1 | 21 | 1/6 | −4368 | **TT parity d4**; A′ TT shorts 0W/3L −3318; `lag-tilt: inv ignored`; US Jul15 weak→align |
| 2026-07-17 | 1 | 21 | 0/8 | −7165 | **TT parity d5 / week done**; A TT longs 0W/4L −5455; 07747 −3577; `lag-tilt: TT`; US Jul16 strong→**miss** |
| 2026-07-13–17 | 5 | 105 | 11/25 | −10991 | TT parity week complete; do not merge with inverse baseline |
| 2026-07-15 | — | — | — | — | **roster plan:** week of Jul 20 drop never-fills; replacements TBD |
| 2026-07-17 | — | — | — | — | **roster:** drop list → 5 (07747 filled −3577); **US→HK lag:** live TT↔TT 2/3; strong side miss |
| 2026-07-20 | 1 | 16 | 1/9 | **−6188** | **inverse**; 13G/3R; A′ inv 0W/2L −1185; only W 00939 +349; `lag-tilt: soft` |
| 2026-07-20 | — | — | — | — | **decision:** hold inv through Jul20–24; revisit Jul 27 |
| 2026-07-21 | 1 | 16 | 2/7 | **−391** | inv; 01888/00148 +3328; G1 skip 09988/09992; `lag-tilt: soft` |
| 2026-07-22 | 1 | 16 | 0/0 | **0** | inv + **atrLiq 0.7** intentional; 0 fills; G1 skip 00700/01810; `lag-tilt: inv` null |
| 2026-07-20–22 | 3 | 48 | 3/16 | **−6579** | post-parity inv hold; Wed 0.7 zero-fill |
| 2026-07-22 | — | — | — | — | **policy:** atrLiq **0.7** for HK commissions (rare/wide); keep separate from 0.25 baselines |
| 2026-07-23 | 1 | 16 | 0/0 | **0** | inv 0.7; 00992 gross-PL-skip (1.49&lt;minPL 1.8); TP:SL 2.33; `lag-tilt: inv` null |
| 2026-07-24 | 1 | 16 | 0/0 | **0** | inv 0.7; 02318 gross-PL-skip (0.49&lt;1.4 — SEHK fees); hold week **done**; lag null |
| 2026-07-20–24 | 5 | 80 | 3/16 | **−6579** | inv hold complete; Wed–Fri 0 nf under 0.7 |
| 2026-07-27 | 1 | 16 | 1/0 | **+5213** | **first 0.7 fill** 01810 +5213 (ratio 1.412); 01299/03690 gross-PL-skip; `lag-tilt: soft/null` |
| 2026-07-27 | — | — | — | — | **decision:** hold inv + atrLiq 0.7; no mode flip off one win |

---
*Agent: north star = §Symbol strategy. **Respect §Operator status** — **Inverse + atrLiq 0.7** held (Jul27 first fill **01810 +5213**). Ingest day → tag `g1-skip`, `atrLiq-skip`, `gross-PL-skip`, **`draft_mode` TT/inv**. After US/HK close → §US→HK lag (skip lag score on 0-nf). **Next:** more 0.7 fill sample; mid-priced minPL skips still structural; replacements TBD. Keep terse.*
