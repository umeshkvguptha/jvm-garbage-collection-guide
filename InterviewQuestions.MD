# JVM & Garbage Collection — Interview Questions
---

## Fundamentals

**1. What is Garbage Collection and why does Java use it?**
Automatic reclamation of heap memory occupied by objects no longer reachable from GC roots. It removes manual `free()`/`delete()` bugs (use-after-free, double-free, most memory leaks) at the cost of some throughput/latency overhead and less deterministic memory behavior than manual management.

**2. What are GC Roots?**
The starting points for reachability analysis: local variables and parameters on live thread stacks, active/running threads themselves, static fields of loaded classes, JNI references, and objects referenced from those (transitively). An object is "garbage" the moment it's no longer reachable from any GC root — not when the programmer is "done" with it.

**3. Can a Java program still have a memory leak with automatic GC?**
Yes — the classic trap. If a reachable reference to an object outlives its useful life (a `static` `List` you keep appending to, an un-removed listener/callback, an `ThreadLocal` never cleared, a cache with no eviction policy), GC correctly considers it live and never reclaims it. GC prevents *dangling-pointer* bugs, not *logical* leaks.

**4. Difference between Minor GC, Major GC, and Full GC.**
- **Minor/Young GC**: collects only the young generation (Eden + Survivor). Frequent, cheap, short STW pause.
- **Major GC**: collects the old generation (terminology varies by collector — with G1 this is a "mixed GC," collecting some old regions alongside young).
- **Full GC**: collects the *entire* heap (and often Metaspace), typically single-threaded and fully STW. In a healthy, well-tuned modern JVM (G1/ZGC/Shenandoah), Full GCs should be rare — frequent Full GCs are a red flag (undersized heap, promotion failure, or a genuine leak).

**5. What's the difference between the stack and the heap in the JVM?**
Stack is per-thread, holds local variables/frames, LIFO, automatically reclaimed on method return — never GC'd. Heap is shared across all threads, holds all objects, and is where GC operates.

**6. What replaced PermGen, and why?**
**Metaspace**, since Java 8 (JEP 122). PermGen had a fixed max size by default and was a common source of `OutOfMemoryError: PermGen space`, especially with app servers doing lots of hot class (re)loading. Metaspace lives in native memory and grows automatically (though you can and should cap it with `-XX:MaxMetaspaceSize` in production to avoid a classloader leak silently eating host memory).

---

## Garbage Collector Choice & Behavior

**7. What's the default GC in modern Java, and has that ever changed?**
G1 has been the default since **Java 9** (replacing Parallel GC, which was default through Java 8), and is still the default in Java 25. It has not changed defaults since Java 9.

**8. What happened to CMS?**
Deprecated in Java 9, **removed entirely in Java 14 (JEP 363)**. G1 (and later Shenandoah/ZGC) replaced it as the low-pause option. If you're on any JDK ≥14, `-XX:+UseConcMarkSweepGC` simply won't start the JVM.

**9. How does G1 decide what to collect?**
It tracks per-region liveness during concurrent marking and prioritizes evacuating the regions with the most garbage relative to the cost of collecting them — hence "Garbage-First." This lets it hit a configurable pause-time goal (`-XX:MaxGCPauseMillis`) probabilistically rather than following a fixed generational schedule.

**10. How do ZGC and Shenandoah achieve sub-millisecond pauses when G1 can't?**
Both do the expensive work — marking and, critically, *object relocation/compaction* — **concurrently** with the application, using pointer-tagging tricks (ZGC's colored pointers + load barriers, Shenandoah's Brooks forwarding pointers + read/write barriers) to keep application threads safely reading correct data even while objects are being moved underneath them. G1 also marks concurrently, but its evacuation/compaction is done during STW pauses — that's the structural difference.

