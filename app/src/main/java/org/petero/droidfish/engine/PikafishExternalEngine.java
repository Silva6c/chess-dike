/*
    DroidFish - An Android chess program.
    Copyright (C) 2011-2014  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.petero.droidfish.engine;

import android.util.Log;

import com.xqdk.chess.BuildConfig;
import com.xqdk.chess.ChessApp;

import org.petero.droidfish.player.EngineListener;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Stockfish engine running as process, started from assets resource.
 * originally, this class will copy the engine file from assets to /data/data/org.petero.droidfish/files
 * but not we will use the engine file in /data/lib directory directly
 * this is because Android Q restricts access to the app's private directories
 * see https://withme.skullzbones.com/blog/programming/execute-native-binaries-android-q-no-root/
 * so you will see lots of legacy codes here
 */
public class PikafishExternalEngine extends ExternalEngine {
    private static final String[] optionNames = {"evalfile"};
    private static final String[] optionValues = {"libpikafish.nnue.so"};

    // 引擎变体运行时自动选择的候选文件（两个 so 均随包分发在 nativeLibraryDir）：
    // dotprod（ARMv8.2 ASIMDDP）机型的 NNUE int8 点积走专用指令，基础变体慢数倍；
    // 反之在不支持 dotprod 的设备上运行 dotprod 引擎会 SIGILL——按 CPU 特性选，
    // 不再依赖用户装对 flavor
    private static final String PikafishEngineFileArmv8 = "libpikafish-armv8.so";
    private static final String PikafishEngineFileDotprod = "libpikafish-armv8-dotprod.so";
    // flavor 构建字段降级为自动选择失败时的回退值（保持产物命名与历史行为兼容）
    private static final String PikafishEngineFile = BuildConfig.PIKAFISH_ENGINE_FILE;

    private final String nativeLibraryDir = ChessApp.getContext().getApplicationInfo().nativeLibraryDir;

    /**
     * 引擎文件运行时自动选择：优先按 ASIMDDP 特性选 dotprod/armv8 变体；
     * 检测异常（native 库加载失败等）回退 flavor 配置值；所选文件不存在时
     * 再回退 flavor 配置值（防打包形态变化后指向缺失文件）。
     */
    private File engineFile() {
        boolean dotprod;
        try {
            dotprod = EngineUtil.isSimdSupported();
        } catch (Throwable t) {
            Log.w("ExternalPikafishEngine", "simd detect failed, fallback to flavor config", t);
            dotprod = PikafishEngineFile.contains("dotprod");
        }
        String name = dotprod ? PikafishEngineFileDotprod : PikafishEngineFileArmv8;
        File f = new File(nativeLibraryDir, name);
        if (!f.exists()) f = new File(nativeLibraryDir, PikafishEngineFile);
        Log.i("ExternalPikafishEngine", "engine variant: " + f.getName()
                + " (dotprod=" + dotprod + ")");
        return f;
    }

    public PikafishExternalEngine(String workDir, EngineListener listener) {
        super("pikafish", workDir, listener);
    }

    /**
     * Return true if the UCI option can be edited in the "Engine Options" dialog.
     */
    //    pikafish目前的uci命令的输出
    //    id author the Pikafish developers (see AUTHORS file)
    //    option name Debug Log File type string default
    //    option name NumaPolicy type string default auto
    //    option name Threads type spin default 1 min 1 max 1024
    //    option name Hash type spin default 16 min 1 max 33554432
    //    option name Clear Hash type button
    //    option name Ponder type check default false
    //    option name MultiPV type spin default 1 min 1 max 128
    //    option name Move Overhead type spin default 10 min 0 max 5000
    //    option name nodestime type spin default 0 min 0 max 10000
    //    option name Skill Level type spin default 20 min 0 max 20
    //    option name Mate Threat Depth type spin default 10 min 0 max 10
    //    option name Repetition Rule type combo default AsianRule var AsianRule var ChineseRule var SkyRule var ComputerRule var AllowChase var YitianRule
    //    option name Draw Rule type combo default None var None var DrawAsBlackWin var DrawAsRedWin var DrawRepAsBlackWin var DrawRepAsRedWin
    //    option name Rule60MaxPly type spin default 120 min 0 max 120
    //    option name UCI_LimitStrength type check default false
    //    option name UCI_Elo type spin default 1280 min 1280 max 3133
    //    option name ScoreType type combo default Elo var Elo var PawnValueNormalized var Raw
    //    option name LU_Output type check default true
    //    option name EvalFile type string default pikafish.nnue
    //    uciok
    @Override
    protected boolean editableOption(String name) {
        name = name.toLowerCase(Locale.US);
        if (super.editableOption(name))
            return true;
        // pikafish可修改的选项
        String[] editable = {"numapolicy", "threads", "hash", "clear hash", "ponder", "multipv", "move overhead", "skill level",
                "mate threat depth", "repetition rule", "draw rule", "rule60maxply", "scoretype", "lu_output", "evalfile"};
        return Arrays.asList(editable).contains(name);
    }


    @Override
    protected String copyFile(File from, File exeDir) throws IOException {
        // now this method does not copy file any longer,
        // it just use files in data/lib/ folder
        return engineFile().getAbsolutePath();
    }

    @Override
    public void initConfig(EngineConfig engineConfig) {
        super.initConfig(engineConfig);
    }

    /**
     * Handles setting the EvalFile UCI option to a full path, otherwise run super.setOption
     */
    @Override
    public boolean setOption(String name, String value) {
        for (int i = 0; i < optionNames.length; i++) {
            if (name.toLowerCase(Locale.US).equals(optionNames[i]) && optionValues[i].equals(value)) {
                // update value to the absolute path of the option file
                value = new File(nativeLibraryDir, value).getAbsolutePath();
                writeLineToEngine(String.format(Locale.US, "setoption name %s value %s", name, value));
                Log.d(  "ExternalPikafishEngine", "setOption: " + name + " " + value);
                return true;
            }
        }
        return super.setOption(name, value);
    }
}
