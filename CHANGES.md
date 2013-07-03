## 1.0.0-beta4 (2013/?/??)
 * Fix: Origin validation when header is missing, and removed skip
   feature from beta3. Use a different `with-channel` handler
   if proxy cannot be configured to send back Protocol/Origin headers.

## 1.0.0-beta3 (2013/6/27)
 * Fix: Allow skipping the origin validation for proxies that don't send
   the origin header.

## 1.0.0-beta2 (2013/6/27)
 * Fix #6: Exception thrown when client sends non-JSON to server.
 * Enhancement #5: Do subprotocol and origin validation during handshake.

## 1.0.0-beta1 (2013/6/17)
 * First release