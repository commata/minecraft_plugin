package kr.commata.economyhub.guard;

import java.util.Arrays;

/**
 * 청크 안 블록 좌표 하나를 int 하나로 묶고, 그 목록(int[])을 다루는 순수 함수 모음.
 * Bukkit 에 의존하지 않아 단위 테스트로 검증합니다.
 */
public final class Marks {

    private Marks() {
    }

    /** x·z 는 청크 안 좌표(0~15)만 쓰고, y 는 -2048~2047 범위. */
    public static int pack(int x, int y, int z) {
        return (x & 15) | ((z & 15) << 4) | (((y + 2048) & 0xFFF) << 8);
    }

    public static boolean contains(int[] marks, int mark) {
        if (marks == null) {
            return false;
        }
        for (int m : marks) {
            if (m == mark) {
                return true;
            }
        }
        return false;
    }

    /** 이미 있으면 그대로, 없으면 뒤에 붙임. 상한을 넘으면 가장 오래된(앞쪽) 것부터 버림. */
    public static int[] add(int[] marks, int mark, int max) {
        if (contains(marks, mark)) {
            return marks;
        }
        int[] base = marks == null ? new int[0] : marks;
        int[] grown = Arrays.copyOf(base, base.length + 1);
        grown[base.length] = mark;
        int limit = Math.max(1, max);
        if (grown.length > limit) {
            return Arrays.copyOfRange(grown, grown.length - limit, grown.length);
        }
        return grown;
    }

    /** 없으면 같은 배열을 그대로 돌려줌(호출하는 쪽이 저장을 건너뛸 수 있게). */
    public static int[] remove(int[] marks, int mark) {
        if (!contains(marks, mark)) {
            return marks;
        }
        int[] out = new int[marks.length - 1];
        int i = 0;
        for (int m : marks) {
            if (m != mark) {
                out[i++] = m;
            }
        }
        return out;
    }
}