**11. Are ZGC and Shenandoah generational now? Wasn't the whole point that they weren't?**
This is a genuinely current (2026) gotcha question. Both *started* as flat, non-generational collectors. That changed:
- **ZGC**: generational mode opt-in in Java 21, became the default in Java 23, and the non-generational mode was **removed** by Java 24/25 — there's only one ZGC now, and it's generational.
- **Shenandoah**: generational mode became a **production feature in Java 25**, but it's still opt-in (`-XX:ShenandoahGCMode=generational`); single-generation remains the default.
Going generational improved both collectors' throughput (roughly a 10% gain reported for ZGC) by exploiting the weak generational hypothesis, without giving up their low-pause guarantees.

**12. If Shenandoah is a fully open-source OpenJDK project, why might a team not be able to use it?**
Because **Oracle's own JDK build doesn't include it** — Oracle ships Serial, Parallel, G1, ZGC, and Epsilon, but not Shenandoah. Teams on Oracle JDK need to switch to another OpenJDK vendor build (Red Hat, Eclipse Temurin/Adoptium, Azul Zulu, Amazon Corretto, etc.) to get it. This is a licensing/distribution fact, not a technical limitation of the collector itself.

**13. What is Epsilon GC and why would anyone want a garbage collector that doesn't collect garbage?**
A **no-op collector** (JEP 318, Java 11): allocates but never reclaims; the JVM crashes with OOM once the heap fills. Useful for (a) measuring pure allocation overhead in performance testing with zero GC noise, (b) very short-lived jobs/serverless invocations that will finish before the heap could realistically fill, and (c) deliberately testing how your app behaves under memory pressure.

**14. When would you pick Parallel GC over G1 today?**
When you're running a **batch/offline job** — an ETL pipeline, a nightly report, a data-processing job — where total throughput matters and pause times don't, since nothing is waiting on a response. Parallel GC optimizes purely for throughput at the cost of longer, less frequent pauses.

---

## Tuning & Diagnostics

**15. How do you enable GC logging today, and why might the flags you remember not work?**
Use **Unified JVM Logging** (`-Xlog:gc*`, since JEP 158 / Java 9): e.g. `-Xlog:gc*:file=gc.log:time,uptime,level,tags`. The older `-XX:+PrintGCDetails -XX:+PrintGCDateStamps` style flags were removed — if you're pasting flags from an old blog post or Stack Overflow answer, verify they still exist on your target JDK version.

**16. Your production service is getting killed by OOM. What's your first move?**
Add `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<path>` so the *next* OOM captures a heap dump automatically, then analyze it with Eclipse Memory Analyzer (MAT) — start with its "leak suspects" report and dominator tree to find what's retaining the most memory and via what reference chain. If it's already happened once without that flag, `jcmd <pid> GC.heap_dump <path>` can capture a live dump for comparison before the next crash.

**17. What's the difference between `-Xms`/`-Xmx` and why set them equal in a service?**
`-Xms` is the initial heap, `-Xmx` the max. Setting them equal avoids the JVM spending time resizing the heap at runtime (each resize is itself a pause-inducing operation) — common practice for latency-sensitive, long-running services with a well-understood memory footprint.

**18. How much heap headroom should you give a concurrent collector like ZGC or Shenandoah?**
More than you'd give G1 — recent guidance suggests **at least ~25% headroom** above your live-set size, since the collector needs room to keep allocating into while it concurrently marks/relocates. Under-provisioning a concurrent collector risks allocation stalls even though its *pause times* look great.

**19. What tools would you reach for to profile GC behavior in production today, and why not `jstat`/`jmap` like a few years ago?**
**JFR (JDK Flight Recorder)** is the modern default — built into the JVM, very low overhead, and as of Java 25 uses cooperative JVM-internal sampling (more accurate, less overhead than the old OS-signal-based sampling). Analyze recordings with **JDK Mission Control (JMC)**. `jcmd` has largely replaced piecemeal tools like `jstat`/`jmap` for ad-hoc CLI diagnostics (`jcmd <pid> GC.heap_info`, `VM.native_memory`, etc.). VisualVM still works but is no longer bundled with the JDK.

