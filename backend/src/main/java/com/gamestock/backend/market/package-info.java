/**
 * Order pipeline: HTTP / internal BotEngine -> OrderService -> bounded admission -> MatchingEngine
 * -> PersistenceWorker transaction -> committed user events / coalesced public state.
 *
 * <p>Human commands preserve FIFO, collect for 2 ms and contain at most 200 independent orders.
 * Bot matching runs passes of at most 200 orders; up to two passes share the default 100 ms DB transaction.
 * Matching threads only touch owned in-memory books. Database workers load and lock committed state,
 * invoke the pure matching stage, then aggregate account/position updates and persist multi-row inserts.
 * Different symbol groups progress independently. A group with an in-flight commit cannot expose its
 * speculative book to another batch. Existing DB locks continue to protect rewards, resets and legacy APIs.</p>
 *
 * <p>The admission queue is bounded (2048 human commands, 64 persistence jobs); one bot job per group may
 * be outstanding. No success response or confirmed fill is emitted before commit. Pending requests can
 * therefore fail on a process crash without losing an acknowledged trade. The caller must reconcile
 * order history after an uncertain network result and must not blindly replay mutations. Within a running
 * worker, a receipt in the same transaction makes retries idempotent, including a lost commit response.
 * Frozen bot intents retain their price, quantity and sequence on retry; expired intents are not revived.</p>
 *
 * <p>Bot groups reuse one receipt row per group. A batch token in that row distinguishes a retry
 * from the next admitted batch; OrderService never admits the latter while the former is unfinished.
 * Human receipts retain their one-hour deduplication window. Expired receipts are removed by an
 * independent bounded cleanup task, including while writes fail. Cleanup sleeps after an idle
 * retention window. MySQL table/disk-full errors pause new writes for 30 seconds and skip bot ticks
 * during that interval instead of producing a continuous failed-write/log storm. This does not
 * free an already exhausted database volume; storage must still be inspected and recovered.</p>
 *
 * <p>Retries use bounded backoff (default four attempts). Graceful shutdown closes admission and drains
 * pending work before closing matching/connection infrastructure. Permanent failure rejects pending
 * requests; the system never continues speculative spending indefinitely during a DB outage.</p>
 *
 * <p>Metrics are cumulative counters, gauges and latency totals, logged each active minute and exposed
 * to administrators at /api/market-metrics. Rates are counter differences divided by elapsed seconds.
 * No new broker, runtime, database engine or monitoring dependency is required.</p>
 */
package com.gamestock.backend.market;
