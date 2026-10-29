package com.xqdk.chess.gamelogic;

import android.util.Log;

import java.io.Serializable;

/**
 * Created by zfdang on 2016-4-17.
 * This class represents the chess board data, basically it's represention of FEN string;
 */
public class Board implements Serializable {
    private static final long serialVersionUID = 2194052829642412444L;

    public boolean bRedGo = true;
    public int rounds = 1;
    static public int BOARD_PIECE_WIDTH = 9;
    static public int BOARD_PIECE_HEIGHT = 10;

    // do not access piece value directly, use getPieceByPosition instead
    // it's easy to make mistake with values of (x,y)
    private int[][] piece = new int[][]{
            {Piece.BJU, Piece.BMA, Piece.BXIANG, Piece.BSHI, Piece.BJIANG, Piece.BSHI, Piece.BXIANG, Piece.BMA, Piece.BJU},
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, Piece.BPAO, 0, 0, 0, 0, 0, Piece.BPAO, 0},
            {Piece.BZU, 0, Piece.BZU, 0, Piece.BZU, 0, Piece.BZU, 0, Piece.BZU},
            {0, 0, 0, 0, 0, 0, 0, 0, 0},

            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {Piece.WBING, 0, Piece.WBING, 0, Piece.WBING, 0, Piece.WBING, 0, Piece.WBING},
            {0, Piece.WPAO, 0, 0, 0, 0, 0, Piece.WPAO, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {Piece.WJU, Piece.WMA, Piece.WXIANG, Piece.WSHI, Piece.WSHUAI, Piece.WSHI, Piece.WXIANG, Piece.WMA, Piece.WJU},
    };

    public static boolean isValidPosition(Position pos) {
        if(pos == null) return false;
        return pos.x >= 0 && pos.x < BOARD_PIECE_WIDTH && pos.y >= 0 && pos.y < BOARD_PIECE_HEIGHT;
    }

    public Board() {
        bRedGo = true;
        rounds = 1;
    }

    public Board(Board b) {
        this.bRedGo = b.bRedGo;
        this.rounds = b.rounds;
        for (int i = 0; i < this.piece.length; i++) {
            this.piece[i] = b.piece[i].clone();
        }
    }

    public void clear(){
        // clear all pieces to empty
        for (int y = 0; y < BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < BOARD_PIECE_WIDTH; x++) {
                piece[y][x] = Piece.EMPTY;
            }
        }
    }

    /*
    * Set piece value by position， it should not be called directly
     */
    public boolean setPieceByPosition(Position pos, int value){
        if (isValidPosition(pos)) {
            if(value == Piece.EMPTY || Piece.isValid(value)){
                piece[pos.y][pos.x] = value;
                return true;
            }
        }
        return false;
    }

    public int getPieceByPosition(Position pos) {
        if(isValidPosition(pos)){
            return piece[pos.y][pos.x];
        } else {
            return -1;
        }
    }

    public int getPieceByPosition(int x, int y) {
        if (x >= 0 && x < BOARD_PIECE_WIDTH && y >= 0 && y < BOARD_PIECE_HEIGHT) {
            return piece[y][x];
        } else {
            return -1;
        }
    }

    public String toFENString() {
        // https://www.xqbase.com/protocol/pgnfen2.htm
        // https://www.xqbase.com/protocol/cchess_fen.htm
        // 按白方视角，描述由上至下、由左至右的盘面，以/符号来分隔相邻横列。白方大写字母、黑方小写字母。
        // 棋盘的编号：从左到右为a-i，从下到上为0-9
        // FEN字符串中棋子的位置的顺序是从左到右，从上到下，空格用数字表示:
        // a9-i9, a8-i8, ..., a1-i1, a0-i0
        String fen = "";
        for (int y = 0; y < BOARD_PIECE_HEIGHT; y++) {
            String row = "";
            int zeros = 0;
            for (int x = 0; x < BOARD_PIECE_WIDTH; x++) {
                int piece = getPieceByPosition(x, y);
                if (piece != 0) {
                    // if zeros > 0, add zeros to fen
                    if (zeros > 0) {
                        row += zeros;
                        zeros = 0;
                    }
                    // convert piece to FEN
                    char fenPiece = Piece.pieceCharMap.get(piece);
                    row += (char) (fenPiece);
                } else {
                    zeros++;
                }
            }
            if (zeros > 0) {
                row += zeros;
            }
            fen += row;
            if (y < BOARD_PIECE_HEIGHT - 1) {
                // add / to separate rows
                fen += "/";
            }
        }
        String result = String.format("%s %s - - %s %s", fen, bRedGo ? "w" : "b", 0, rounds);
        return result;
    }

    public boolean restoreFromFEN(String fenString) {
        if(fenString == null) return false;
        fenString = fenString.trim();

        // append missing parts
        if(fenString.endsWith("w") || fenString.endsWith("b")) {
            fenString += " - - 0 1";
        }

        // parse fen string
        String[] parts = fenString.split(" ");
        if (parts.length != 6) {
            Log.e("Board", "Failed to parse FEN string: " + fenString);
            return false;
        }

        // parse isRedGo
        String side = parts[1].toLowerCase();
        if(side.equals("w") || side.equals("r")) {
            // white or red, both are valid
            bRedGo = true;
        } else if(side.equals("b")) {
            bRedGo = false;
        } else {
            Log.e("Board", "Failed to parse side from FEN string: " + fenString);
            return false;
        }

        // parse rounds
        String part5 = parts[5];
        try {
            rounds = Integer.parseInt(part5);
        } catch (NumberFormatException e) {
            Log.e("Board", "Failed to parse rounds from FEN string: " + fenString);
            return false;
        }

        // parse fen string
        String fen = parts[0];
        int x = 0;
        int y = 0;
        // 解析前清盘：行数不足的 FEN 不应残留旧局面棋子
        clear();
        for (int i = 0; i < fen.length(); i++) {
            char c = fen.charAt(i);
            if (c == '/') {
                // next row；行宽必须满 9 且不能超过 10 行
                if (x != BOARD_PIECE_WIDTH || y >= BOARD_PIECE_HEIGHT - 1) {
                    Log.e("Board", "Failed to parse FEN row structure: " + fenString);
                    return false;
                }
                x = 0;
                y++;
            } else if (c >= '0' && c <= '9') {
                int zeros = c - '0';
                if (x + zeros > BOARD_PIECE_WIDTH) {
                    Log.e("Board", "Failed to parse FEN row width: " + fenString);
                    return false;
                }
                for (int k = 0; k < zeros; k++) {
                    piece[y][x] = 0;
                    x++;
                }
            } else {
                Integer value = Piece.pieceValueMap.get(c);
                if (value == null || x >= BOARD_PIECE_WIDTH) {
                    // 未知字符（含小写/大小写错误）此前会因自动拆箱 null 直接 NPE
                    Log.e("Board", "Failed to parse FEN piece char '" + c + "': " + fenString);
                    return false;
                }
                piece[y][x] = value;
                x++;
            }
        }
        if (x != BOARD_PIECE_WIDTH || y != BOARD_PIECE_HEIGHT - 1) {
            Log.e("Board", "Incomplete FEN string: " + fenString);
            return false;
        }
        return true;
    }

    public void randomizePieces(){
        // randomize values for all pieces
        for (int y = 0; y < BOARD_PIECE_HEIGHT; y++) {
            for (int x = 0; x < BOARD_PIECE_WIDTH; x++) {
                piece[y][x] = (int) (Math.random() * 14);
            }
        }
    }
}
