Feature: Broker Emulator mode end-to-end
  The broker emulator provides synthetic market data and paper execution without Interactive Brokers.

  Background:
    Given the broker mode is "emulator"
    And a running Touch Turn deployment for "AAPL"

  Scenario: Emulator gateway connects on startup
    When the broker runtime starts
    Then the execution gateway should be connected
    And the execution gateway broker id should be "EMULATOR"

  Scenario: Session bootstrap loads opening bar through emulator
    When the Touch Turn engine starts
    And the session loads the first fifteen minute candle
    Then the deployment candle status should be "READY"
    And the deployment should have ATR14 populated

  Scenario: Immediate entry fill opens paper position on emulator
    Given the emulator entry scenario is immediate fill
    When the Touch Turn engine starts
    And a liquidity bracket is placed on the emulator for "AAPL"
    Then the emulator should report an open position for "AAPL"

  Scenario: Never-fill entry scenario keeps position flat
    Given the emulator entry scenario is never fill
    When the Touch Turn engine starts
    And a liquidity bracket is placed on the emulator for "AAPL"
    Then the emulator should have no open position for "AAPL"
    And the emulator should have a working entry order for "AAPL"

  Scenario: Manual session stop flattens emulator symbol
    When the Touch Turn engine starts
    And the session is stopped manually
    Then the emulator should have flattened "AAPL"

  Scenario: Synthetic pricing ignores external IB-style quotes
    Given the emulator uses synthetic pricing
    When an external quote is published for "AAPL"
    And a liquidity bracket is placed on the emulator for "AAPL"
    Then the emulator should have no open position for "AAPL"
