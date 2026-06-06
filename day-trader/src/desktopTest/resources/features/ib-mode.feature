Feature: Interactive Brokers mode end-to-end
  Full IB mode uses a single gateway for market data and order execution.
  The IB TWS API is mocked at the gateway boundary.

  Background:
    Given the broker mode is "ib"
    And a running Touch Turn deployment for "AAPL"

  Scenario: IB gateway connects on startup
    When the broker runtime starts
    Then the gateway should be connected
    And the gateway broker id should be "INTERACTIVE_BROKERS"

  Scenario: Session bootstrap fetches signal context through IB gateway
    Given the IB gateway returns a non-liquidity bootstrap context
    When the Touch Turn engine starts
    And the session loads the first fifteen minute candle
    Then the deployment candle status should be "READY"
    And the deployment should have ATR14 populated

  Scenario: Non-liquidity bar yields no-trade decision through IB path
    Given the IB gateway returns a non-liquidity bootstrap context
    And the deployment has a closed non-liquidity bar loaded
    When liquidity is evaluated for the session
    Then the session decision outcome should be "NO_TRADE_NOT_LIQUIDITY"

  Scenario: Liquidity bar submits bracket to IB gateway
    Given the IB gateway returns a liquidity bootstrap context
    And the deployment has a closed liquidity bar loaded
    When the broker runtime starts
    And liquidity is evaluated for the session
    Then the IB gateway should have placed a bracket for "AAPL"

  Scenario: Manual session stop flattens symbol on IB gateway
    When the Touch Turn engine starts
    And the session is stopped manually
    Then the IB gateway should have flattened "AAPL"

  Scenario: IB gateway exposes connection state lifecycle
    When the broker runtime starts
    Then the gateway connection state should be "Connected"
    When the broker runtime shuts down
    Then the gateway connection state should be "Disconnected"

  Scenario: IB gateway resolves instrument metadata
    When the IB gateway resolves instrument "AAPL"
    Then the instrument resolution should succeed
