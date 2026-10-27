package com.xqdk.chess.openbook;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

abstract public class OpenBookBase implements OpenBook{

    abstract protected List<BookData> get(long vkey);

    @Override
    public List<BookData> query(long vkey, boolean redGo, SortRule rule) {
        List<BookData> list = get(vkey);
        sort(list, rule);
        return list;
    }

    void sort(List<BookData> list, SortRule rule) {
        // 随机规则改为"稳定排序后洗牌"：原随机比较器每次 compare 返回随机 ±1，
        // 违反 Comparator 总序契约，候选 ≥32 时 TimSort 可抛
        // "Comparison method violates its general contract"
        Collections.sort(list, new Comparator<BookData>() {
            @Override
            public int compare(BookData o1, BookData o2) {
                switch (rule) {
                    case BEST_WINRATE: {
                        return o1.getWinRate() > o2.getWinRate() ? -1 : (o1.getWinRate() < o2.getWinRate() ? 1 : 0);
                    }
                    case POSITIVE_RANDOM: {
                        // 正分着法排前（稳定），组内随机由下方 shuffle 实现
                        return o1.getScore() > 0 ? -1 : (o2.getScore() > 0 ? 1 : 0);
                    }
                    case BEST_SCORE:
                    case FULL_RANDOM:
                    default: {
                        return o1.getScore() > o2.getScore() ? -1 : (o1.getScore() < o2.getScore() ? 1 : 0);
                    }
                }
            }
        });
        if (rule == SortRule.FULL_RANDOM || rule == SortRule.POSITIVE_RANDOM) {
            Collections.shuffle(list);
        }
    }

    @Override
    public void close() {

    }
}
