package com.xqdk.chess.openbook;


import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.xqdk.chess.gamelogic.Zobrist;

import java.util.ArrayList;
import java.util.List;

public class BHOpenBook extends OpenBookBase {

    private BHDatabase bhDB = null;
    SQLiteDatabase db = null;

    /**
     * 【雷区·性能】构造即触发 assets 大库拷贝（桔库 .obk.zip 约 71MB，sqliteassethelper
     * 首次 getReadableDatabase 解包到 filesDir）：在 worker 线程同步调用会卡识别管线
     * 数秒（仅首次或版本升级后）。构造方（ScreenAssistService 惰性初始化）已把首查
     * 放在 worker——不得挪到主线程；失败会抛异常走 openBookFailed 降级为纯引擎模式。
     */
    public BHOpenBook(Context context){
        // database asset file is managed by BHDatabase class
        bhDB = new BHDatabase(context);
        db = bhDB.getReadableDatabase();
    }


    @Override
    protected List<BookData> get(long vkey) {
        List<BookData> list = new ArrayList<>();
        Cursor cursor = null;
        try {
            String sql = String.format("select * from bhobk where vvalid = 1 and vkey = %d order by vscore desc, vwin desc limit 5", vkey);
            Log.d("BHOpenBook", "get: " + sql);
            cursor = db.rawQuery(sql, null);
            // 列索引先取一次并判 -1（Lint Range：getColumnIndex 可能返回 -1）
            int colMove = cursor.getColumnIndex("vmove");
            int colScore = cursor.getColumnIndex("vscore");
            int colWin = cursor.getColumnIndex("vwin");
            int colDraw = cursor.getColumnIndex("vdraw");
            int colLose = cursor.getColumnIndex("vlost");
            if (colMove < 0 || colScore < 0 || colWin < 0 || colDraw < 0 || colLose < 0) {
                Log.e("BHOpenBook", "get: unexpected book schema");
                return list;
            }
            // iterate result

            while (cursor.moveToNext()) {
                BookData bd = new BookData();
                int vmove = cursor.getInt(colMove);
                String move = Zobrist.getMoveFromVmove(vmove);
                bd.setMove(move);
                bd.setScore(cursor.getInt(colScore));
                bd.setWinRate(cursor.getDouble(colWin));
                bd.setDrawNum(cursor.getInt(colDraw));
                bd.setLoseNum(cursor.getInt(colLose));
                list.add(bd);
            }
        } catch (Exception e) {
            Log.d("BHOpenBook", "get: " + e.getMessage());
            Log.d("BHOpenBook", "get: " + e.getStackTrace());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }


    @Override
    public void close() {
        bhDB.close();
    }
}
