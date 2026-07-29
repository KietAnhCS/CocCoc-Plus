package com.vnsearch.ranking;

/**
 * TODO (PHASE 5): PageRank tu cai dat bang power iteration tren SparseMatrix.
 *
 * PR = (1-d)/N + d * M^T * PR, d = 0.85.
 * Xu ly dangling node (trang khong co outlink): phan phoi deu diem cua no
 * cho tat ca cac trang.
 * Dieu kien dung: ||PR_new - PR_old||_1 < 1e-6 HOAC du 100 vong lap.
 * Log so vong lap thuc te da hoi tu.
 */
public class PageRankService {
    // TODO: implement in PHASE 5
}
