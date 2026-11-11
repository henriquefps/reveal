package dev.hfps.reveal.pipeline

/**
 * Version of the develop pipeline's maths, stored with every render cache. Any change to a formula or to the order
 * of operations bumps it, so stale caches are never reused (docs/plan/architecture.md, "Determinism").
 */
const val PIPELINE_VERSION: Int = 5
