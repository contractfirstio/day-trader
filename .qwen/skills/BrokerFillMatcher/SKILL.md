---
name: BrokerFillMatcher
description: Utility for filtering broker fills by order or bracket ID
source: auto-skill
extracted_at: '2026-06-09T00:00:00.000Z'
---

# BrokerFillMatcher Utility

This file provides functions to filter broker fills from `BrokerGateway.fills`.

## Functions

### `fillsForOrder(orderId: Int, fills: List<BrokerFill>)`
Returns all fills matching a single order ID (for entry, take-profit, or stop legs).

### `fillsForBracket(entryOrderId: Int, fills: List<BrokerFill>)`
Returns all fills for a Touch Turn bracket rooted at the entry order. Includes:
- The entry fill (`parentOrderId == 0` and `BrokerFill.orderId == entryOrderId`)
- Child leg fills (`parentOrderId == entryOrderId`)

### Convenience overloads
Both functions have overloads that accept `BrokerGateway` directly, reading from `gateway.fills.value`.
