@paper
Feature: Hybrid mode end-to-end
  Hybrid mode uses live IB market data with paper execution on the emulator.
  IB is mocked — no TWS connection is required.

  Background:
    Given the broker mode is "hybrid"
    And a running Touch Turn deployment for "AAPL"

  Scenario: Hybrid runtime splits market data and execution gateways
    When the broker runtime starts
    Then the market data gateway broker id should be "INTERACTIVE_BROKERS"
    And the execution gateway broker id should be "EMULATOR"

  Scenario: Session bootstrap reads signal context from mocked IB
    Given the mocked IB bootstrap context is non-liquidity
    When the Touch Turn engine starts
    And the session loads the first fifteen minute candle
    Then the deployment candle status should be "READY"
    And the deployment should have ATR14 populated

  Scenario: Non-liquidity opening bar yields no-trade decision
    Given the mocked IB bootstrap context is non-liquidity
    And the deployment has a closed non-liquidity bar loaded
    When liquidity is evaluated for the session
    Then the session decision outcome should be "NO_TRADE_NOT_LIQUIDITY"

  Scenario: Bracket orders route to emulator not mocked IB
    Given the mocked IB bootstrap context is liquidity
    And the deployment has a closed liquidity bar loaded
    When the broker runtime starts
    And liquidity is evaluated for the session
    Then the mocked IB gateway should reject order placement
    And the emulator should have received a bracket for "AAPL"

  Scenario: Live IB quotes drive emulator fill pricing
    Given the mocked IB bootstrap context is liquidity
    And the deployment has a closed liquidity bar loaded
    When the broker runtime starts
    And liquidity is evaluated for the session
    And a live IB quote crosses the entry price for "AAPL"
    Then the emulator should report an open position for "AAPL"

  Scenario: IB quote subscription is requested when session needs live marks
    Given the mocked IB bootstrap context is liquidity
    And the deployment has a closed liquidity bar loaded
    When the broker runtime starts
    And liquidity is evaluated for the session
    Then the mocked IB should have subscribed to live quotes for "AAPL"

  Scenario: Synthetic emulator ticks do not fill in hybrid mode
    Given the mocked IB bootstrap context is liquidity
    When the Touch Turn engine starts
    And a liquidity bracket is placed on the emulator for "AAPL"
    And the emulator advances synthetic market ticks
    Then the emulator should have no open position for "AAPL"

  Scenario: Manual session stop flattens emulator only
    When the Touch Turn engine starts
    And the session is stopped manually
    Then the emulator should have flattened "AAPL"
    And the mocked IB gateway should remain market-data-only
