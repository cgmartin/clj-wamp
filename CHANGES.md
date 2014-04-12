## 1.0.2 (2014/4/12)
 * Fix: Support catch-all ("*") in subscribe and publish maps

## 1.0.0 (2013/7/17)
 * Feature #7: Permissions can be allowed in bulk for all topics, or
   per topic category.
 * Feature: Allow authorization timeout to be disabled when set to `0`.

## 1.0.0-rc1 (2013/7/6)
 * Feature #3: WAMP-CRA (Challenge-Response Authentication) support.
   See API docs or `lein new clj-wamp wampproj` for example.
 * Fix: Perform origin validation when header is missing.
 * Fix: Removed origin validation skip feature from beta3. If your
   proxy cannot be configured to send back Protocol/Origin headers,
   please use a different `with-channel` handler.

## 1.0.0-beta3 (2013/6/27)
 * Fix: Allow skipping the origin validation for proxies that don't send
   the origin header.

## 1.0.0-beta2 (2013/6/27)
 * Fix #6: Exception thrown when client sends non-JSON to server.
 * Feature #5: Do subprotocol and origin validation during handshake.

## 1.0.0-beta1 (2013/6/17)
 * First release