package com.santimattius.resilient.bulkhead

/**
 * Registry of named [DefaultBulkhead] instances so multiple [com.santimattius.resilient.ResilientPolicy]
 * configurations can **share** the same bulkhead limits (e.g. one pool per downstream `"payments"`, `"db"`).
 *
 * **Thread-safety:** [getOrCreate] is **not** synchronized across platforms. Typical use is to build policies
 * on a single thread at startup; do not call [getOrCreate] concurrently for the same registry without external
 * synchronization.
 *
 * The first successful [getOrCreate] for a given [name] wins; subsequent calls return the same instance and
 * ignore configuration differences (document your convention: one name, one config).
 */
class BulkheadRegistry {

    private val instances = mutableMapOf<String, DefaultBulkhead>()

    /**
     * Returns an existing [DefaultBulkhead] for [name], or creates one with [configure].
     *
     * @param onRejected Optional callback forwarded to [DefaultBulkhead] **only** when the instance is created.
     */
    fun getOrCreate(
        name: String,
        configure: BulkheadConfig.() -> Unit,
        onRejected: ((reason: String) -> Unit)? = null
    ): DefaultBulkhead {
        instances[name]?.let { return it }
        val created = DefaultBulkhead(BulkheadConfig().apply(configure), onRejected)
        instances[name] = created
        return created
    }
}
