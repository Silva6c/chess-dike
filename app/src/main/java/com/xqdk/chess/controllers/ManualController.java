package com.xqdk.chess.controllers;

import android.content.Context;
import android.util.Log;

import com.xqdk.chess.ChessApp;
import com.xqdk.chess.gamelogic.Board;
import com.xqdk.chess.gamelogic.GameStatus;
import com.xqdk.chess.gamelogic.Move;
import com.xqdk.chess.gamelogic.Piece;
import com.xqdk.chess.gamelogic.Position;
import com.xqdk.chess.gamelogic.PvInfo;
import com.xqdk.chess.gamelogic.Rule;
import com.xqdk.chess.manuals.XQFManual;
import com.xqdk.chess.manuals.XQFParser;
import com.xqdk.chess.utils.PathUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class ManualController extends GameController{
    public XQFManual manual = null;
    public XQFManual.MoveNode moveNode = null;

    private Context context = null;
    private ControllerListener gui = null;

    public ManualController(ControllerListener listener) {
        super(listener);
        this.gui = listener;
        this.context = ChessApp.getContext();
    }

    public boolean loadManualFromFile(String filename) {
        if(filename.toLowerCase().endsWith(".xqf")) {
            try (InputStream inputStream = new FileInputStream(new File(filename))) {
                byte[] buffer = readAll(inputStream);

                // use XQFGame to parse the buffer
                manual = XQFParser.parse(buffer);
                if(manual == null) {
                    Log.e("ManualActivity", "Failed to parse XQF game: " + filename);
                    gui.onGameEvent(GameStatus.ILLEGAL, "Failed to parse XQF game: " + filename);
                    return false;
                }

                manual.setFilename(PathUtil.getFileName(filename));

                boolean result = manual.validateAllMoves();
                if (!result) {
                    Log.e("ManualActivity", "Failed to validate moves: "  + filename);
                }

                Log.d("ManualActivity", "Parsed XQF game: " + manual);

                // 根据XQFManual的getHeadMove()方法获取第一个MoveNode
                moveNode = manual.getHeadMove();

                // 根据第一个move的颜色，来确定那方先走
                if(moveNode.nextMoves.size() > 0) {
                    Move m = moveNode.nextMoves.get(0).move;
                    Position p = m.fromPosition;
                    int piece = manual.board.getPieceByPosition(p);
                    manual.board.bRedGo = Piece.isRed(piece);
                    setSatate(manual.board.bRedGo);
                }

                // reset game
                game.currentBoard = new Board(manual.board);
                game.history.clear();
                game.suggestedMoves.clear();
                game.startPos = null;
                game.endPos = null;

                return true;
            } catch (IOException e) {
                Log.e("ManualController", "Failed to load manual from file: " + filename);
                Log.e("ManualController", e.getMessage());
            } catch (RuntimeException e) {
                // 损坏文件在解析深处的任何运行时异常都按"解析失败"处理，不崩溃
                Log.e("ManualController", "Runtime error parsing XQF: " + filename, e);
                gui.onGameEvent(GameStatus.ILLEGAL, "棋谱文件损坏或格式错误");
            }
        }

        return false;
    }

    /** 循环读满整个流：单次 read 不保证读满（大文件/存储层可能部分读） */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            bos.write(chunk, 0, n);
        }
        return bos.toByteArray();
    }

    public void manualForward() {
        if(manual == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "未打开棋谱...");
            return;
        }

        if(moveNode == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "状态异常");
            return;
        }

        ArrayList<XQFManual.MoveNode> children = moveNode.nextMoves;
        if(children.size() == 1){
            // only one child, move to it
            moveNode = children.get(0);
            if(moveNode != null) {
                Move m = moveNode.move;
                if(m != null) {
                    // 棋谱着法落盘前做真实校验（几何+王安全），损坏棋谱不再污染盘面
                    if (!Rule.isValidMove(m, game.currentBoard)) {
                        gui.onGameEvent(GameStatus.ILLEGAL, "棋谱着法无效: " + m.getChsString());
                        return;
                    }
                    game.startPos = m.fromPosition;
                    game.endPos = m.toPosition;
                    game.movePiece();
                    toggleTurn();

                    gui.onGameEvent(GameStatus.MOVE, game.getLastMoveDesc());
                }
            }
        } else if(children.size() > 1){
            // multiple children, show a dialog to let user choose
            multiPVs.clear();
            for(XQFManual.MoveNode child : children){
                PvInfo pvInfo = new PvInfo(0, 0, 0, 0, 0, 0, 0, 0, false, false, false, new ArrayList<>());
                pvInfo.pv.add(child.move);
                multiPVs.add(pvInfo);
            }
            game.generateSuggestedMoves(multiPVs);
            gui.onGameEvent(GameStatus.MULTIPV, "请选择分支: ");
            Log.d("ManualController", "multiPVs: " + multiPVs.size());
        } else{
            gui.onGameEvent(GameStatus.ILLEGAL, "没有下一步了");
        }

    }

    public void manualBack() {
        if(manual == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "未打开棋谱...");
            return;
        }

        if(moveNode == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "状态异常");
            return;
        }

        if(moveNode.parent == null) {
            String hint = "已经到达开局" + "," + getFirstMoveColor();
            game.suggestedMoves.clear();
            gui.onGameEvent(GameStatus.MOVE, hint);
            return;
        }

        moveNode = moveNode.parent;
        game.undoMove();
        toggleTurn();
        gui.onGameEvent(GameStatus.MOVE, "回到上一步");
    }

    public String getFirstMoveColor(){
        if(manual == null) {
            return "未打开棋谱...";
        }

        if(manual.board.bRedGo) {
            return "红方先行";
        } else {
            return "黑方先行";
        }
    }

    public void manualFirst() {
        if(manual == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "未打开棋谱...");
            return;
        }

        moveNode = manual.getHeadMove();

        // reset game
        game.currentBoard = new Board(manual.board);
        game.history.clear();
        game.suggestedMoves.clear();
        game.startPos = null;
        game.endPos = null;

        setSatate(manual.board.bRedGo);

        String hint = "回到开局" + "," + getFirstMoveColor();
        gui.onGameEvent(GameStatus.MOVE, hint);
    }

    public void selectBranch(int i) {
        if(manual == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "未打开棋谱...");
            return;
        }

        if(moveNode == null) {
            gui.onGameEvent(GameStatus.ILLEGAL, "状态异常");
            return;
        }

        ArrayList<XQFManual.MoveNode> children = moveNode.nextMoves;
        if(i < children.size()) {
            moveNode = children.get(i);
            if(moveNode != null) {
                Move m = moveNode.move;
                if(m != null) {
                    // 棋谱着法落盘前做真实校验（几何+王安全），损坏棋谱不再污染盘面
                    if (!Rule.isValidMove(m, game.currentBoard)) {
                        gui.onGameEvent(GameStatus.ILLEGAL, "棋谱着法无效: " + m.getChsString());
                        return;
                    }
                    game.startPos = m.fromPosition;
                    game.endPos = m.toPosition;
                    game.movePiece();
                    toggleTurn();

                    game.suggestedMoves.clear();
                    gui.onGameEvent(GameStatus.MOVE, "分支" + (i+1) + ": " + game.getLastMoveDesc());
                }
            }
        } else {
            gui.onGameEvent(GameStatus.ILLEGAL, "错误的分支: " + (i+1));
        }
    }
}
