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

import static jpcsp.util.Utilities.readStringNZ;

import jpcsp.HLE.HLEFunction;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import jpcsp.Emulator;
import jpcsp.Memory;
import jpcsp.MemoryMap;
import jpcsp.Allegrex.CpuState;
import jpcsp.Debugger.DumpDebugState;
import jpcsp.HLE.Modules;
import jpcsp.HLE.kernel.managers.SceUidManager;
import jpcsp.HLE.kernel.types.MemoryChunk;
import jpcsp.HLE.kernel.types.MemoryChunkList;
import jpcsp.HLE.kernel.types.SceKernelErrors;
import jpcsp.HLE.modules.HLEModule;
import jpcsp.util.Utilities;

import org.apache.log4j.Logger;

/*
 * TODO list:
 * 1. Use the partitionid in functions that use it as a parameter.
 *  -> Info:
 *      1 = kernel, 2 = user, 3 = me, 4 = kernel mirror (from potemkin/dash)
 *      http://forums.ps2dev.org/viewtopic.php?p=75341#75341
 *      8 = slim, topaddr = 0x8A000000, size = 0x1C00000 (28 MB), attr = 0x0C
 *      8 = slim, topaddr = 0x8BC00000, size = 0x400000 (4 MB), attr = 0x0C
 *
 * 2. Implement format string parsing and reading variable number of parameters
 * in sceKernelPrintf.
 */
public class SysMemUserForUser extends HLEModule {
    protected static Logger log = Modules.getLogger("SysMemUserForUser");
    protected static Logger stdout = Logger.getLogger("stdout");
    protected static HashMap<Integer, SysMemInfo> blockList;
    protected static MemoryChunkList freeMemoryChunks;
    protected int firmwareVersion = 150;
    public static final int defaultSizeAlignment = 256;
    protected boolean memory64MB = false;

    // PspSysMemBlockTypes
    public static final int PSP_SMEM_Low = 0;
    public static final int PSP_SMEM_High = 1;
    public static final int PSP_SMEM_Addr = 2;
    public static final int PSP_SMEM_LowAligned = 3;
    public static final int PSP_SMEM_HighAligned = 4;

    public static final int KERNEL_PARTITION_ID = 1;
    public static final int USER_PARTITION_ID = 2;

    @Override
	public String getName() { return "SysMemUserForUser"; }

	protected boolean started = false;

	@Override
	public void start() {
		if (!started) {
			reset();
			started = true;
		}

        super.start();
	}

	@Override
	public void stop() {
		started = false;

        super.stop();
	}

    public void reset() {
		blockList = new HashMap<Integer, SysMemInfo>();

        int startFreeMem = MemoryMap.START_USERSPACE;
        int endFreeMem = MemoryMap.END_USERSPACE;
        MemoryChunk initialMemory = new MemoryChunk(startFreeMem, endFreeMem - startFreeMem + 1);
        freeMemoryChunks = new MemoryChunkList(initialMemory);
	}

    public void setMemory64MB(boolean isMemory64MB) {
    	if (memory64MB != isMemory64MB) {
    		memory64MB = isMemory64MB;

	    	if (memory64MB) {
	    		MemoryMap.END_RAM = MemoryMap.END_RAM_64MB;
	    		MemoryMap.END_USERSPACE = MemoryMap.END_USERSPACE_64MB;
	    	} else {
	    		MemoryMap.END_RAM = MemoryMap.END_RAM_32MB;
	    		MemoryMap.END_USERSPACE = MemoryMap.END_USERSPACE_32MB;
	    	}
			MemoryMap.SIZE_RAM = MemoryMap.END_RAM - MemoryMap.START_RAM + 1;

			if (!Memory.getInstance().allocate()) {
				log.error(String.format("Failed to resize the PSP memory from %s to %s", memory64MB ? "32MB" : "64MB", memory64MB ? "64MB" : "32MB"));
				Emulator.PauseEmuWithStatus(Emulator.EMU_STATUS_MEM_ANY);
			}

			reset();
    	}
    }

