package com.dex.ragpoc.api

import com.dex.ragpoc.asset.AssetReadService
import com.dex.ragpoc.identity.PrincipalResolver
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class AssetController(
    private val principals: PrincipalResolver,
    private val assets: AssetReadService,
) {
    @GetMapping("/workspaces/{workspaceId}/assets/{assetId}")
    fun getAsset(
        @PathVariable workspaceId: String,
        @PathVariable assetId: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
        request: HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        val asset = assets.read(principals.resolve(request), workspaceId, assetId)
        val etag = "\"${asset.metadata.contentHash}\""
        if (ifNoneMatch == etag) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(asset.metadata.contentHash).build()
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(asset.metadata.mediaType))
            .contentLength(asset.bytes.size.toLong())
            .eTag(asset.metadata.contentHash)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .header("X-Content-Type-Options", "nosniff")
            .body(asset.bytes)
    }
}
