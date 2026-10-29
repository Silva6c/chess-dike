/*
    DroidFish - An Android chess program.
    Copyright (C) 2012  Peter Österlund, peterosterlund2@gmail.com

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

/**
 * 引擎启动参数。裁撤对弈页后唯一存活的字段是 workDir：getEngine() 工厂与
 * initConfig() 握手链的参数载体（AnalysisEngine 传入空 workDir，引擎文件
 * 直接从 nativeLibraryDir 运行）。
 */
public final class EngineConfig {
    public String workDir;      // Working directory for engine process

    public EngineConfig() {
        workDir = "";
    }
}
