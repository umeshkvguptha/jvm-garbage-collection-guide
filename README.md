# JVM & Garbage Collection Guide

*Last updated: August 2026 — verified against Java 25 LTS (current LTS, released Sept 16, 2025) and Java 26 (latest non-LTS, March 2026).*

## **Overview**

The **Java Virtual Machine (JVM)** is what lets Java bytecode run on any platform. It provides runtime services such as memory management, security, and garbage collection (GC).

This guide covers **JVM architecture**, the **GC process**, **all current GC algorithms** , **JVM tuning**, and **monitoring tools**.

---

## **1. JVM Architecture**

```
+----------------------------+
|            JVM             |
+----------------------------+
|   Class Loader Subsystem   |
|    - Loading               |
|    - Linking (Verify/      |
|      Prepare/Resolve)      |
|    - Initialization        |
+----------------------------+
|   Runtime Data Areas        |
|    - Method Area (part of  |
|      Metaspace since J8)   |
|    - Heap                  |
|    - Stack (per thread)    |
|    - PC Register (per      |
|      thread)                |
|    - Native Method Stack   |
+----------------------------+
|   Execution Engine          |
|    - Interpreter            |
|    - JIT Compiler (C1/C2,  |
|      tiered compilation)   |
|    - Garbage Collector     |
+----------------------------+
|   Native Interface (JNI/FFM)|
+----------------------------+
```

### **1.1 Class Loader Subsystem**

- Loads `.class` files into memory using the **Bootstrap → Platform → Application** classloader delegation model.
- **Linking** = Verify (bytecode is valid/safe) → Prepare (allocate static fields, default values) → Resolve (symbolic references → direct references).
- **Initialization** runs static initializers and static variable assignments, in the order they appear.
- Interview note: know the difference between `ClassNotFoundException` (class not found by classloader) vs `NoClassDefFoundError` (class was present at compile time but missing/failed to init at runtime).

### **1.2 Runtime Data Areas**

- **Method Area / Metaspace**: class metadata, constant pool, static variables. Since **Java 8**, this lives in **Metaspace**, allocated from **native (off-heap) memory**, not the JVM heap — it grows automatically unless you cap it with `-XX:MaxMetaspaceSize`.
- **Heap**: shared across all threads; stores all objects and arrays. Divided into generations by most (not all) collectors — see §2.3.
- **Stack**: one per thread, holds frames with local variables, operand stack, and method-call bookkeeping. `StackOverflowError` originates here.
- **PC (Program Counter) Register**: one per thread, tracks the currently executing bytecode instruction.
- **Native Method Stack**: supports native (JNI/FFM) method calls.
- **Runtime Constant Pool**: per-class, holds numeric literals, string literals, and symbolic references — technically part of Metaspace since Java 8 (interned Strings themselves live on the heap).

### **1.3 Execution Engine**

- **Interpreter**: executes bytecode line by line — fast startup, slower steady-state execution.
- **JIT Compiler**: HotSpot uses **tiered compilation** — C1 (client compiler, fast warm-up, light optimization) compiles "hot" methods first, then **C2** (server compiler) recompiles the hottest of those with heavy optimization (inlining, escape analysis, loop unrolling). As of Java 25, **AOT method profiling (JEP 515)** lets the JVM reuse profiling data from a prior training run so C2 can compile well-optimized code sooner on startup.
- **Garbage Collector**: reclaims memory from unreachable objects (see §2).

### **1.4 Native Interface**

- **JNI** (Java Native Interface) — traditional way to call C/C++ from Java.
- **FFM API** (Foreign Function & Memory API, finalized as **JEP 454** in **Java 22**) — the modern, safer replacement for JNI and `sun.misc.Unsafe` for calling native code and working with off-heap memory. Worth mentioning in interviews as the direction the platform is moving.

---

## **2. Garbage Collection (GC) in Java**

### **2.1 What is Garbage Collection?**

GC is the JVM's automatic reclamation of heap memory occupied by objects that are no longer *reachable* from a set of **GC roots** (local variables on stacks, active thread objects, static fields, JNI references, etc.). "Reachable" — not "unused" — is the key word; an object with no more logical use but still referenced (e.g., left in a `static` collection) will **not** be collected. That's the classic Java "memory leak" interview trap.

**Key goals:**
- Automatic memory reclamation (no manual `free()`).
- Avoid dangling pointers and use-after-free bugs by construction.
- Balance **throughput**, **latency (pause times)**, and **memory footprint** — you can optimize for at most two of these at once, which is the crux of collector selection.

### **2.2 How GC Works**

Most collectors follow some variation of:

1. **Mark**: traverse the object graph from GC roots, marking every reachable object as live.
2. **Sweep**: reclaim memory occupied by unmarked (unreachable) objects.
3. **Compact**: move surviving objects together to eliminate fragmentation and keep allocation cheap (simple pointer-bump into contiguous free space).

