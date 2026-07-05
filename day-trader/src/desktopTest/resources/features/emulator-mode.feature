@emulator
Feature: Broker Emulator mode end-to-end
  The broker emulator provides synthetic market data and paper execution without Interactive Brokers.

  Background:
    Given the broker mode is "emulator"
    And a running Touch Turn deployment for "AAPL"

  @emulator-shard-1
  Scenario: Emulator gateway connects on startup
    When the broker runtime starts
    Then the execution gateway should be connected
    And the execution gateway broker id should be "EMULATOR"

  @emulator-shard-1
  Scenario: Session bootstrap loads opening bar through emulator
    When the Touch Turn engine starts
    And the session loads the first fifteen minute candle
    Then the deployment candle status should be "READY"
    And the deployment should have ATR14 populated

  @emulator-shard-1
  Scenario: Immediate entry fill opens paper position on emulator
    Given the emulator entry scenario is immediate fill
    When the Touch Turn engine starts
    And a liquidity bracket is placed on the emulator for "AAPL"
    Then the emulator should report an open position for "AAPL"

  @emulator-shard-1
  Scenario: Never-fill entry scenario keeps position flat
    Given the emulator entry scenario is never fill
    When the Touch Turn engine starts
    And a liquidity bracket is placed on the emulator for "AAPL"
    Then the emulator should have no open position for "AAPL"
    And the emulator should have a working entry order for "AAPL"

  @emulator-shard-1
  Scenario: Manual session stop flattens emulator symbol
    When the Touch Turn engine starts
    And the session is stopped manually
    Then the emulator should have flattened "AAPL"

  @emulator-shard-1
  Scenario: Manual session stop releases synthetic quote streaming
    When the Touch Turn engine starts
    And synthetic quote streaming is ensured for "AAPL"
    Then the emulator should be streaming quotes for "AAPL"
    When the session is stopped manually
    Then the emulator should have released quotes for "AAPL"

  @emulator-shard-1
  Scenario: Synthetic pricing ignores external IB-style quotes
    Given the emulator uses synthetic pricing
    When an external quote is published for "AAPL"
    And a liquidity bracket is placed on the emulator for "AAPL"
    Then the emulator should have no open position for "AAPL"

  @emulator-shard-2
  Scenario: Engine five minute hammer confirmation submits bracket on emulator
    Given the emulator is configured for red liquidity with five minute hammer confirmation
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session five minute confirmation status should be "CONFIRMED"
    And the session should have orders placed for the session
    And the emulator should have received a bracket for "AAPL"

  @emulator-shard-2
  Scenario: Engine five minute confirmation expires without a qualifying hammer
    Given the emulator is configured for five minute confirmation expiry without a hammer
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity awaiting five minute confirmation expiry
    Then the session five minute confirmation status should be "EXPIRED"
    And the session decision outcome should be "NO_TRADE_FIVE_MIN_CONFIRMATION_EXPIRED"

  @emulator-shard-2
  Scenario: Engine five minute confirmation invalidates when bar closes outside sweep range
    Given the emulator is configured for five minute confirmation invalidation
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session five minute confirmation status should be "INVALIDATED"
    And the session decision outcome should be "NO_TRADE_FIVE_MIN_CONFIRMATION_INVALIDATED"

  @emulator-shard-2
  Scenario: Engine five minute hammer rejects bracket below minimum gross profit
    Given the emulator is configured for red liquidity with five minute hammer confirmation
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    And the deployment minimum gross profit is 100000.0
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session five minute confirmation status should be "REJECTED_INSUFFICIENT_GROSS_PROFIT"
    And the session decision outcome should be "NO_TRADE_INSUFFICIENT_GROSS_PROFIT"

  @emulator-shard-2
  Scenario: Session prepare completes on emulator with expected checks
    Given a stopped Touch Turn deployment for "AAPL"
    And the emulator uses canonical scenario "RED_LIQUIDITY_LONG"
    When session prepare runs on emulator
    Then the prepare check "FLAT_POSITION" should pass
    And the prepare check "HISTORICAL_BOOTSTRAP" should pass
    And the prepare overall status should be "WARN"

  @emulator-shard-2
  Scenario: Emulator gateway reconnects after brief disconnect with bracket session intact
    Given the emulator entry scenario is immediate fill
    When the Touch Turn engine starts
    And a liquidity bracket is placed on the emulator for "AAPL"
    And orders placed for the session is recorded on the deployment
    And the emulator gateway disconnects and reconnects
    Then the deployment status should be "RUNNING"
    And the session should have orders placed for the session
