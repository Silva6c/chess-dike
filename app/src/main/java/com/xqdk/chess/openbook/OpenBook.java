package com.xqdk.chess.openbook;

import java.util.List;

public interface OpenBook {

    enum SortRule {
        BEST_SCORE,
        BEST_WINRATE,
        POSITIVE_RANDOM,
        FULL_RANDOM
    }

    // for local openbook
    List<BookData> query(long vkey, boolean redGo, SortRule rule);

    // close database
    void close();
}
