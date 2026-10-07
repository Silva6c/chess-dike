package com.xqdk.chess.views;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.xqdk.chess.ChessApp;
import com.xqdk.chess.R;
import com.xqdk.chess.controllers.GameController;
import com.xqdk.chess.gamelogic.Board;
import com.xqdk.chess.gamelogic.Game;
import com.xqdk.chess.gamelogic.Move;
import com.xqdk.chess.gamelogic.Piece;
import com.xqdk.chess.gamelogic.Position;
import com.xqdk.chess.utils.ArrowShape;
import com.xqdk.chess.utils.DrawableUtil;

import android.graphics.Path;


public class ChessView extends SurfaceView implements SurfaceHolder.Callback {
    public ChessViewThread thread;

    protected static class XYCoord {
        public int x;
        public int y;
        public XYCoord(int x, int y) { this.x = x; this.y = y; }
    }
    public Paint paint;

    public Bitmap ChessBoardBitmap;
    public Bitmap B_box, R_box, R_pot, B_pot;
    public Bitmap[] PieceBitmaps = new Bitmap[14];
    public Bitmap[] ChoiceBitmaps = new Bitmap[5];
    public Bitmap ThinkBitmap;
    final int MAX_SUGGESTED_MOVES = 5;

    // 如何设置下面的几个参数：
    // 有2个假设：棋盘的每个格子是正方形的, 棋子也是正方形的
    // 要计算下面的几个参数，需要找到棋盘上的几个点：格子左上角的坐标(x1, y1)，格子右上角的坐标(x2, y2)
    // 本次使用的棋盘x1=77, y1=60, x2=1165, y2=60
    final int BOARD_WIDTH = 1240;  // 根据棋盘的实际宽度来设置
    final int BOARD_HEIGHT = 1340; // 根据棋盘的实际高度来设置
    static final int BOARD_PIECE_SIZE = 110;  // 根据棋盘的实际格子大小来设置
    static final int BOARD_X_OFFSET = 22; // x1 - BOARD_PIECE_SIZE/2
    static final int BOARD_Y_OFFSET = 5; // y1 - BOARD_PIECE_SIZE/2
    static final int BOARD_GRID_INTERVAL = 136;  // (x2-x1)/8

    public Rect srcBoardRect, destBoardRect;
    public int Board_width, Board_height;
    public float scaleRatio;

    public GameController controller;

    // ==== 重绘同步（根因 1/2/3：脏标志驱动，替代"每 100ms 无条件全盘重绘"）====
    /** 渲染锁：保护脏标志；刷帧线程无脏时在它上面休眠，requestRender 置脏后立刻唤醒 */
    private final Object renderLock = new Object();
    /** 脏标志：初始 true 保证新 surface 首帧必画；此后只在局面/高亮/箭头状态变化时置位 */
    private boolean renderDirty = true;

    // ==== 绘制对象复用（根因 8：原实现每帧每格 new，GC 抖动丢帧）====
    // 绘制只发生在唯一刷帧线程，这些复用字段无并发冲突
    private final Position reusePos = new Position(0, 0);
    private final Rect reuseSrcRect = new Rect();
    private final Rect reuseDstRect = new Rect();
    private final Rect reuseCoordRect = new Rect();
    private final XYCoord reuseCrdA = new XYCoord(0, 0);
    private final XYCoord reuseCrdB = new XYCoord(0, 0);
    private final XYCoord arrowCrd3 = new XYCoord(0, 0);
    private final XYCoord arrowCrd4 = new XYCoord(0, 0);
    private final XYCoord arrowCrd = new XYCoord(0, 0);
    private final Paint suggestPaint = new Paint();
    private final Paint historyPaint = new Paint();
    private final ArrowShape arrowShape = new ArrowShape();
    private final Path arrowPath = new Path();


    public ChessView(Context context, GameController controller) {
        super(context);
        this.controller = controller;
        getHolder().addCallback(this);
        initBitmaps();
    }

    public void initBitmaps() {
        ChessBoardBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.chessboard);
        srcBoardRect = new Rect(0, 0, ChessBoardBitmap.getWidth(), ChessBoardBitmap.getHeight());

