package com.dex.ragpoc.config

import com.dex.ragpoc.ingestion.ContentAddressedAssetStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AssetStorageConfiguration {
    @Bean
    fun contentAddressedAssetStore(properties: AppProperties): ContentAddressedAssetStore = ContentAddressedAssetStore(properties)
}
