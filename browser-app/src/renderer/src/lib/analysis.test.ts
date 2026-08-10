import { describe, it, expect } from 'vitest'
import {
  coverageRatio,
  foldTail,
  headShare,
  normalizedEntropy,
  ratio,
  shannonEntropy
} from './analysis'

describe('shannonEntropy', () => {
  it('bằng 0 khi tất cả dồn vào một mục', () => {
    expect(shannonEntropy([10])).toBe(0)
    expect(shannonEntropy([10, 0, 0])).toBe(0)
  })

  it('bằng log2(n) khi chia đều — trường hợp đa dạng tối đa', () => {
    expect(shannonEntropy([5, 5])).toBeCloseTo(1, 10)
    expect(shannonEntropy([1, 1, 1, 1])).toBeCloseTo(2, 10)
  })

  it('không phụ thuộc quy mô, chỉ phụ thuộc tỉ lệ', () => {
    expect(shannonEntropy([1, 3])).toBeCloseTo(shannonEntropy([100, 300]), 10)
  })

  it('trả về 0 cho danh sách rỗng thay vì NaN', () => {
    expect(shannonEntropy([])).toBe(0)
    expect(shannonEntropy([0, 0])).toBe(0)
  })
})

describe('normalizedEntropy', () => {
  it('đưa mọi bảng về cùng thang 0..1 bất kể số dòng', () => {
    // Hai bảng cùng "chia đều tuyệt đối" nhưng khác số dòng: entropy thô khác
    // nhau (1 bit vs 3 bit), entropy chuẩn hoá phải bằng nhau.
    expect(normalizedEntropy([5, 5])).toBeCloseTo(1, 10)
    expect(normalizedEntropy([1, 1, 1, 1, 1, 1, 1, 1])).toBeCloseTo(1, 10)
  })

  it('bằng 0 khi chỉ có một mục khác 0', () => {
    expect(normalizedEntropy([7, 0, 0])).toBe(0)
    expect(normalizedEntropy([])).toBe(0)
  })

  it('nằm giữa 0 và 1 với phân bố lệch', () => {
    const value = normalizedEntropy([100, 5, 3, 1])
    expect(value).toBeGreaterThan(0)
    expect(value).toBeLessThan(1)
  })
})

describe('headShare', () => {
  it('cộng đúng k mục lớn nhất, bất kể thứ tự đầu vào', () => {
    expect(headShare([1, 50, 2, 30, 17], 2)).toBeCloseTo(0.8, 10)
  })

  it('bằng 1 khi k lớn hơn số mục', () => {
    expect(headShare([2, 3], 10)).toBe(1)
  })

  it('trả về 0 cho tổng bằng 0', () => {
    expect(headShare([], 3)).toBe(0)
    expect(headShare([0, 0], 3)).toBe(0)
  })
})

describe('coverageRatio', () => {
  it('là tỉ lệ trang đã tải trên số đích đã nhìn thấy', () => {
    expect(coverageRatio(250, 1000)).toBe(0.25)
  })

  it('chặn trên ở 1 — corpus không thể phủ hơn 100%', () => {
    expect(coverageRatio(1200, 1000)).toBe(1)
  })

  it('trả về 0 khi chưa phát hiện đích nào', () => {
    expect(coverageRatio(10, 0)).toBe(0)
  })
})

describe('ratio', () => {
  it('không bao giờ trả về NaN hay Infinity', () => {
    expect(ratio(5, 0)).toBe(0)
    expect(ratio(Number.NaN, 10)).toBe(0)
    expect(ratio(3, 12)).toBe(0.25)
  })
})

describe('foldTail', () => {
  it('giữ k mục lớn nhất và gộp phần còn lại', () => {
    const folded = foldTail(
      [
        { label: 'vi', count: 80 },
        { label: 'en', count: 15 },
        { label: 'und', count: 3 },
        { label: 'fr', count: 1 },
        { label: 'de', count: 1 }
      ],
      3
    )

    expect(folded).toEqual([
      { label: 'vi', value: 80 },
      { label: 'en', value: 15 },
      { label: 'und', value: 3 },
      { label: 'khác', value: 2 }
    ])
  })

  it('không thêm dòng "khác" khi không có phần đuôi', () => {
    const folded = foldTail([{ label: 'vi', count: 5 }], 3)
    expect(folded).toEqual([{ label: 'vi', value: 5 }])
  })

  it('không sửa mảng gốc', () => {
    const input = [
      { label: 'a', count: 1 },
      { label: 'b', count: 9 }
    ]
    foldTail(input, 1)
    expect(input[0].label).toBe('a')
  })
})
