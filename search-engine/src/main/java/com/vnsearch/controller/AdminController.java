package com.vnsearch.controller;

/**
 * TODO (PHASE 6): REST endpoint quan tri: kich hoat crawl, xem trang thai
 * job, reindex, xem thong ke he thong.
 *
 * POST /api/admin/crawl                        -&gt; { jobId, status }
 * GET  /api/admin/crawl/{jobId}/status          -&gt; { status, pagesCrawled, queueSize }
 * POST /api/admin/reindex                       -&gt; rebuild index + chay lai PageRank
 * GET  /api/admin/stats                         -&gt; { totalDocuments, totalTerms,
 *                                                       indexSizeBytes, cacheHitRate,
 *                                                       bloomFilterBits }
 */
public class AdminController {
    // TODO: implement in PHASE 6
}