**20. What's a TLAB and why does it matter?**
A **Thread-Local Allocation Buffer** — each thread gets its own small chunk of Eden to allocate into without synchronization, avoiding lock contention on the shared heap for the (very common) fast path of allocating a new small object. Escape analysis in C2 can sometimes eliminate the heap allocation entirely (**scalar replacement**) if the JIT proves an object never escapes a method.

---

## System-Design-Level / Senior Questions

**21. How would you choose a GC for a latency-sensitive payments API vs. a nightly batch ETL job?**
Payments API: start with G1 tuned for a tight pause goal; if tail latency (p99/p999) from GC is still unacceptable and you can afford the extra CPU/memory, move to generational ZGC. Batch ETL: Parallel GC — no one is waiting on an individual response, so maximize throughput and don't pay any concurrent-collector CPU tax.

**22. Why might switching from G1 to ZGC make your memory usage *look* worse even if it's "better" at latency?**
Concurrent collectors trade memory/CPU headroom for pause-time consistency — expect higher RSS and ~5–10% more CPU overhead than G1 for comparable workloads. That's not a bug, it's the mechanism (extra headroom to allocate into while concurrently relocating, plus barrier overhead on every reference access). Right-sizing container memory limits accordingly is part of the migration, not an afterthought.

**23. What is "Stop-the-World" and why can't we just eliminate it entirely?**
STW means all application threads are paused so the collector can safely mutate object graph structure/roots without the app changing them mid-operation. Even the "concurrent" collectors (ZGC, Shenandoah) still need brief STW pauses for root scanning — you can shrink STW to near-zero and make it heap-size-independent, but a small synchronization point is still needed to get a consistent snapshot of the roots to start from.

**24. What are weak, soft, and phantom references, and when would you use each?**
- **`WeakReference`**: collected as soon as the only references are weak — good for building caches/maps where you never want to be the reason an object survives (see `WeakHashMap`).
- **`SoftReference`**: collected only under memory pressure, before an OOM would otherwise occur — good for memory-sensitive caches that should degrade gracefully rather than leak.
- **`PhantomReference`**: never gives you the referent back at all (`get()` always returns `null`); used with a `ReferenceQueue` for post-mortem cleanup actions after an object is finalized/collected — the modern, safer alternative to `finalize()`.

**25. What's the difference between the write barriers G1 uses and the load barriers ZGC uses?**
G1's **write barrier** fires when a reference *field is written*, recording cross-region references into a remembered set so a young GC doesn't need to scan the whole old generation for roots pointing into young gen. ZGC's **load barrier** fires when a reference is *read*, checking the colored pointer's metadata bits to detect (and self-heal) stale references left over from an in-progress concurrent relocation. The distinction — barrier on write vs. barrier on read — is a good way to demonstrate you understand *why* each collector's STW footprint differs.

**26. If someone tells you "just use G1, it's always the best," how do you push back with something more precise?**
G1 is the best *default* and typically the most memory-efficient of the modern collectors, which makes it right for most general-purpose services. But it's not universally best: it doesn't match ZGC/Shenandoah's pause-time consistency at large heap sizes, and it isn't the throughput leader that Parallel GC is for batch work. "Best" depends on which two of throughput/latency/footprint you're optimizing for — pick the collector that matches your actual constraint, not a rule of thumb.

---

## Further reading
- [Oracle GC Tuning Guide (Java 25)](https://docs.oracle.com/en/java/javase/25/gctuning/)
- [OpenJDK ZGC wiki](https://wiki.openjdk.org/spaces/zgc)
- [JEP 474 — ZGC: Generational Mode by Default](https://openjdk.org/jeps/474)
- [JEP 521 — Generational Shenandoah](https://openjdk.org/jeps/521)
- [JEP 363 — Remove the CMS Garbage Collector](https://openjdk.org/jeps/363)
