package com.xqdk.chess.openbook;

public class BookData {

    private String move;

    private int score;

    private double winRate;

    private int drawNum;

    private int loseNum;

    @Override
    public String toString() {
        return "BookData{" +
                "move='" + move + '\'' +
                ", score=" + score +
                ", winRate=" + winRate +
                '}';
    }

    public String getMove() {
        return move;
    }

    public void setMove(String move) {
        this.move = move;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public double getWinRate() {
        return winRate;
    }

    public void setWinRate(double winRate) {
        this.winRate = winRate;
    }

    public Integer getDrawNum() {
        return drawNum;
    }

    public void setDrawNum(Integer drawNum) {
        this.drawNum = drawNum;
    }

    public Integer getLoseNum() {
        return loseNum;
    }

    public void setLoseNum(Integer loseNum) {
        this.loseNum = loseNum;
    }
}
