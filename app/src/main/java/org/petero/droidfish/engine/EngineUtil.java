/*
    DroidFish - An Android chess program.
    Copyright (C) 2011-2012  Peter Österlund, peterosterlund2@gmail.com

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

public class EngineUtil {
    static {
        System.loadLibrary("nativeutil");
    }

    /**
     * Change the priority of a process.
     */
    static native void reNice(int pid, int prio);

    /**
     * SIMD/点积特性检测（nativeutil.cpp 已有实现，此前零调用）：
     * aarch64 返回 ASIMDDP（dotprod）支持位——dotprod 引擎的 NNUE int8 点积
     * 走专用指令，不支持的设备上运行会 SIGILL，选错变体后果是崩溃而非变慢。
     * x86 分支语义为 SSE4.1（转译环境不依赖本检测结果）。
     */
    static native boolean isSimdSupported();

    /**
     * For synchronizing non thread safe native calls.
     */
    static final Object nativeLock = new Object();
}