        B_box = BitmapFactory.decodeResource(getResources(), R.drawable.b_box);
        R_box = BitmapFactory.decodeResource(getResources(), R.drawable.r_box);
        R_pot = BitmapFactory.decodeResource(getResources(), R.drawable.redpot);
        B_pot = BitmapFactory.decodeResource(getResources(), R.drawable.blackpot);

        // these values should be consistent with Piece.java
        PieceBitmaps[0] = BitmapFactory.decodeResource(getResources(), R.drawable.r_shuai);
        PieceBitmaps[1] = BitmapFactory.decodeResource(getResources(), R.drawable.r_shi);
        PieceBitmaps[2] = BitmapFactory.decodeResource(getResources(), R.drawable.r_xiang);
        PieceBitmaps[3] = BitmapFactory.decodeResource(getResources(), R.drawable.r_ma);
        PieceBitmaps[4] = BitmapFactory.decodeResource(getResources(), R.drawable.r_ju);
        PieceBitmaps[5] = BitmapFactory.decodeResource(getResources(), R.drawable.r_pao);
        PieceBitmaps[6] = BitmapFactory.decodeResource(getResources(), R.drawable.r_bing);
        PieceBitmaps[7] = BitmapFactory.decodeResource(getResources(), R.drawable.b_jiang);
        PieceBitmaps[8] = BitmapFactory.decodeResource(getResources(), R.drawable.b_shi);
        PieceBitmaps[9] = BitmapFactory.decodeResource(getResources(), R.drawable.b_xiang);
        PieceBitmaps[10] = BitmapFactory.decodeResource(getResources(), R.drawable.b_ma);
        PieceBitmaps[11] = BitmapFactory.decodeResource(getResources(), R.drawable.b_ju);
        PieceBitmaps[12] = BitmapFactory.decodeResource(getResources(), R.drawable.b_pao);
        PieceBitmaps[13] = BitmapFactory.decodeResource(getResources(), R.drawable.b_zu);

        // load drawables for choice bitmaps
        ChoiceBitmaps[0] = BitmapFactory.decodeResource(getResources(), R.drawable.digit1);
        ChoiceBitmaps[1] = BitmapFactory.decodeResource(getResources(), R.drawable.digit2);
        ChoiceBitmaps[2] = BitmapFactory.decodeResource(getResources(), R.drawable.digit3);
        ChoiceBitmaps[3] = BitmapFactory.decodeResource(getResources(), R.drawable.digit4);
        ChoiceBitmaps[4] = BitmapFactory.decodeResource(getResources(), R.drawable.digit5);