    public static class SysMemInfo implements Comparable<SysMemInfo> {

        public final int uid;
        public final int partitionid;
        public final String name;
        public final int type;
        public final int size;
        public final int allocatedSize;
        public final int addr;

        public SysMemInfo(int partitionid, String name, int type, int size, int allocatedSize, int addr) {
            this.partitionid = partitionid;
            this.name = name;
            this.type = type;
            this.size = size;
            this.allocatedSize = allocatedSize;
            this.addr = addr;

            uid = SceUidManager.getNewUid("SysMem");
            blockList.put(uid, this);
        }

        @Override
        public String toString() {
            return String.format("SysMemInfo[addr=0x%08X-0x%08X, uid=%x, partition=%d, name='%s', type=%s, size=0x%X (allocated=0x%X)]", addr, addr + allocatedSize, uid, partitionid, name, getTypeName(type), size, allocatedSize);
        }

        public void free() {
        	blockList.remove(uid);
        }

        @Override
        public int compareTo(SysMemInfo o) {
            if (addr == o.addr) {
                log.warn("Set invariant broken for SysMemInfo " + this);
                return 0;
            }
            return addr < o.addr ? -1 : 1;
        }
    }

    static protected String getTypeName(int type) {
        String typeName;

        switch (type) {
            case PSP_SMEM_Low:
                typeName = "PSP_SMEM_Low";
                break;
            case PSP_SMEM_High:
                typeName = "PSP_SMEM_High";
                break;
            case PSP_SMEM_Addr:
                typeName = "PSP_SMEM_Addr";
                break;
            case PSP_SMEM_LowAligned:
                typeName = "PSP_SMEM_LowAligned";
                break;
            case PSP_SMEM_HighAligned:
                typeName = "PSP_SMEM_HighAligned";
                break;
            default:
                typeName = "UNHANDLED " + type;
                break;
        }

        return typeName;
    }

    // Allocates to 256-byte alignment
    public SysMemInfo malloc(int partitionid, String name, int type, int size, int addr) {
        int allocatedAddress = 0;

        int alignment = defaultSizeAlignment - 1;

        // The allocated size has not to be aligned to the requested alignment
        // (for PSP_SMEM_LowAligned or PSP_SMEM_HighAligned),
        // it is only aligned to the default size alignment.
        int allocatedSize = Utilities.alignUp(size, alignment);

        if (type == PSP_SMEM_LowAligned || type == PSP_SMEM_HighAligned) {
            // Use the alignment provided in the addr parameter
            alignment = addr - 1;
        }

        switch (type) {
        	case PSP_SMEM_Low:
        	case PSP_SMEM_LowAligned:
        		allocatedAddress = freeMemoryChunks.allocLow(allocatedSize, alignment);
        		break;
        	case PSP_SMEM_High:
        	case PSP_SMEM_HighAligned:
        		allocatedAddress = freeMemoryChunks.allocHigh(allocatedSize, alignment);
        		break;
        	case PSP_SMEM_Addr:
        		allocatedAddress = freeMemoryChunks.alloc(addr, allocatedSize);
        		break;
    		default:
    			log.warn(String.format("malloc: unknown type %s", getTypeName(type)));
        }

        SysMemInfo sysMemInfo;
		if (allocatedAddress == 0) {
            log.warn(String.format("malloc cannot allocate partition=%d, type=%s, size=0x%X, addr=0x%08X", partitionid, getTypeName(type), size, addr));
			if (log.isTraceEnabled()) {
				log.trace("Free list: " + freeMemoryChunks);
				log.trace("Allocated blocks:\n" + getDebugAllocatedMem() + "\n");
			}
			sysMemInfo = null;
		} else {
			sysMemInfo = new SysMemInfo(partitionid, name, type, size, allocatedSize, allocatedAddress);

			if (log.isDebugEnabled()) {
				log.debug(String.format("malloc partition=%d, type=%s, size=0x%X, addr=0x%08X: returns 0x%08X", partitionid, getTypeName(type), size, addr, allocatedAddress));
				if (log.isTraceEnabled()) {
					log.trace("Free list after malloc: " + getDebugFreeMem());
					log.trace("Allocated blocks after malloc:\n" + getDebugAllocatedMem() + "\n");
				}
			}
		}

		return sysMemInfo;
    }

