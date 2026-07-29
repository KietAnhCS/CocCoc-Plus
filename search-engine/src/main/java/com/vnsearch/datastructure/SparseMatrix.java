package com.vnsearch.datastructure;

/**
 * TODO (PHASE 5): Ma tran thua (sparse matrix) tu cai dat, dung de luu ma
 * tran lien ket giua cac trang web cho PageRank.
 *
 * Vi sao dung sparse thay vi double[n][n]: voi n = 10.000 trang, ma tran
 * dac se can 10000 * 10000 * 8 byte = 800MB, trong khi thuc te moi trang
 * chi lien ket toi vai chuc trang khac -> bieu dien thua chi ton vai MB.
 *
 * Dinh dang du kien: CSR (Compressed Sparse Row) hoac adjacency list.
 *
 * Method can cai dat:
 *   - void set(int row, int col, double value)
 *   - double[] multiply(double[] vector)   O(nnz) voi nnz = so phan tu khac 0
 *     (dung cho vong lap power iteration cua PageRank).
 */
public class SparseMatrix {
    // TODO: implement in PHASE 5
}
