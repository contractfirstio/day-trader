@ib
Feature: Interactive Brokers mode end-to-end
  Full IB mode uses a single gateway for market data and order execution.
  The IB TWS API is mocked at the gateway boundary.

  Background:
    Given the broker mode is "ib"
    And a running Touch Turn deployment for "AAPL"

  @ib-shard-1
  Scenario: IB gateway connects on startup
    When the broker runtime starts
    Then the gateway should be connected
    And the gateway broker id should be "INTERACTIVE_BROKERS"

  @ib-shard-1
  Scenario: Session bootstrap fetches signal context through IB gateway
    Given the IB gateway returns a non-liquidity bootstrap context
    When the Touch Turn engine starts
    And the session loads the first fifteen minute candle
    Then the deployment candle status should be "READY"
    And the deployment should have ATR14 populated

  @ib-shard-1
  Scenario: Non-liquidity bar yields no-trade decision through IB path
    Given the IB gateway returns a non-liquidity bootstrap context
    And the deployment has a closed non-liquidity bar loaded
    When liquidity is evaluated for the session
    Then the session decision outcome should be "NO_TRADE_NOT_LIQUIDITY"

  @ib-shard-1
  Scenario: Liquidity bar submits bracket to IB gateway
    Given the IB gateway returns a liquidity bootstrap context
    And the deployment has a closed liquidity bar loaded
    When the broker runtime starts
    And liquidity is evaluated for the session
    Then the IB gateway should have placed a bracket for "AAPL"

  @ib-shard-1
  Scenario: Manual session stop flattens symbol on IB gateway
    When the Touch Turn engine starts
    And the session is stopped manually
    Then the IB gateway should have flattened "AAPL"

  @ib-shard-1
  Scenario: IB gateway exposes connection state lifecycle
    When the broker runtime starts
    Then the gateway connection state should be "Connected"
    When the broker runtime shuts down
    Then the gateway connection state should be "Disconnected"

  @ib-shard-1
  Scenario: IB gateway resolves instrument metadata
    When the IB gateway resolves instrument "AAPL"
    Then the instrument resolution should succeed

  @ib-shard-2
  Scenario: Canonical non-liquidity yields no-trade through IB
    Given the IB gateway returns canonical scenario "NON_LIQUIDITY"
    And the deployment has canonical scenario "NON_LIQUIDITY" loaded
    When liquidity is evaluated for the session
    Then the session decision outcome should be "NO_TRADE_NOT_LIQUIDITY"

  @ib-shard-2
  Scenario: Canonical red liquidity submits bracket with matching entry on IB
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the deployment has canonical scenario "RED_LIQUIDITY_LONG" loaded
    When the broker runtime starts
    And liquidity is evaluated for the session
    Then the IB gateway should have placed a bracket for "AAPL"
    And the IB bracket entry should match canonical scenario "RED_LIQUIDITY_LONG"

  @ib-shard-2
  Scenario: Engine PollLiquidity places bracket and marks ordersPlacedForSession
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the deployment has liquidity evaluation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session should have orders placed for the session
    And the IB gateway should have placed a bracket for "AAPL"

  @ib-shard-2
  Scenario: Engine PollLiquidity bracket rejection yields NO_TRADE_ORDER_REJECTED
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the IB gateway rejects the next bracket placement
    And the deployment has liquidity evaluation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session decision outcome should be "NO_TRADE_ORDER_REJECTED"

  @ib-shard-2
  Scenario: Session prepare fails when IB gateway is disconnected
    Given a stopped Touch Turn deployment for "AAPL"
    And the IB gateway is disconnected
    When session prepare runs on IB
    Then the prepare check "IB_CONNECTED" should fail

  @ib-shard-2
  Scenario: Session prepare fails when broker reports open position
    Given a stopped Touch Turn deployment for "AAPL"
    And the IB gateway reports an open position for "AAPL"
    When session prepare runs on IB
    Then the prepare check "FLAT_POSITION" should fail

  @ib-shard-2
  Scenario: IB session start registers market data capture
    Given a stopped Touch Turn deployment for "AAPL"
    When the Touch Turn IB session starts
    Then session market data capture should be active

  @ib-shard-2
  Scenario: Manual session stop releases market data capture
    Given session market data capture is active for the deployment
    And the Touch Turn engine starts
    When the session is stopped manually
    Then session market data capture should be inactive

  @ib-shard-3
  Scenario: Engine five minute hammer confirmation submits bracket on IB
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the IB gateway returns five minute hammer bars for canonical scenario "RED_LIQUIDITY_LONG"
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session five minute confirmation status should be "CONFIRMED"
    And the session should have orders placed for the session
    And the IB gateway should have placed a bracket for "AAPL"

  @ib-shard-3
  Scenario: Engine five minute confirmation invalidates on IB when bar closes outside sweep range
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the IB gateway returns five minute invalidating bars for canonical scenario "RED_LIQUIDITY_LONG"
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the session five minute confirmation status should be "INVALIDATED"
    And the session decision outcome should be "NO_TRADE_FIVE_MIN_CONFIRMATION_INVALIDATED"

  @ib-shard-3
  Scenario: Engine five minute confirmation expires on IB without a qualifying hammer
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the IB gateway returns five minute bars without a hammer for canonical scenario "RED_LIQUIDITY_LONG"
    And the deployment has liquidity evaluation enabled
    And the deployment has five minute confirmation enabled
    When the engine evaluates liquidity awaiting five minute confirmation expiry
    Then the session five minute confirmation status should be "EXPIRED"
    And the session decision outcome should be "NO_TRADE_FIVE_MIN_CONFIRMATION_EXPIRED"

  @ib-shard-3
  Scenario: Session prepare passes when IB gateway is healthy
    Given a stopped Touch Turn deployment for "AAPL"
    And the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the IB gateway has live bid ask for "AAPL"
    When session prepare runs on IB
    Then the prepare check "IB_CONNECTED" should pass
    And the prepare check "HISTORICAL_BOOTSTRAP" should pass
    And the prepare check "OPENING_BAR_TIME" should pass
    And the prepare overall status should be "PASS"

  @ib-shard-3
  Scenario: Session prepare fails when IB historical bootstrap is unavailable
    Given a stopped Touch Turn deployment for "AAPL"
    And the IB gateway historical bootstrap fails
    When session prepare runs on IB
    Then the prepare check "HISTORICAL_BOOTSTRAP" should fail
    And the prepare overall status should be "FAIL"

  @ib-shard-3
  Scenario: Orphan working orders block engine liquidity bracket submit on IB
    Given the IB gateway returns canonical scenario "RED_LIQUIDITY_LONG"
    And the IB gateway has orphan working orders for "AAPL"
    And the deployment has liquidity evaluation enabled
    When the Touch Turn engine starts
    And the engine evaluates liquidity for the session
    Then the IB gateway should not have placed a bracket for "AAPL"
