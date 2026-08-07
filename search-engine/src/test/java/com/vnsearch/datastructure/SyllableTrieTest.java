package com.vnsearch.datastructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiem thu trie am tiet luu bang mang phang. */
class SyllableTrieTest {

    private static String[] syllables(String spaceSeparated) {
        return spaceSeparated.split(" ");
    }

    @Test
    void storesAndRetrievesWordWeight() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(syllables("máy tính"), 9.5);

        int node = walk(trie, "máy tính");
        assertTrue(trie.isWord(node));
        assertEquals(9.5, trie.weightAt(node));
    }

    @Test
    void prefixOfAWordIsNotItselfAWord() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(syllables("cơ sở dữ liệu"), 12.0);

        int prefix = walk(trie, "cơ sở");
        assertNotEquals(SyllableTrie.NONE, prefix, "Tien to phai ton tai nhu mot nut");
        assertFalse(trie.isWord(prefix), "Nhung tien to khong phai la mot tu");
    }

    @Test
    void childReturnsNoneForUnknownSyllable() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(syllables("máy tính"), 1.0);

        assertEquals(SyllableTrie.NONE, trie.idOf("khủnglong"),
                "Am tiet chua tung nap phai cho id NONE");
        assertEquals(SyllableTrie.NONE, trie.child(trie.root(), trie.idOf("khủnglong")));
    }

    /**
     * Day la tinh chat khien trie hon {@code HashSet}: khi da biet khong con tu nao
     * co tien to nay, ben goi cat nhanh duoc ngay thay vi thu tiep cac do dai con lai.
     */
    @Test
    void deadEndIsDetectableAfterOneStep() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(syllables("hàng xóm"), 9.4);

        int afterHang = trie.child(trie.root(), trie.idOf("hàng"));
        assertNotEquals(SyllableTrie.NONE, afterHang);
        // "hàng khong" khong ton tai — phat hien ngay o buoc thu hai.
        assertEquals(SyllableTrie.NONE, trie.child(afterHang, trie.idOf("không")));
    }

    @Test
    void sameWordFromTwoSourcesKeepsLargerWeight() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(syllables("công nghệ"), 3.0);
        trie.insert(syllables("công nghệ"), 8.0);
        assertEquals(8.0, trie.weightAt(walk(trie, "công nghệ")));

        trie.insert(syllables("công nghệ"), 5.0);
        assertEquals(8.0, trie.weightAt(walk(trie, "công nghệ")), "Khong duoc ha xuong");
    }

    @Test
    void wordsSharingPrefixShareNodes() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(syllables("máy tính"), 1.0);
        trie.insert(syllables("máy bay"), 1.0);

        int afterMay = trie.child(trie.root(), trie.idOf("máy"));
        assertEquals(afterMay, trie.child(trie.root(), trie.idOf("máy")),
                "Hai tu cung tien to phai di qua CUNG mot nut");
        // goc + máy + tính + bay = 4 nut
        assertEquals(4, trie.nodeCount());
        assertEquals(3, trie.edgeCount());
    }

    /**
     * Bang canh phai bam lai khi vuot nguong tai. Neu ham bam hoac vong lap tham do
     * sai thi loi chi hien ra SAU khi bam lai — nen phai nhet du du lieu de kich hoat.
     */
    @Test
    void survivesRehashingWithManyWords() {
        SyllableTrie trie = new SyllableTrie(16);
        int words = 5_000;
        for (int i = 0; i < words; i++) {
            trie.insert(new String[]{"tien" + i, "to" + (i % 7)}, i + 1.0);
        }
        for (int i = 0; i < words; i++) {
            int node = trie.child(trie.child(trie.root(), trie.idOf("tien" + i)),
                    trie.idOf("to" + (i % 7)));
            assertNotEquals(SyllableTrie.NONE, node, "Mat tu thu " + i + " sau khi bam lai");
            assertEquals(i + 1.0, trie.weightAt(node));
        }
        assertEquals(words * 2, trie.edgeCount());
    }

    /**
     * Khoa canh la {@code (nutCha << 32) | idAmTiet}. Neu ham bam khong tron bit thi
     * 32 bit cao — chinh la nut cha — bi vut di, va MOI canh mang cung mot am tiet se
     * do vao cung mot o. Test nay dung mot am tiet duoi 2.000 nut cha khac nhau.
     */
    @Test
    void sameSyllableUnderManyParentsStaysCorrect() {
        SyllableTrie trie = new SyllableTrie(16);
        int parents = 2_000;
        for (int i = 0; i < parents; i++) {
            trie.insert(new String[]{"cha" + i, "của"}, i + 1.0);
        }
        for (int i = 0; i < parents; i++) {
            int node = trie.child(trie.child(trie.root(), trie.idOf("cha" + i)), trie.idOf("của"));
            assertEquals(i + 1.0, trie.weightAt(node),
                    "Canh 'của' duoi nut cha thu " + i + " bi lan sang nut cha khac");
        }
    }

    @Test
    void emptyInsertIsIgnored() {
        SyllableTrie trie = new SyllableTrie();
        trie.insert(new String[0], 5.0);
        assertEquals(1, trie.nodeCount(), "Chi con nut goc");
        assertEquals(0, trie.edgeCount());
    }

    /** Di tu goc theo cac am tiet; tra ve {@link SyllableTrie#NONE} neu duong dut. */
    private static int walk(SyllableTrie trie, String spaceSeparated) {
        int node = trie.root();
        for (String syllable : syllables(spaceSeparated)) {
            node = trie.child(node, trie.idOf(syllable));
            if (node == SyllableTrie.NONE) {
                return SyllableTrie.NONE;
            }
        }
        return node;
    }
}
