# G1GC vs ZGC vs Shenandoah GC: Expert-Level Deep Dive

## **Overview**

These are the three modern, actively-developed collectors in the JVM (CMS, the fourth "modern" collector from a decade ago, was removed in Java 14 — see the main README). This guide covers internal mechanisms, object movement, stop-the-world (STW) behavior, tuning, and — updated from the original — **where each collector stands after the generational-mode changes that landed across Java 21–25**.

---

## **1. G1GC (Garbage-First Garbage Collector)**

### **1.1 How G1GC Works**

- **Region-based heap**: heap split into equal-sized regions (default 1–32MB, auto-sized by heap size), each dynamically tagged Eden, Survivor, Old, or Humongous — not fixed contiguous generations.
- **Garbage-first prioritization**: tracks liveness per region and collects the regions with the most reclaimable garbage first, to hit its pause-time goal efficiently.
- **Concurrent marking**: tracks object liveness alongside the running application via a **Snapshot-At-The-Beginning (SATB)** algorithm and write barriers.
- **Incremental, mostly-concurrent compaction** via region evacuation (copying live objects out of a region so the whole region can be reclaimed at once) — this largely avoids the fragmentation that plagued old CMS.

### **1.2 G1GC Heap Structure and Object Movement**

```
+---------------------------------------------------+
|                     Heap                           |
+---------------------------------------------------+
| Region | Region | Region | Region | Region | ...    |
| (Eden) | (Eden) | (Surv) | (Old)  | (Humongous) ... |
+---------------------------------------------------+
```

- **Eden regions**: new allocations.
- **Survivor regions**: objects that outlived one or more young GCs.
- **Old regions**: promoted, long-lived objects.
- **Humongous regions**: objects ≥ 50% of a region size get allocated directly here, bypassing young gen — a known source of fragmentation/pause issues if your app allocates lots of large arrays; worth tuning `-XX:G1HeapRegionSize` upward if so.

### **1.3 G1GC Phases**

- **Young GC (Minor GC)**: STW, copies live Eden/Survivor objects forward, reclaims Eden regions in bulk.
- **Concurrent marking cycle**: runs mostly alongside the app once old-gen occupancy crosses `InitiatingHeapOccupancyPercent` (default 45%).
- **Mixed GC**: collects young regions *plus* a subset of old regions identified as garbage-rich by marking — this is G1's mechanism for reclaiming old-gen without a Full GC.
- **Full GC (fallback)**: single-threaded, fully STW, happens when G1 can't keep up (evacuation failure, allocation outpacing collection). This is the pathological case you tune to avoid — if you're seeing Full GCs in G1 logs, that's a sign of undersized heap or a genuine leak, not normal operation.

### **1.4 Does G1GC Cause STW Events?**

Yes — Young GC, the initial-mark pause, remark, and (rarely) Full GC are all STW. In healthy G1 operation these are on the order of tens of milliseconds, not the multi-second pauses old Full GCs used to cause.

### **1.5 Tuning G1GC**

- `-XX:MaxGCPauseMillis=<n>` — soft pause-time goal (default 200ms).
- `-XX:G1HeapRegionSize=<n>` — region size; raise it if you allocate many large/humongous objects.
- `-XX:InitiatingHeapOccupancyPercent=<n>` — when concurrent marking kicks off.
- `-XX:+G1PeriodicGCInterval` — force periodic GCs during idle periods (useful to keep RSS down for bursty services).

### **1.6 When to Use G1GC**

✅ Best default for **general-purpose, balanced throughput + latency** workloads (most web services, microservices, REST APIs).
✅ Best **memory efficiency** among the three — as of Java 25 benchmarking, G1 still typically has the lowest RSS footprint, which matters in memory-constrained containers.
✅ Good from small heaps up through very large (multi-hundred-GB) heaps.

❌ Not the right pick if you need **consistent sub-10ms** pauses regardless of heap size — that's ZGC/Shenandoah's job.

---

## **2. ZGC (Z Garbage Collector)**

### **2.1 How ZGC Works**

