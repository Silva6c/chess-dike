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

public interface UCIEngine {

    /**
     * Start engine.
     */
    void initialize();

    /**
     * Shut down engine.
     */
    void shutDown();

    /**
     * Initialize default options.
     */
    void initConfig(EngineConfig engineConfig);

    /**
     * Set an engine integer option.
     */
    void setOption(String name, int value);

    /**
     * Set an engine option. If the option is not a string option,
     * value is converted to the correct type.
     *
     * @return True if the option was changed.
     */
    boolean setOption(String name, String value);

    /**
     * Register an option as supported by the engine.
     *
     * @param tokens The UCI option line sent by the engine, split in words.
     */
    UCIOptions.OptionBase registerOption(String[] tokens);

    /**
     * Read a line from the engine.
     *
     * @param timeoutMillis Maximum time to wait for data.
     * @return The line, without terminating newline characters,
     * or empty string if no data available,
     * or null if I/O error.
     */
    String readLineFromEngine(int timeoutMillis);

    /**
     * Write a line to the engine. \n will be added automatically.
     */
    void writeLineToEngine(String data);
}
