/**
 * Subscription protocol at {@code /ws/stream} (the legacy {@code /ws/market} remains compatible).
 * <ul>
 * <li>{@code {"type":"AUTH","token":"Firebase ID token"}} binds a verified account for five minutes.
 *     Renew on the same account every four minutes; reconnect when changing accounts.</li>
 * <li>{@code {"type":"SUBSCRIBE","symbols":["UMA"],"detailSymbols":["UMA"]}} replaces subscriptions.
 *     Symbols outside detailSymbols receive only quotes; detail viewers also receive depth and recent trades.</li>
 * <li>{@code MARKET_SYMBOL} is a replaceable public snapshot, coalesced every 100 ms by default.</li>
 * <li>{@code AUTHENTICATED}, {@code ACCOUNT_UPDATED}, {@code PORTFOLIO_UPDATED}, {@code ORDER_CANCELLED}
 *     and {@code ORDER_REJECTED} are private. Financial changes are sent only after commit.</li>
 * </ul>
 * <p>Single replica uses LocalEventFanout without a broker. A distributed adapter implements EventFanout,
 * returns distributed=true, preserves event IDs, and delivers envelopes locally and remotely exactly once
 * per adapter invocation. It must never re-publish received envelopes. Channels are market:SYMBOL,
 * user:ID and presence:market. A bounded per-connection cache handles duplicate broker deliveries.</p>
 * <p>Only one replica owns matching, guarded by a MySQL connection-scoped advisory lock. Additional socket
 * replicas set GAMESTOCK_MATCHING_ENABLED=false. Route order HTTP requests to the matching owner;
 * broker fan-out does not distribute matching authority. Presence pulses let remote viewers wake the owner.</p>
 */
package com.gamestock.backend.realtime;
