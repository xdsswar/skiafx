#!/usr/bin/env python3
"""Minimal minidump exception + stack-scan parser for skia-fx-webview crashes.

Usage: python parse_minidump.py <dump.dmp>
Symbolizes the exception address and heuristically-scanned stack return
addresses (8-byte values that land inside the main DLL range) against the DLL
PDB via llvm-symbolizer --relative-address.
"""
import struct, sys, subprocess, os

DUMP = sys.argv[1]
SYMBOLIZER = r".chromium/chromium/src/third_party/llvm-build/Release+Asserts/bin/llvm-symbolizer.exe"
PDB_DLL = r".chromium/chromium/src/out/skiafxweb/skia-fx-webview.dll"  # symbolizer wants the binary; pdb sits beside it

data = open(DUMP, "rb").read()
assert data[:4] == b"MDMP", "not a minidump"
nstreams, dirrva = struct.unpack_from("<II", data, 8)

streams = {}
for i in range(nstreams):
    st, ds, rva = struct.unpack_from("<III", data, dirrva + i*12)
    streams[st] = (ds, rva)

# --- Module list (type 4) ---
modules = []
if 4 in streams:
    ds, rva = streams[4]
    (nmods,) = struct.unpack_from("<I", data, rva)
    off = rva + 4
    for i in range(nmods):
        base, = struct.unpack_from("<Q", data, off)
        size, = struct.unpack_from("<I", data, off+8)
        namerva, = struct.unpack_from("<I", data, off+20)
        nlen, = struct.unpack_from("<I", data, namerva)
        name = data[namerva+4: namerva+4+nlen].decode("utf-16-le", "replace")
        modules.append((base, size, name))
        off += 108

def mod_for(addr):
    for base, size, name in modules:
        if base <= addr < base+size:
            return base, size, name
    return None

MAIN = None
for base, size, name in modules:
    if "skia-fx-webview.dll" in name.lower():
        MAIN = (base, size, name)
print("MAIN DLL:", MAIN)

# --- Exception stream (type 6) ---
exc_addr = None
ctx_rva = None
if 6 in streams:
    ds, rva = streams[6]
    tid, = struct.unpack_from("<I", data, rva)
    # MINIDUMP_EXCEPTION starts at rva+8
    ecode, eflags, erec, eaddr = struct.unpack_from("<IIQQ", data, rva+8)
    nparam, = struct.unpack_from("<I", data, rva+8+24)
    # ThreadContext location: after exception record (rva+8 + 24 + 8 + 15*8 = rva+8+152)
    cds, crva = struct.unpack_from("<II", data, rva+8+152)
    exc_addr = eaddr
    ctx_rva = crva
    print(f"\n=== EXCEPTION ===\ntid={tid} code=0x{ecode:08x} flags=0x{eflags:x} nparam={nparam} addr=0x{eaddr:x}")
    m = mod_for(eaddr)
    print("exc module:", m)
    print("exc params:", [hex(x) for x in struct.unpack_from("<15Q", data, rva+8+32)][:nparam])

def symbolize(rvas):
    if not MAIN: return
    inp = "".join(f"0x{r:x}\n" for r in rvas)
    p = subprocess.run([SYMBOLIZER, "--relative-address", "-e", PDB_DLL],
                       input=inp, capture_output=True, text=True)
    print(p.stdout)
    if p.stderr.strip(): print("STDERR:", p.stderr[:500])

if exc_addr and MAIN and MAIN[0] <= exc_addr < MAIN[0]+MAIN[1]:
    print("\n=== EXC ADDRESS SYMBOL ===")
    symbolize([exc_addr - MAIN[0]])

# --- CONTEXT: RIP @ 0xF8, RSP @ 0x98 (AMD64 CONTEXT) ---
rsp = rip = None
if ctx_rva:
    rip, = struct.unpack_from("<Q", data, ctx_rva+0xF8)
    rsp, = struct.unpack_from("<Q", data, ctx_rva+0x98)
    print(f"\nRIP=0x{rip:x} RSP=0x{rsp:x}")
    m = mod_for(rip)
    if m and "skia-fx-webview" in m[2].lower():
        print("=== RIP SYMBOL ==="); symbolize([rip - m[0]])

# --- Memory64 list (type 9) to find stack bytes ---
mem_ranges = []
if 9 in streams:
    ds, rva = streams[9]
    nranges, = struct.unpack_from("<Q", data, rva)
    base_rva, = struct.unpack_from("<Q", data, rva+8)
    off = rva+16
    cur = base_rva
    for i in range(nranges):
        sa, sz = struct.unpack_from("<QQ", data, off)
        mem_ranges.append((sa, sz, cur))
        cur += sz
        off += 16

# --- MemoryList (type 5) fallback (WER dumps use this) ---
if not mem_ranges and 5 in streams:
    ds, rva = streams[5]
    (nr,) = struct.unpack_from("<I", data, rva)
    off = rva+4
    for i in range(nr):
        sa, dsz, drva = struct.unpack_from("<QII", data, off)
        mem_ranges.append((sa, dsz, drva))
        off += 16

def read_mem(addr, length):
    for sa, sz, frva in mem_ranges:
        if sa <= addr < sa+sz:
            o = frva + (addr-sa)
            return data[o: o+min(length, sz-(addr-sa))]
    return None

if rsp and MAIN:
    stack = read_mem(rsp, 0x4000)
    if stack:
        print("\n=== STACK SCAN (return addrs in MAIN dll) ===")
        seen = set(); rvas = []
        for i in range(0, len(stack)-8, 8):
            v, = struct.unpack_from("<Q", stack, i)
            if MAIN[0] <= v < MAIN[0]+MAIN[1]:
                r = v - MAIN[0]
                if r not in seen:
                    seen.add(r); rvas.append(r)
        symbolize(rvas[:40])