    public String getDebugFreeMem() {
    	return freeMemoryChunks.toString();
    }

    public String getDebugAllocatedMem() {
    	StringBuilder result = new StringBuilder();

    	// Sort allocated blocks by address
    	List<SysMemInfo> sortedBlockList = Collections.list(Collections.enumeration(blockList.values()));
    	Collections.sort(sortedBlockList);

    	for (SysMemInfo sysMemInfo : sortedBlockList) {
    		if (result.length() > 0) {
    			result.append("\n");
    		}
    		result.append(sysMemInfo.toString());
    	}

    	return result.toString();
    }

    public void free(SysMemInfo info) {
    	if (info != null) {
    		info.free();
	    	MemoryChunk memoryChunk = new MemoryChunk(info.addr, info.allocatedSize);
	    	freeMemoryChunks.add(memoryChunk);

	    	if (log.isDebugEnabled()) {
	    		log.debug(String.format("free %s", info.toString()));
	    		if (log.isTraceEnabled()) {
	    			log.trace("Free list after free: " + getDebugFreeMem());
					log.trace("Allocated blocks after free:\n" + getDebugAllocatedMem() + "\n");
	    		}
	    	}
    	}
    }

    public int maxFreeMemSize() {
    	int maxFreeMemSize = 0;
    	for (MemoryChunk memoryChunk = freeMemoryChunks.getLowMemoryChunk(); memoryChunk != null; memoryChunk = memoryChunk.next) {
    		if (memoryChunk.size > maxFreeMemSize) {
    			maxFreeMemSize = memoryChunk.size;
    		}
    	}
		return maxFreeMemSize;
    }

    public int totalFreeMemSize() {
        int totalFreeMemSize = 0;
    	for (MemoryChunk memoryChunk = freeMemoryChunks.getLowMemoryChunk(); memoryChunk != null; memoryChunk = memoryChunk.next) {
    		totalFreeMemSize += memoryChunk.size;
    	}

    	return totalFreeMemSize;
    }

    /** @param firmwareVersion : in this format: ABB, where A = major and B = minor, for example 271 */
    public void setFirmwareVersion(int firmwareVersion) {
    	this.firmwareVersion = firmwareVersion;
    }