Concurrent, region-based collectors (G1, ZGC, Shenandoah) also add:
- **Remembered sets / card tables**: per-region metadata tracking cross-region references, so a GC of one region doesn't require scanning the *entire* heap for roots.
- **Barriers**: small pieces of code inserted at reference reads/writes (write barriers in G1, load barriers in ZGC/Shenandoah) that keep that tracking metadata correct while the application keeps running concurrently with the collector.

### **2.3 Generational Hypothesis & Heap Structure**

Most collectors (G1, Parallel, Serial, and now generational ZGC/Shenandoah) are built on the **weak generational hypothesis**: most objects die young. So the heap is split:

- **Young Generation** — new objects. Sub-divided into:
  - **Eden**: where new objects are allocated (often via a per-thread **TLAB — Thread-Local Allocation Buffer** — to avoid allocation contention between threads).
  - **Survivor spaces (S0/S1)**: objects that survive one or more young GCs get copied here, and their **age** (survival count) is tracked; past a tenuring threshold they get promoted.
- **Old (Tenured) Generation** — long-lived, promoted objects. Collected less often but more expensively.
- **Metaspace** — class metadata, native memory, not part of the heap (replaced PermGen in Java 8).

**Minor GC** = young-gen only, frequent, cheap, usually a brief stop-the-world (STW) pause.
**Major/Full GC** = old-gen (and often the whole heap), rarer, much more expensive — this is the one that shows up as a latency spike in production and the one interviewers care about you being able to diagnose.

---

## **3. Garbage Collectors in Java — Current State (Java 25)**

⚠️ **The single biggest thing that changed since this guide was first written**: **CMS (Concurrent Mark-Sweep) was deprecated in Java 9 and fully removed in Java 14 (JEP 363).** It no longer exists in any supported JDK. If it comes up in an interview, the correct answer is "it's been removed since Java 14 — G1/Shenandoah/ZGC replaced it," not "here's the flag to enable it."

| Collector | Status as of Java 25 | Default? |
|---|---|---|
| Serial GC | Available | No |
| Parallel GC | Available | No (was default pre-Java 9) |
| ~~CMS~~ | **Removed in Java 14** | — |
| **G1 GC** | Available, actively developed | **Yes**, default since Java 9 |
| Epsilon GC | Available (no-op) | No |
| **ZGC** | Available, **generational-only since Java 24/25** | No, but production-grade |
| **Shenandoah** | Available, **generational mode is now a product feature (Java 25, JEP 521)** | No, and not shipped in Oracle's own JDK builds |

### **3.1 Serial GC**

- Single-threaded, stop-the-world for both young and old gen.
- Good for small heaps / single-CPU environments (containers with 1 vCPU, small CLI tools).
- Enable: `-XX:+UseSerialGC`

### **3.2 Parallel GC**

- Multi-threaded STW collector, optimized for **throughput** over latency.
- Was the JVM's default up until Java 8; still a fine choice for **batch jobs** where pauses don't matter but total throughput does.
- Enable: `-XX:+UseParallelGC`

### **3.3 ~~CMS~~ (Removed)**

- Historically the low-latency collector before G1 matured. **Deprecated in Java 9 (JEP 291), removed in Java 14 (JEP 363).** Do not use or recommend it — G1 or Shenandoah/ZGC are its replacements.

### **3.4 G1 (Garbage-First) GC**

- **Default collector since Java 9** and still the most widely deployed collector in production Java today.
- Region-based heap (not fixed contiguous young/old spaces); prioritizes collecting the regions with the most garbage first (hence "Garbage-First").
- Aims for a **pause-time goal** you set (`-XX:MaxGCPauseMillis`, default 200ms), not a hard guarantee.
- Best general-purpose choice for heaps roughly **1GB–32GB+**; still typically the **most memory-efficient** of the modern collectors (lowest RSS/native memory overhead), which matters a lot in containerized/cloud deployments.
- Enable: `-XX:+UseG1GC`

### **3.5 Epsilon GC** 

- A **no-op garbage collector** (JEP 318, Java 11): it allocates memory but **never reclaims it**. When the heap fills up, the JVM exits with an OOM.
- Real use cases: performance testing (measure allocation overhead with zero GC interference), ultra-short-lived jobs/serverless functions that finish before the heap could fill up, and memory-pressure testing.
- Enable: `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC`
- This is a common "trick question" in senior interviews — knowing it exists and *why* you'd deliberately choose a GC that doesn't collect anything is a good signal.

### **3.6 Z Garbage Collector (ZGC)**

