/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.HLE.modules150;

import jpcsp.HLE.HLEFunction;
import jpcsp.Processor;
import jpcsp.Allegrex.CpuState;
import jpcsp.HLE.Modules;
import jpcsp.HLE.modules.HLEModule;

import org.apache.log4j.Logger;

public class sceNp extends HLEModule {

    protected static Logger log = Modules.getLogger("sceNp");

    @Override
    public String getName() {
        return "sceNp";
    }

    @HLEFunction(nid = 0x857B47D3, version = 150, checkInsideInterrupt = true)
    public void sceNp_857B47D3(Processor processor) {
        CpuState cpu = processor.cpu;

        log.info("sceNp_857B47D3");

        
        cpu.gpr[2] = 0;
    }

}