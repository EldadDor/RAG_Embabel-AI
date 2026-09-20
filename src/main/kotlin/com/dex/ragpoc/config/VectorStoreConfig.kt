package com.dex.ragpoc.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Reserved for a future explicit vector-store adapter.
 *
 * The default parity service uses JDBC against Python-managed PostgreSQL tables and does not
 * auto-configure a Spring AI vector-store implementation.
 */
@Configuration
@Profile("legacy-poc")
class VectorStoreConfig
