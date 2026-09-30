# RDP-16 design — PPTX parsing and ingestion

Last reviewed: 2026-09-30. G1 and G2 approved; implementation undergoing offline verification.

## Parser and dependency

Add `org.apache.poi:poi-ooxml:5.5.1` explicitly to the default Maven dependencies and register a `PowerPointDocumentLoader` for `.pptx`. Apache lists 5.5.1 as its stable release: https://poi.apache.org/download.cgi. Use XSLF `XMLSlideShow` and the existing `DocumentLoader` interface; close all streams/packages. No rendering, OCR, chart interpretation, binary `.ppt`, macro-enabled presentation support, or new HTTP endpoint.

The cached POI POM declares POI/OOXML-lite, XMLBeans 5.3.0, Commons Compress 1.28.0, Commons IO 2.21.0, curvesapi, collections4, and Log4j API. Existing default resolution already includes Commons Compress 1.28.0 and Commons IO 2.22.0. Verify the actual resolved graph after adding POI, then compile/test under JDK 25. Keep Boot's dependency management; do not add optional rendering/crypto dependencies or change logging backends without evidence. Cached POI descriptors came from optional Embabel resolution; POI is not currently available to default source code.

## Document representation and extraction

Reuse the existing `SourceType.UNKNOWN` persistence value with `document_format=pptx` metadata. This avoids introducing an unverified source-type value into Python's shared schema/codec. Presentation format, slide count, slide titles, blocks, image anchors, and offsets are explicit metadata. If a dedicated source-type value becomes necessary, treat it as a separately reviewed interoperability change.

Slides follow presentation order, including hidden slides (flagged in metadata). Traverse shapes in stored shape order, recursively expanding groups at their original position. This is deterministic authoring order, not inferred visual reading order; record this limitation. Extract text paragraphs and bullet/list content, row-major table cells, and each slide's existing notes body after its main shapes. Exclude notes slide-number/header/footer placeholders and master/layout boilerplate. Use the core-properties title or the filename fallback, and slide title text where present.

Emit a nonempty `[SLIDE n]` header for every slide, including blank/image-only slides, so assets always have a text anchor. Preserve stable slide/block IDs, block kinds, original slide number, section/title, and absolute content start/end offsets. Track group/shape IDs for provenance. Delimit table cells safely; preserve Hebrew and other Unicode text.

## Package safety

Read one immutable input snapshot within the registry's existing 50 MiB source limit, then validate and parse that same snapshot. Before constructing POI objects, stream through ZIP entries with bounds: 10,000 entries, 50 MiB per expanded entry, and 250 MiB total expansion. Reject duplicate or unsafe/absolute/traversal entry names, corrupt CRC/size, invalid or missing OOXML content types/relationships/presentation parts, non-PPTX/macro-enabled content types, encrypted/non-ZIP input, and malformed XML. Secure XML validation rejects DTD/external entities and never resolves network resources. Internal relationship targets may use `..` only when normalized within the package; reject escaping targets.

Bound extraction to 1,000 slides, group depth 64, 100,000 shapes/blocks, and 2,000,000 extracted characters. Enforce bounds while traversing/reading, not after allocating unbounded collections. Keep POI's ZIP-bomb protections enabled; its setters are global, so avoid per-request changes to them. Return a dedicated `IllegalArgumentException` subtype so existing safe API advice handles failures. No source paths or content in new lifecycle logs.

## Images, chunks, and persistence

Extract only embedded picture shapes; never fetch linked pictures or hyperlinks. Record unavailable/external anchors without creating assets. Preserve original embedded image bytes, MIME type/name, hash, relationship ID scoped by slide, shape alt text, and dimensions where available. Repeated uses of one image get distinct stable occurrence anchors; content-addressed storage still deduplicates bytes. Existing asset count/byte limits apply; unsupported raster/vector image formats must produce explicit unavailable anchors rather than break otherwise usable slides. Support embedded PNG/JPEG/GIF images admitted by the existing asset-read path. BMP and vector formats retain unavailable anchors. Loader caps are 100 images, 15 MiB each, and 100 MiB total; configured asset-store limits also apply.

