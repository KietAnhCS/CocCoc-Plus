package com.vnsearch.index;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VByteCodecTest {

    @Test
    void emptyListEncodesToEmptyArray() {
        assertEquals(0, VByteCodec.encodeSorted(new int[0]).length);
        assertEquals(0, VByteCodec.encodeSorted(null).length);
        assertArrayEquals(new int[0], VByteCodec.decodeSorted(new byte[0], 0));
    }

    @Test
    void roundTripPreservesValues() {
        int[] original = {0, 3, 17, 19, 40, 1041, 5010};
        byte[] encoded = VByteCodec.encodeSorted(original);
        assertArrayEquals(original, VByteCodec.decodeSorted(encoded, original.length));
    }

    @Test
    void smallDeltasUseOneByteEach() {
        // Hieu <= 127 thi moi so chi ton 1 byte — day la nguon goc cua ty le nen.
        int[] values = {0, 1, 2, 3, 4, 5};
        assertEquals(values.length, VByteCodec.encodeSorted(values).length);
    }

    @Test
    void compressionBeatsRawIntOnRealisticPostingList() {
        // Mo phong posting list that: 1.639 docId trai deu tren 5.011 tai lieu,
        // tuc hieu trung binh ~3 -> moi docId chi can 1 byte thay vi 4.
        int[] docIds = new int[1639];
        for (int i = 0; i < docIds.length; i++) {
            docIds[i] = i * 3 + (i % 2);
        }
        byte[] compressed = VByteCodec.encodeSorted(docIds);
        int rawBytes = docIds.length * Integer.BYTES;

        assertTrue(compressed.length < rawBytes / 3,
                "Nen phai tiet kiem tren 66%; thuc te " + compressed.length + " / " + rawBytes);
        assertArrayEquals(docIds, VByteCodec.decodeSorted(compressed, docIds.length));
    }

    @Test
    void handlesLargeDeltasSpanningMultipleBytes() {
        int[] values = {0, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152, Integer.MAX_VALUE};
        byte[] encoded = VByteCodec.encodeSorted(values);
        assertArrayEquals(values, VByteCodec.decodeSorted(encoded, values.length));
    }

    @Test
    void randomSortedListsRoundTrip() {
        Random random = new Random(42); // seed co dinh -> tai lap duoc
        for (int trial = 0; trial < 50; trial++) {
            int[] values = new int[200];
            int current = 0;
            for (int i = 0; i < values.length; i++) {
                current += random.nextInt(1000);
                values[i] = current;
            }
            byte[] encoded = VByteCodec.encodeSorted(values);
            assertArrayEquals(values, VByteCodec.decodeSorted(encoded, values.length));
        }
    }

    @Test
    void rejectsUnsortedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> VByteCodec.encodeSorted(new int[]{5, 3}));
    }

    @Test
    void rejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> VByteCodec.encodeSorted(new int[]{-1}));
    }

    @Test
    void encodedSizeMatchesActualEncoding() {
        assertEquals(1, VByteCodec.encodedSize(0));
        assertEquals(1, VByteCodec.encodedSize(127));
        assertEquals(2, VByteCodec.encodedSize(128));
        assertEquals(2, VByteCodec.encodedSize(16_383));
        assertEquals(3, VByteCodec.encodedSize(16_384));
    }

    // --- encodeSegments / decodeSegments ---

    @Test
    void segmentsRoundTripPreservesEachSegment() {
        List<int[]> segments = List.of(
                new int[]{0, 5, 9},
                new int[]{3},
                new int[]{},
                new int[]{1, 2, 3, 400});
        byte[] encoded = VByteCodec.encodeSegments(segments);
        int[][] decoded = VByteCodec.decodeSegments(encoded, new int[]{3, 1, 0, 4});

        assertEquals(4, decoded.length);
        for (int i = 0; i < segments.size(); i++) {
            assertArrayEquals(segments.get(i), decoded[i], "doan " + i);
        }
    }

    @Test
    void segmentBoundaryResetsDeltaBase() {
        // Day chinh la ly do encodeSegments phai ton tai: noi hai doan lai roi
        // delta hoa MOT lan se cho delta AM tai ranh gioi (100 -> 1).
        List<int[]> segments = List.of(new int[]{100}, new int[]{1});
        byte[] encoded = VByteCodec.encodeSegments(segments);
        int[][] decoded = VByteCodec.decodeSegments(encoded, new int[]{1, 1});

        assertArrayEquals(new int[]{100}, decoded[0]);
        assertArrayEquals(new int[]{1}, decoded[1], "doan sau phai bat dau lai tu 0");
    }

    @Test
    void segmentsRejectUnsortedWithinSegment() {
        assertThrows(IllegalArgumentException.class,
                () -> VByteCodec.encodeSegments(List.of(new int[]{5, 3})));
    }

    @Test
    void manySegmentsRoundTripOnRandomData() {
        Random random = new Random(42);
        List<int[]> segments = new ArrayList<>();
        int[] counts = new int[200];
        for (int s = 0; s < counts.length; s++) {
            int length = random.nextInt(6);
            int[] segment = new int[length];
            int value = random.nextInt(10);
            for (int i = 0; i < length; i++) {
                value += random.nextInt(50);
                segment[i] = value;
            }
            segments.add(segment);
            counts[s] = length;
        }
        int[][] decoded = VByteCodec.decodeSegments(VByteCodec.encodeSegments(segments), counts);
        for (int s = 0; s < counts.length; s++) {
            assertArrayEquals(segments.get(s), decoded[s], "doan " + s);
        }
    }
}