        // get drawable for think state
        ThinkBitmap = DrawableUtil.drawableToBitmap(ChessApp.getContext().getDrawable(R.drawable.intelligence));
    }

    public void Draw(Canvas canvas) {
        Game game = controller.game;
        if(canvas == null) {
            return;
        }
        // draw chess board
        canvas.drawBitmap(ChessBoardBitmap, srcBoardRect, destBoardRect, null);

        Board board = game.currentBoard;

        // draw controller state in the middle of destBoardRect
        showControllerState(canvas);

        // draw piece
        for (int x = 0; x < Board.BOARD_PIECE_WIDTH; x++) {
            for (int y = 0; y < Board.BOARD_PIECE_HEIGHT; y++) {
                reusePos.x = x;
                reusePos.y = y;
                int piece = board.getPieceByPosition(reusePos);
                if (Piece.isValid(piece)) {
                    // valid piece, draw the bitmap
                    Bitmap bitmap = PieceBitmaps[piece-1];
                    fillSrcRect(bitmap, reuseSrcRect);
                    fillDestRect(reusePos, reuseDstRect);
                    canvas.drawBitmap(bitmap, reuseSrcRect, reuseDstRect, null);
                }
            }
        }

        if(game.startPos != null) {
            // highlight selected piece
            HighlighSelectedPiece(canvas);

            // show all possible moves for selected piece
            int piece = game.currentBoard.getPieceByPosition(game.startPos);
            // draw all possible moves
            if(Piece.isRed(piece)) {
                fillSrcRect(R_pot, reuseSrcRect);
                for (Position pos : game.possibleToPositions) {
                    fillDestRect(pos, reuseDstRect);
                    canvas.drawBitmap(R_pot, reuseSrcRect, reuseDstRect, null);
                }
            } else if(Piece.isBlack(piece)){
                fillSrcRect(B_pot, reuseSrcRect);
                for (Position pos : game.possibleToPositions) {
                    fillDestRect(pos, reuseDstRect);
                    canvas.drawBitmap(B_pot, reuseSrcRect, reuseDstRect, null);
                }
            }
        }

        // draw arrows for last moves
        if(game.history.size() > 0) {
            DrawMoveHistory(canvas);
        }

        // if there are suggested moves, show them on the board
        if(game.suggestedMoves.size() > 0) {
            suggestPaint.setStyle(Paint.Style.FILL);
            suggestPaint.setAntiAlias(true);
            suggestPaint.setColor(Color.GREEN);
            for(int i = 0; i < game.suggestedMoves.size() && i < MAX_SUGGESTED_MOVES ; i++) {
                Move move = game.suggestedMoves.get(i);
                fillCoord(move.fromPosition, reuseCrdA);
                fillCoord(move.toPosition, reuseCrdB);
                DrawArrow(canvas, reuseCrdA, reuseCrdB, suggestPaint, ChoiceBitmaps[i]);
            }
        }
    }

    private void showControllerState(Canvas canvas) {
        int targetSize = Scale(30);
        int xOffset = Scale(BOARD_GRID_INTERVAL * 4 + 45);
        reuseDstRect.set(destBoardRect.centerX() - targetSize + xOffset, destBoardRect.centerY() - targetSize,
                destBoardRect.centerX() + targetSize + xOffset, destBoardRect.centerY() + targetSize);
        if (controller.isRedTurn()) {
            Bitmap bitmap = PieceBitmaps[0];
            fillSrcRect(bitmap, reuseSrcRect);
            canvas.drawBitmap(bitmap, reuseSrcRect, reuseDstRect, null);
        } else if (controller.isBlackTurn()) {
            Bitmap bitmap = PieceBitmaps[7];
            fillSrcRect(bitmap, reuseSrcRect);
            canvas.drawBitmap(bitmap, reuseSrcRect, reuseDstRect, null);
        } else {
            fillSrcRect(ThinkBitmap, reuseSrcRect);
            canvas.drawBitmap(ThinkBitmap, reuseSrcRect, reuseDstRect, null);
        }
    }

    private void HighlighSelectedPiece(Canvas canvas) {
        // draw selected piece
        Game game = controller.game;
        Board board = game.currentBoard;
        Position pos = game.startPos;
        int piece = board.getPieceByPosition(pos);
        if (Piece.isValid(piece)) {
            // valid piece is selected
            fillDestRect(pos, reuseDstRect);
            if (Piece.isRed(piece)) {
                fillSrcRect(R_box, reuseSrcRect);
                canvas.drawBitmap(R_box, reuseSrcRect, reuseDstRect, null);
            } else {
                fillSrcRect(B_box, reuseSrcRect);
                canvas.drawBitmap(B_box, reuseSrcRect, reuseDstRect, null);
            }
        }
    }

    private void DrawMoveHistory(Canvas canvas) {
        Game game = controller.game;

        historyPaint.setStyle(Paint.Style.FILL);
        historyPaint.setAntiAlias(true);

        // draw arrow for the last several moves in historyMoves
        int num_of_history_moves = 2;
        if(controller!= null && controller.settings != null) {
            num_of_history_moves = controller.settings.getHistory_moves();
        }
        for(int i = game.history.size() - 1; i >= 0 && i >= game.history.size() - num_of_history_moves; i--) {
            Game.HistoryRecord record = game.history.get(i);
            fillCoord(record.move.fromPosition, reuseCrdA);
            fillCoord(record.move.toPosition, reuseCrdB);

            // color
            if(Piece.isRed(record.move.piece)) {
                historyPaint.setColor(Color.RED);
            } else {
                historyPaint.setColor(Color.BLACK);
            }

            // calculate alpha value, the last move is the most opaque one
            int idx = (game.history.size() - 1 - i);
            int value = 220 - idx * 40;
            if(value < 0) value = 0;
            historyPaint.setAlpha(value);

            DrawArrow(canvas, reuseCrdA, reuseCrdB, historyPaint, null);
        }
    }

    /*
        * 画箭头，并在箭头上显示bitmap。这个bitmap一般是数字，来标识箭头
     */
    void DrawArrow(Canvas canvas, XYCoord crd0, XYCoord crd1, Paint p, Bitmap bitmap) {
        arrowPath.reset();
        arrowShape.getTransformedPath(arrowPath, crd0.x, crd0.y, crd1.x, crd1.y);
        canvas.drawPath(arrowPath, p);

        if(bitmap != null) {
            int offset_to_endpos = 80;
            int width_of_bitmap = 80;
            // 找到合适的位置，然后在那个位置画bitmap

            // 离crd1 offset_to_endpos个像素的位置
            int dx = crd1.x - crd0.x;
            int dy = crd1.y - crd0.y;
            int d = (int)Math.sqrt(dx*dx + dy*dy);
            arrowCrd3.x = crd1.x - offset_to_endpos * dx / d;
            arrowCrd3.y = crd1.y - offset_to_endpos * dy / d;

            // 两者之间3/5的位置
            arrowCrd4.x = (crd0.x*2 + crd1.x*3) / 5;
            arrowCrd4.y = (crd0.y*2 + crd1.y*3) / 5;

            // 取离crd1最近的点, 防止箭头太长时，数字离箭头太远
            int d3 = (arrowCrd3.x - crd1.x) * (arrowCrd3.x - crd1.x) + (arrowCrd3.y - crd1.y) * (arrowCrd3.y - crd1.y);
            int d4 = (arrowCrd4.x - crd1.x) * (arrowCrd4.x - crd1.x) + (arrowCrd4.y - crd1.y) * (arrowCrd4.y - crd1.y);
            XYCoord crd = d3 < d4 ? arrowCrd3 : arrowCrd4;

            // draw bitmap to crd position
            int sx = bitmap.getWidth();
            int sy = bitmap.getHeight();
            int nx = width_of_bitmap / 2;
            int ny = nx * sy / sx / 2;
            fillSrcRect(bitmap, reuseSrcRect);
            reuseDstRect.set(crd.x - nx, crd.y - ny, crd.x + nx, crd.y + ny);
            canvas.drawBitmap(bitmap, reuseSrcRect, reuseDstRect, null);
        }
    }

    public int Scale(int x) {
        return (int)(x * scaleRatio);
    }

    @NonNull
    private Rect fillDestRect(Position pos, Rect out) {
        out.set(
                Scale(pos.x * BOARD_GRID_INTERVAL + BOARD_X_OFFSET),
                Scale(pos.y * BOARD_GRID_INTERVAL + BOARD_Y_OFFSET),
                Scale(pos.x * BOARD_GRID_INTERVAL + BOARD_X_OFFSET + BOARD_PIECE_SIZE),
                Scale(pos.y * BOARD_GRID_INTERVAL + BOARD_Y_OFFSET + BOARD_PIECE_SIZE));
        return out;
    }

    @NonNull
    private Rect getDestRect(Position pos) {
        return fillDestRect(pos, new Rect());
    }

    /** 用位图整幅图像填充 src rect（复用调用方的 Rect，热路径免分配） */
    private static Rect fillSrcRect(Bitmap bitmap, Rect out) {
        out.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        return out;
    }

    /** 棋盘格中心坐标填入复用的 XYCoord（热路径免分配） */
    private void fillCoord(Position pos, XYCoord out) {
        Rect r = fillDestRect(pos, reuseCoordRect);
        out.x = r.centerX();
        out.y = r.centerY();
    }


    public XYCoord getCoordByPosition(Position pos) {
        Rect r = getDestRect(pos);
        return new XYCoord(r.centerX(), r.centerY());
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        Board_width = MeasureSpec.getSize(widthMeasureSpec);
        Board_height = Board_width * BOARD_HEIGHT / BOARD_WIDTH;
        scaleRatio = (float) Board_width / BOARD_WIDTH;

        destBoardRect = new Rect(0, 0, Board_width, Board_height);
        setMeasuredDimension(Board_width, Board_height);
    }


    public void surfaceChanged(SurfaceHolder holder, int format, int width,
                               int height) {
    }

    public void surfaceCreated(SurfaceHolder holder) {
        // 先停掉可能残留的旧线程（正常路径 surfaceDestroyed 已停；防御回调次序异常导致线程叠加）
        stopRenderThread();
        this.thread = new ChessViewThread(getHolder());
        this.thread.start();
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // Surface 已销毁必须停线程：修复"每次 surface 重建泄漏一个 10fps 自旋线程"（根因 2）
        stopRenderThread();
    }

    /** 停掉当前刷帧线程：置退出标志并唤醒（可能正 wait），join 等其退出（含最后一帧的画布释放） */
    private void stopRenderThread() {
        ChessViewThread t = this.thread;
        this.thread = null;
        if (t == null) return;
        t.running = false;
        synchronized (renderLock) {
            renderLock.notifyAll();
        }
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 事件驱动重绘入口：局面/选中/高亮/箭头等棋盘状态变化后由 UI 调用。
     * 脏标志置位并唤醒刷帧线程；线程无脏标志时不 lockCanvas 不绘制（静止时零重绘，根因 1）。
     */
    public void requestRender() {
        synchronized (renderLock) {
            renderDirty = true;
            renderLock.notifyAll();
        }
    }

    public Position getPosByCoord(float x, float y) {
        float vx = x / scaleRatio;
        float vy = y / scaleRatio;

        int ix = (int)((vx - BOARD_X_OFFSET) / BOARD_GRID_INTERVAL);
        int iy = (int)((vy - BOARD_Y_OFFSET) / BOARD_GRID_INTERVAL);

        Rect rect = getDestRect(new Position(ix, iy));
        if(rect.contains((int)x, (int)y)) {
            Log.d("ChessView", "getPosByCoord: " + ix + ", " + iy);
            return new Position(ix, iy);
        } else {
            Log.d("ChessView", "getPosByCoord: " + "out of bound");
        }

        return null;
    }

    class ChessViewThread extends Thread {
        //刷帧线程
        public int span = 100;//相邻两帧最小间隔（毫秒），事件风暴下的绘制节奏上限
        /** 无脏标志时的兜底自检周期：只醒来检查脏标志，不 lockCanvas 不绘制（静止时零重绘） */
        private static final long IDLE_WAIT_MS = 500;
        /** 退出标志：surface 销毁（stopRenderThread）置 false，run() 循环检测后结束（根因 1/2） */
        public volatile boolean running = true;
        public SurfaceHolder surfaceHolder;

        public ChessViewThread(SurfaceHolder surfaceHolder) {
            this.surfaceHolder = surfaceHolder;
        }

        public void run() {//重写的方法
            while (running) {
                // 无脏标志时休眠等待 requestRender 唤醒；IDLE_WAIT_MS 兜底自检防漏单
                synchronized (renderLock) {
                    while (running && !renderDirty) {
                        try {
                            renderLock.wait(IDLE_WAIT_MS);
                        } catch (InterruptedException e) {
                            return;// 中断即退出（surface 销毁路径）
                        }
                    }
                    if (!running) return;
                    renderDirty = false;
                }
                Canvas c = null;//画布
                try {
                    c = this.surfaceHolder.lockCanvas();
                    if (c != null) Draw(c);//绘制方法
                } catch (Exception e) {
                    e.printStackTrace();//输出异常堆栈信息
                } finally {
                    if (c != null) {
                        try {
                            this.surfaceHolder.unlockCanvasAndPost(c);
                        } catch (Exception e) {
                            e.printStackTrace();// surface 销毁竞态下可能失败，忽略
                        }
                    }
                }
                try {
                    Thread.sleep(span);//保持原绘制节奏下限
                } catch (Exception e) {
                    return;// 中断即退出
                }
            }
        }
    }
}