- Ultra-low-latency concurrent collector, sub-millisecond pause targets, regardless of heap size (validated up to multi-terabyte heaps).
- Uses **colored pointers** + **load barriers** to do marking, relocation, and remapping almost entirely concurrently with the application.
- **Timeline correction**: introduced experimentally in Java 11, production-ready in Java 15. **Generational ZGC** (separate young/old regions, like G1) arrived opt-in in **Java 21 (JEP 439)**. As of **Java 23 (JEP 474)** generational became the **default** mode, and as of **Java 24/25 the non-generational mode was removed entirely** — there is now only one ZGC, and it's generational.
- Enable: `-XX:+UseZGC` (that's it on Java 25 — no extra flag needed for generational mode anymore).

### **3.7 Shenandoah GC**

- Concurrent, region-based, low-pause collector (developed originally by Red Hat), pause times largely independent of heap size.
- **Update**: **generational Shenandoah** graduated from experimental to a **production feature in Java 25 (JEP 521)** — separate young/old generations, no longer needs `-XX:+UnlockExperimentalVMOptions` to use. Still defaults to single-generation mode; opt into generational with `-XX:ShenandoahGCMode=generational`.
- **Important and often-missed fact**: Shenandoah is **not included in Oracle's own JDK builds** (Oracle ships G1, Parallel, Serial, Epsilon, ZGC only). It *is* available in OpenJDK builds from other vendors — Red Hat/IBM, Eclipse Temurin/Adoptium, Azul Zulu, Amazon Corretto, etc. This trips people up: "which GC should I use" answers can depend on which JDK *distribution* you're actually running.
- Enable: `-XX:+UseShenandoahGC`

---

## **4. JVM Performance Tuning and GC Monitoring**

### **4.1 JVM GC Tuning Flags**

- `-Xms<size>` / `-Xmx<size>`: initial / max heap size. Setting them equal avoids heap-resizing pauses in latency-sensitive services.
- `-XX:MaxMetaspaceSize=<size>`: cap Metaspace (uncapped by default — a classloader leak can otherwise silently eat native memory).
- `-XX:MaxGCPauseMillis=<n>`: soft pause-time goal (G1/Shenandoah).
- `-XX:+UseStringDeduplication`: (G1/Shenandoah) merges duplicate `char[]`/`byte[]` backing arrays of equal `String`s to cut memory.
- `-Xlog:gc*` : **current, correct way to get GC logs**, using the **Unified JVM Logging** framework (JEP 158, since Java 9). The old `-XX:+PrintGCDetails -XX:+PrintGCDateStamps` flags from the original version of this guide **no longer work** — they were removed. Example: `-Xlog:gc*:file=gc.log:time,uptime,level,tags`
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<path>`: auto-dump the heap on OOM — the single highest-value flag for diagnosing production memory leaks after the fact.
- `-XX:+ExitOnOutOfMemoryError` / `-XX:+CrashOnOutOfMemoryError`: fail fast instead of limping along in a broken state (useful in containers where you want an orchestrator to just restart the pod).

### **4.2 GC Monitoring & Diagnostic Tools**

- **JDK Flight Recorder (JFR)**: built into the JVM (`-XX:StartFlightRecording=...`), extremely low overhead, the modern default for production profiling/GC analysis. As of **Java 25**, JFR gained **per-method timing/tracing without manual config** and switched to **cooperative, JVM-internal sampling** instead of OS signal-based sampling, improving accuracy at production-safe overhead.
- **JDK Mission Control (JMC)**: the GUI for analyzing JFR recordings.
- **`jcmd`**: the modern Swiss-army-knife CLI (`jcmd <pid> GC.heap_info`, `jcmd <pid> VM.native_memory`, `jcmd <pid> GC.run`, etc.) — largely superseded jstat/jmap for day-to-day use.
- **VisualVM**: still useful, no longer bundled with the JDK (separate download from visualvm.github.io) — good for heap dump/live monitoring with a GUI.
- **Eclipse Memory Analyzer (MAT)**: the standard tool for post-mortem heap dump analysis (leak suspects report, dominator tree).
- **`async-profiler`**: widely used third-party low-overhead sampling profiler, complements JFR.

---

## **5. Quick Interview-Ready Summary**

- **Default GC today**: G1, since Java 9, still default in Java 25.
- **Want lowest possible pause times & don't mind ~5–10% more CPU/memory**: Generational ZGC (Java 24/25+), the only ZGC mode remaining.
- **Batch/throughput job, pauses don't matter**: Parallel GC.
- **Single-CPU/tiny footprint**: Serial GC.
- **Testing allocation overhead / a job that will finish before GC ever needs to run**: Epsilon.
- **CMS**: gone since Java 14 — don't recommend it.
- **Shenandoah**: great low-pause option, but only if you're not locked into Oracle's own JDK build.

See [`G1GCvsZGCvsShenandoahGC.MD`](./G1GCvsZGCvsShenandoahGC.MD) for a deep-dive comparison of the three modern low-pause collectors, and [`InterviewQuestions.MD`](./InterviewQuestions.MD) for a worked Q&A set.

---

## **Conclusion**

Understanding JVM memory structure and GC behavior is core interview and production-debugging territory. The landscape keeps shifting — CMS died, Shenandoah and ZGC both went generational, unified logging replaced the old `-XX:+PrintGC*` flags — so it's worth re-verifying flags/defaults against the JDK version you're actually targeting rather than trusting memorized advice.