- **Colored pointers**: metadata bits embedded directly in 64-bit object references (not a separate side table), letting the collector track an object's state (marked, remapped, finalizable) without touching the object itself.
- **Load barriers**: a check inserted at every reference *load*; if it detects a stale/relocated reference it self-heals the pointer on the fly. (G1 uses *write* barriers instead — a key architectural difference worth naming in interviews.)
- **Concurrent everything**: marking, relocation, and reference remapping all happen while application threads keep running; only tiny root-scanning pauses are STW.
- **Compacting**: fully compacts the heap concurrently, so no long-term fragmentation.

### **2.2 What changed — Generational ZGC**

This is the single biggest fact update needed here. The original write-up described ZGC as a flat, non-generational collector. That's no longer accurate:

- **Java 21 (JEP 439)**: Generational ZGC introduced, **opt-in** (`-XX:+UseZGC -XX:+ZGenerational`).
- **Java 23 (JEP 474)**: generational mode became the **default**; non-generational deprecated.
- **Java 24**: the `ZGenerational` flag was **removed** entirely.
- **Java 25 (current LTS)**: **only generational ZGC exists.** `-XX:+UseZGC` is generational, full stop. Java 25 also replaced ZGC's internal "Page Cache" with a "Mapped Cache," reducing fragmentation and fixing artificially-inflated RSS reporting from the old multi-mapped memory scheme.

Why it matters: generational ZGC applies the same young/old split as G1 to exploit the weak-generational hypothesis, which **improved ZGC throughput by roughly 10%** over the old flat design and eliminated allocation stalls under high allocation-rate workloads — closing much of the throughput gap that used to make people avoid ZGC for anything but pure-latency use cases.

### **2.3 ZGC Heap Structure and Object Movement (Generational, Java 25)**

```
+------------------------------------------------------+
|                        Heap                            |
+------------------------------------------------------+
| Young Generation (ZPages)   | Old Generation (ZPages)  |
+------------------------------------------------------+
```

- Heap is divided into **ZPages** (variable-sized regions: small/medium/large) rather than fixed generations like G1's uniform regions.
- **Young collections** run frequently and cheaply, reclaiming short-lived garbage.
- **Old collections** run less often, reclaiming long-lived garbage via concurrent marking + relocation.

### **2.4 Does ZGC Cause STW Events?**

✅ Yes, but they're tiny and roughly constant regardless of heap size: `Pause Mark Start` and `Pause Mark End` — typically sub-millisecond to low-single-digit-millisecond, even on multi-terabyte heaps. This is ZGC's headline feature.

### **2.5 Tuning ZGC (Java 25)**

- `-XX:+UseZGC` — that's the whole story for enabling it; no generational flag needed anymore.
- `-Xmx<size>` — the main real tuning knob; give ZGC generous headroom (rule of thumb from recent benchmarking: **at least ~25% memory headroom** above your live-set) since it's a concurrent collector doing work while allocation continues.
- `-XX:SoftMaxHeapSize=<size>` — soft cap; ZGC tries to stay under it but can exceed it up to `-Xmx` if needed.
- `-XX:+ExplicitGCInvokesConcurrent` — makes `System.gc()` trigger a concurrent (not STW) cycle.

### **2.6 When to Use ZGC**

✅ **Ultra-low, consistent latency** requirements (trading systems, real-time bidding, interactive services with strict SLA tails).
✅ **Very large heaps** (tens of GB to multi-TB) where other collectors' pause times scale with heap size.
✅ As of Java 25's generational mode, increasingly viable as a **general-purpose** default too, not just a latency-niche choice — though G1 still usually wins on raw throughput/memory footprint for typical small-object-heavy workloads.

❌ Higher CPU/memory overhead than G1 (recent benchmarks put the throughput penalty around 5–10% versus G1 in non-extreme workloads) — a real cost, not a free lunch.

---

## **3. Shenandoah GC**

### **3.1 How Shenandoah Works**

- **Concurrent evacuation**: unlike older collectors, Shenandoah moves (evacuates) live objects to compact the heap **while the application is running**, using a **Brooks forwarding pointer** (an extra word per object pointing to its "current" location) plus read/write barriers to keep things consistent during the move.
- **Pause times largely independent of heap size** — same design goal as ZGC, achieved via a different mechanism (forwarding pointers vs. colored pointers).
- Developed originally by Red Hat, now a standard OpenJDK project.

### **3.2 What changed — Generational Shenandoah**