Use existing `DocumentAsset` fields for stable block IDs, sourceIndex, section, caption, and alt text. Package-byte content hashes detect image-only and notes-only changes. Existing document/asset identity and transactions are retained.

Add a PPTX-specific branch in `DocumentChunker`, selected by `document_format=pptx`. Split each slide independently with the selected recursive profile. Set `Chunk.page` to its 1-based slide number and metadata `slide_number`, section, and absolute offsets. Never merge or overlap text across slides; preserve the existing chunk-ID scheme. Existing behavior for other formats stays covered by regression tests.

For PPTX only, restrict nearest-chunk asset association to the same slide/section before offset selection; ensure markers provide a chunk for image-only slides. Existing asset association semantics remain unchanged for other formats. The JDBC page_number column can carry slide numbers without a migration. Existing `/ingest` scanning, dry run, model selection, unchanged checks, replacements, transaction rollback, cleanup, metrics, and asset APIs are reused.

## Validation

Generate deterministic presentations in test code using POI: reordered slides, text/bullets, Hebrew, tables, nested groups, notes, hidden/blank/image-only slides, and repeated pictures. Verify content/block order, exact offsets, slide-local chunks/citations, image bytes/hashes/anchors, image-only change detection, and same-slide links. Mutate package fixtures to test corruption, missing parts, unsafe names/relationships, duplicate names, expansion/entry/extraction limits, DTD/entities, encryption/non-ZIP rejection, and external-image no-fetch behavior.

Extend offline registry/directory and ingestion/API coverage for PPTX dry-run isolation, selected-model fake embeddings, persisted slide provenance and asset links, unchanged skip, replacement, failure/transaction boundaries, recursion, and stale cleanup. Add a separately environment-gated dedicated-workspace integration test with deterministic vectors for real persistence/asset-link checks; do not run it without live-database approval. Run focused parser/chunker/ingestion/API tests, Spotless, and the full offline suite. No application startup/provider/database/Docker operations during implementation verification.

## Alternative and rollback

Rejected: a text-only PowerPoint extractor or raw XML-only implementation. It loses structured slide/table/image provenance or duplicates the required POI object model. Shared OOXML-validator refactoring is deferred to avoid changing accepted DOCX behavior within this extension.

Rollback removes the loader registration, PPTX-specific chunk/asset handling, POI dependency, fixtures/tests, and docs. No schema migration or data deletion. Previously indexed presentation rows remain readable by existing JDBC records using UNKNOWN plus format metadata; users decide any re-ingestion or data cleanup separately.

G2 approved the dependency/structural edits. G3 before push; G4 before phase closure.

Resolved dependencies: POI/OOXML-lite 5.5.1, XMLBeans 5.3.0, Commons Compress 1.28.0, Commons IO 2.22.0, and Boot-managed Log4j API 2.25.5; no logging backend added. XML depth is additionally limited to 256 before POI parsing.

The opt-in Rdp16LiveIngestionIntegrationTest uses deterministic embeddings and dedicated workspace kotlin-rdp16-pptx-v1 to verify persisted same-slide image links and transaction rollback after forced asset failure. It requires RDP16_LIVE_INTEGRATION=true and separate permission for live database writes; it never calls an embedding provider.

## Runtime evidence (2026-09-30)

User ingestion persisted 37 chunks and 18 assets. Read-only SQL confirms 37 distinct slide numbers, nonempty 39–630-character chunks, all embeddings present, and 18 nonempty assets linked to existing chunks on their originating slides. A stored-vector probe ranks its own slide first; this confirms the vector query path but does not measure question relevance. The approved database-only fixture test passes lexical retrieval with slide provenance, image-to-slide links, and real transaction rollback after a forced asset failure. It leaves dedicated fixture data in kotlin-rdp16-pptx-v1 and local target assets. User chat answer/citation acceptance remains pending.
