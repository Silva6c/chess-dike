package com.xqdk.chess;


import android.content.Context;
import android.media.MediaPlayer;

import com.xqdk.chess.controllers.GameController;

public class SoundPlayer {
    private final GameController controller;
    private MediaPlayer selectSound;
    private MediaPlayer moveSound;
    private MediaPlayer captureSound;
    private MediaPlayer checkSound;
    private MediaPlayer invalidSound;
    private MediaPlayer checkmateSound;
    private MediaPlayer readySound;

    public SoundPlayer(Context context, GameController gameController) {
        selectSound = MediaPlayer.create(context, R.raw.select);
        moveSound = MediaPlayer.create(context, R.raw.move);
        captureSound = MediaPlayer.create(context, R.raw.capture);
        checkSound = MediaPlayer.create(context, R.raw.check);
        invalidSound = MediaPlayer.create(context, R.raw.invalid);
        checkmateSound = MediaPlayer.create(context, R.raw.checkmate);
        readySound = MediaPlayer.create(context, R.raw.ready);

        controller = gameController;
    }

    /** 统一播放入口：MediaPlayer.create 可能返回 null（资源解码失败），判空防 NPE */
    private void play(MediaPlayer player) {
        if (player != null && controller != null && controller.settings.getSound_effect()) {
            player.start();
        }
    }

    public void select() {
        play(selectSound);
    }

    public void move() {
        play(moveSound);
    }

    public void capture() {
        play(captureSound);
    }

    public void check() {
        play(checkSound);
    }

    public void illegal() {
        play(invalidSound);
    }

    public void checkmate() {
        play(checkmateSound);
    }

    public void ready() {
        play(readySound);
    }

    public void release() {
        // MediaPlayer.create 可能返回 null，逐个判空
        if (selectSound != null) selectSound.release();
        if (moveSound != null) moveSound.release();
        if (captureSound != null) captureSound.release();
        if (checkSound != null) checkSound.release();
        if (invalidSound != null) invalidSound.release();
        if (checkmateSound != null) checkmateSound.release();
        if (readySound != null) readySound.release();
    }
}