    // note: we're only looking at user memory, so 0x08800000 - 0x0A000000
    // this is mainly to make it fit on one console line
    public void dumpSysMemInfo() {
        final int MEMORY_SIZE = 0x1800000;
        final int SLOT_COUNT = 64; // 0x60000
        final int SLOT_SIZE = MEMORY_SIZE / SLOT_COUNT; // 0x60000
        boolean[] allocated = new boolean[SLOT_COUNT];
        boolean[] fragmented = new boolean[SLOT_COUNT];
        int allocatedSize = 0;
        int fragmentedSize = 0;

        for (Iterator<SysMemInfo> it = blockList.values().iterator(); it.hasNext();) {
            SysMemInfo info = it.next();
            for (int i = info.addr; i < info.addr + info.size; i += SLOT_SIZE) {
                if (i >= 0x08800000 && i < 0x0A000000) {
                    allocated[(i - 0x08800000) / SLOT_SIZE] = true;
                }
            }
            allocatedSize += info.size;
        }

        for (MemoryChunk memoryChunk = freeMemoryChunks.getLowMemoryChunk(); memoryChunk != null; memoryChunk = memoryChunk.next) {
            for (int i = memoryChunk.addr; i < memoryChunk.addr + memoryChunk.size; i += SLOT_SIZE) {
                if (i >= 0x08800000 && i < 0x0A000000) {
                    fragmented[(i - 0x08800000) / SLOT_SIZE] = true;
                }
            }
            fragmentedSize += memoryChunk.size;
        }

        StringBuilder allocatedDiagram = new StringBuilder();
        allocatedDiagram.append("[");
        for (int i = 0; i < SLOT_COUNT; i++) {
            allocatedDiagram.append(allocated[i] ? "X" : " ");
        }
        allocatedDiagram.append("]");

        StringBuilder fragmentedDiagram = new StringBuilder();
        fragmentedDiagram.append("[");
        for (int i = 0; i < SLOT_COUNT; i++) {
            fragmentedDiagram.append(fragmented[i] ? "X" : " ");
        }
        fragmentedDiagram.append("]");

        DumpDebugState.log("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
        DumpDebugState.log(String.format("Allocated memory:  %08X %d bytes", allocatedSize, allocatedSize));
        DumpDebugState.log(allocatedDiagram.toString());
        DumpDebugState.log(String.format("Fragmented memory: %08X %d bytes", fragmentedSize, fragmentedSize));
        DumpDebugState.log(fragmentedDiagram.toString());

        DumpDebugState.log("Free list: " + getDebugFreeMem());
        DumpDebugState.log("Allocated blocks:\n" + getDebugAllocatedMem() + "\n");
    }

    public int hleKernelPrintf(CpuState cpu, Logger logger, String sceFunctionName) {
		int string_addr = cpu.gpr[4];

		String msg = readStringNZ(string_addr, 256);
        if (log.isDebugEnabled()) {
        	log.debug(String.format("%s(string_addr=0x%08X) '%s'", sceFunctionName, string_addr, msg));
        }

        // Format and print the message to stdout
        if (logger.isInfoEnabled()) {
        	String formattedMsg = msg;
        	try {
        		int[] gpr = cpu.gpr;
            	// For now, use only the 7 register parameters: $a1-$a3, $t0-$t3
            	// Further parameters should be retrieved from the stack.
        		Object[] formatParameters = new Object[] {
        				gpr[5],
        				gpr[6],
        				gpr[7],
        				gpr[8],
        				gpr[9],
        				gpr[10],
        				gpr[11]
        		};

        		// Translate the C-like format string to a Java format string:
        		// - %u or %i -> %d
        		// - %p -> %08X
        		String javaMsg = msg;
        		javaMsg = javaMsg.replaceAll("\\%[ui]", "%d");
        		javaMsg = javaMsg.replaceAll("\\%p", "%08X");

        		// Support basic string output "%s"
        		// Assume %s is always the first parameter...
        		if (javaMsg.contains("%s")) {
        			formatParameters[0] = Utilities.readStringZ(gpr[5]);
        		}

        		// String.format: If there are more arguments than format specifiers, the extra arguments are ignored.
        		formattedMsg = String.format(javaMsg, formatParameters[0], formatParameters[1], formatParameters[2], formatParameters[3], formatParameters[4], formatParameters[5], formatParameters[6]);
        	} catch (Exception e) {
        		// Ignore formatting exception
        	}
        	logger.info(formattedMsg);
        }

        return 0;
    }

    @HLEFunction(nid = 0xA291F107, version = 150)
    public int sceKernelMaxFreeMemSize() {
		int maxFreeMemSize = maxFreeMemSize();

        // Some games expect size to be rounded down in 16 bytes block
        maxFreeMemSize &= ~15;

    	if (log.isDebugEnabled()) {
    		log.debug(String.format("sceKernelMaxFreeMemSize %d(hex=0x%1$X)", maxFreeMemSize));
    	}

    	return maxFreeMemSize;
	}

	@HLEFunction(nid = 0xF919F628, version = 150)
	public int sceKernelTotalFreeMemSize() {
		int totalFreeMemSize = totalFreeMemSize();
    	if (log.isDebugEnabled()) {
    		log.debug(String.format("sceKernelTotalFreeMemSize %d(hex=0x%1$X)", totalFreeMemSize));
    	}

    	return totalFreeMemSize;
	}

	@HLEFunction(nid = 0x237DBD4F, version = 150)
	public int sceKernelAllocPartitionMemory(int partitionid, String name, int type, int size, int addr) {
        addr &= Memory.addressMask;
        if (log.isDebugEnabled()) {
	        log.debug(String.format("sceKernelAllocPartitionMemory(partition=%d, name='%s', type=%s, size=0x%X, addr=0x%08X", partitionid, name, getTypeName(type), size, addr));
        }

        if (type < PSP_SMEM_Low || type > PSP_SMEM_HighAligned) {
            return SceKernelErrors.ERROR_KERNEL_ILLEGAL_MEMBLOCK_ALLOC_TYPE;
        }

        SysMemInfo info = malloc(partitionid, name, type, size, addr);
        if (info == null) {
        	return SceKernelErrors.ERROR_KERNEL_FAILED_ALLOC_MEMBLOCK;
        }

        return info.uid;
	}

	@HLEFunction(nid = 0xB6D61D02, version = 150)
	public int sceKernelFreePartitionMemory(int uid) {
		SceUidManager.checkUidPurpose(uid, "SysMem", true);

		SysMemInfo info = blockList.remove(uid);
        if (info == null) {
            log.warn("sceKernelFreePartitionMemory unknown SceUID=" + Integer.toHexString(uid));
            return SceKernelErrors.ERROR_KERNEL_ILLEGAL_CHUNK_ID;
        }

        if (log.isDebugEnabled()) {
    		log.debug("sceKernelFreePartitionMemory SceUID=" + Integer.toHexString(info.uid) + " name:'" + info.name + "'");
    	}

        free(info);

        return 0;
	}

	@HLEFunction(nid = 0x9D9A5BA1, version = 150)
	public int sceKernelGetBlockHeadAddr(int uid) {
		SceUidManager.checkUidPurpose(uid, "SysMem", true);

		SysMemInfo info = blockList.get(uid);
        if (info == null) {
            log.warn("sceKernelGetBlockHeadAddr unknown SceUID=" + Integer.toHexString(uid));
            return SceKernelErrors.ERROR_KERNEL_ILLEGAL_CHUNK_ID;
        }

        if (log.isDebugEnabled()) {
    		log.debug("sceKernelGetBlockHeadAddr SceUID=" + Integer.toHexString(info.uid) + " name:'" + info.name + "' headAddr:" + Integer.toHexString(info.addr));
    	}

        return info.addr;
	}

	@HLEFunction(nid = 0x13A5ABEF, version = 150)
	public int sceKernelPrintf(CpuState cpu) {
		return hleKernelPrintf(cpu, stdout, "sceKernelPrintf");
	}

	@HLEFunction(nid = 0x3FC9AE6A, version = 150)
	public int sceKernelDevkitVersion() {
		int major = firmwareVersion / 100;
        int minor = (firmwareVersion / 10) % 10;
        int revision = firmwareVersion % 10;
        int devkitVersion = (major << 24) | (minor << 16) | (revision << 8) | 0x10;
        if (log.isDebugEnabled()) {
        	log.debug(String.format("sceKernelDevkitVersion return:0x%08X", devkitVersion));
        }

        return devkitVersion;
	}

	@HLEFunction(nid = 0xD8DE5C1E, version = 150)
	public int SysMemUserForUser_D8DE5C1E() {
		if (log.isDebugEnabled()) {
			log.debug("SysMemUserForUser_D8DE5C1E");
		}

		// Seems to always return 0...
		return 0;
	}
}