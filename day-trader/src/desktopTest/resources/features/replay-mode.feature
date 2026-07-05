@replay
Feature: Session replay mode end-to-end
  Replay discovers hybrid and IB session captures on disk and plays them back through the production wiring path.

  Scenario: Replay catalog discovers hybrid session capture on disk
    Given a hybrid session capture on disk for "AAPL"
    When the replay catalog is discovered under paper-live-ib scope
    Then the replay catalog should list "AAPL" for session date "2026-06-04"

  Scenario: Minimal hybrid capture replays with matching ground truth outcome
    Given a minimal hybrid session capture on disk
    When the capture replays through the session runner
    Then the replay comparison outcome should match ground truth