- Generational mode was **experimental starting Java 24**.
- **Java 25 (JEP 521)**: generational Shenandoah graduated to a **production feature** — separate young/old generations for the same reason G1 and ZGC adopted them (most objects die young, so treat them differently). Memory/CPU usage in generational mode now matches or beats the older single-generation mode.
- **Still defaults to single-generation mode** as of Java 25 — you opt in explicitly with `-XX:ShenandoahGCMode=generational`. Unlike ZGC, Shenandoah has *not* removed the non-generational mode; both remain supported options.

### **3.3 Shenandoah Heap Structure and Object Movement**

```
+------------------------------------------------------+
|                        Heap                            |
+------------------------------------------------------+
| Young Regions (generational mode only) | Old Regions   |
+------------------------------------------------------+
```

(In default single-generation mode, there's no young/old split — the whole heap is treated uniformly, which is simpler but less efficient for typical allocation patterns than G1/ZGC/generational-Shenandoah.)

### **3.4 Does Shenandoah Cause STW Events?**

✅ Yes, small ones: init-mark and final-mark pauses, similar in spirit to G1/ZGC's brief pauses. No long STW compaction phase, unlike older collectors — that's Shenandoah's core value proposition.

### **3.5 Tuning Shenandoah GC**

- `-XX:+UseShenandoahGC` — enable it.
- `-XX:ShenandoahGCMode=generational` — opt into the new Java 25 generational mode (no longer needs `-XX:+UnlockExperimentalVMOptions`).
- `-XX:+ShenandoahUncommit` — releases unused heap memory back to the OS, useful for bursty/multi-tenant environments.

### **3.6 When to Use Shenandoah**

✅ Low-pause, interactive workloads similar to ZGC's sweet spot.
✅ A good option if you're already on a JDK distribution that ships it and you want low pauses without necessarily needing ZGC's TB-scale heap story.

❌ **Not available in Oracle's own JDK builds** — Oracle ships Serial, Parallel, G1, ZGC, and Epsilon, but *not* Shenandoah. You need an OpenJDK build from Red Hat, Eclipse Adoptium/Temurin, Azul Zulu, Amazon Corretto, etc. This is a genuinely important practical constraint that the original guide didn't mention — "can we even use Shenandoah" is often an infrastructure/vendor question before it's a technical one.
❌ Less battle-tested at true multi-TB heap scale than ZGC.

---

## **4. Comparative Analysis (Java 25)**

| Feature | G1GC | ZGC | Shenandoah |
|---|---|---|---|
| Heap layout | Fixed-size regions | Variable-size ZPages | Regions |
| Generational? | Yes (always) | Yes (only mode, since Java 24) | Optional (Java 25+; default is still single-gen) |
| Typical pause times | Tens of ms | Sub-ms to low single-digit ms | Low single-digit ms |
| STW scope | Young GC + short marking pauses + rare Full GC | Tiny mark-start/mark-end pauses only | Tiny init-mark/final-mark pauses only |
| Heap size sweet spot | Small to very large | Large to huge (TB-scale validated) | Small to large |
| Memory/CPU overhead | Lowest of the three | ~5–10% more than G1 | Similar ballpark to ZGC |
| Ships in Oracle JDK? | Yes | Yes | **No** — OpenJDK vendor builds only |
| Default since | Java 9 (still default) | Never default | Never default |
| Best-fit use case | General purpose / most services | Ultra-low-latency, huge heaps | Low-latency alternative when not tied to Oracle JDK |

---

## **5. Conclusion**

The three collectors have converged more than they've diverged since this was first written: **all three are now generational by default or by option**, closing the throughput gap that used to be the main argument for sticking with G1. The practical decision tree in 2026 looks like:

1. **Don't know your constraints yet / general service** → start with **G1** (still the default, still the most memory-efficient).
2. **Measured, unacceptable tail-latency from GC pauses, and you can afford ~5–10% more CPU/RAM** → **ZGC**.
3. **Want ZGC-like latency but you're locked to a non-Oracle OpenJDK distribution and want to try Shenandoah's approach** → **Shenandoah**, ideally in its new generational mode.
4. **CMS** → not an option anymore since Java 14; if you see it in old docs or a legacy runbook, treat it as a signal that whatever you're reading predates Java 14.
