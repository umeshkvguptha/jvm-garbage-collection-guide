# jvm-garbage-collection-guide

## **Overview**
The **Java Virtual Machine (JVM)** is an integral part of Java that enables the execution of Java bytecode on any platform. It provides runtime services such as memory management, security, and garbage collection (GC).

This guide covers the **JVM architecture**, **Garbage Collection (GC) process**, **types of GC algorithms**, and **key JVM components** in detail.

---

## **1. JVM Architecture**

The **JVM architecture** consists of several key components:

```
+----------------------------+
|        JVM                 |
+----------------------------+
|   Class Loader Subsystem   |
|    - Loading              |
|    - Linking              |
|    - Initialization       |
+----------------------------+
|   Runtime Data Areas       |
|    - Method Area          |
|    - Heap                 |
|    - Stack                |
|    - PC Register          |
|    - Native Method Stack  |
+----------------------------+
|   Execution Engine         |
|    - Interpreter          |
|    - JIT Compiler         |
|    - Garbage Collector    |
+----------------------------+
|   Native Interface (JNI)   |
+----------------------------+
```

### **1.1 Class Loader Subsystem**
- Loads Java class files into memory.
- Manages class loading, linking, and initialization.

### **1.2 Runtime Data Areas**
JVM memory is divided into several areas:
- **Method Area**: Stores class metadata and static variables.
- **Heap**: Stores all objects and class instances.
- **Stack**: Stores method-specific values, local variables, and execution frames.
- **PC Register**: Holds the address of the current instruction being executed.
- **Native Method Stack**: Stores native method calls.

### **1.3 Execution Engine**
- **Interpreter**: Reads and executes bytecode line-by-line.
- **Just-In-Time (JIT) Compiler**: Converts bytecode into native machine code for performance optimization.
- **Garbage Collector (GC)**: Reclaims memory occupied by unreachable objects.

### **1.4 Native Interface (JNI)**
- Allows Java applications to interact with native code (C/C++ libraries).

---

## **2. Garbage Collection (GC) in Java**

### **2.1 What is Garbage Collection?**
Garbage Collection (GC) is the process of automatically managing memory in Java by reclaiming space occupied by unused objects.

**Key Goals:**
- Prevent memory leaks.
- Optimize memory utilization.
- Reduce manual memory management errors.

### **2.2 How GC Works**
Garbage Collection occurs in multiple phases:
1. **Mark**: Identifies live objects (reachable from GC roots).
2. **Sweep**: Removes unreferenced objects from memory.
3. **Compact**: Defragments memory by moving remaining objects closer.

### **2.3 Components of GC**
- **Young Generation**:
    - Stores new objects.
    - Divided into **Eden Space** and **Survivor Spaces (S1 & S2)**.
- **Old Generation**:
    - Stores long-lived objects.
    - Major Garbage Collections (Full GC) occur here.
- **Metaspace**:
    - Stores class metadata.
    - Replaces PermGen in Java 8+.

---

## **3. Types of Garbage Collectors in Java**
Java provides multiple garbage collection strategies, each suited for different applications.

### **3.1 Serial Garbage Collector**
- Uses a single thread to handle garbage collection.
- Best for single-threaded applications.
- Enable using: `-XX:+UseSerialGC`

### **3.2 Parallel Garbage Collector**
- Uses multiple threads for GC, improving performance in multi-threaded applications.
- Suitable for high-throughput applications.
- Enable using: `-XX:+UseParallelGC`

### **3.3 Concurrent Mark-Sweep (CMS) Garbage Collector**
- Low-latency collector that minimizes pause times.
- Runs concurrently with the application threads.
- Enable using: `-XX:+UseConcMarkSweepGC`

### **3.4 G1 (Garbage First) Garbage Collector**
- Default GC since Java 9.
- Uses region-based memory management.
- Reduces stop-the-world pauses.
- Enable using: `-XX:+UseG1GC`

### **3.5 Z Garbage Collector (ZGC)**
- Ultra-low latency GC suitable for large heap sizes.
- Available since Java 11.
- Enable using: `-XX:+UseZGC`

### **3.6 Shenandoah Garbage Collector**
- Focuses on reducing pause times.
- Suitable for low-latency applications.
- Enable using: `-XX:+UseShenandoahGC`

---

## **4. JVM Performance Tuning and GC Monitoring**

### **4.1 JVM GC Tuning Parameters**
- `-Xms<size>`: Initial heap size.
- `-Xmx<size>`: Maximum heap size.
- `-XX:MaxMetaspaceSize=<size>`: Limit Metaspace size.
- `-XX:+PrintGCDetails`: Logs detailed GC information.
- `-XX:+UseStringDeduplication`: Reduces memory usage by deduplicating strings.

### **4.2 GC Monitoring Tools**
- **VisualVM**: Analyze heap dumps and monitor GC behavior.
- **JConsole**: Real-time JVM monitoring.
- **Java Mission Control (JMC)**: Performance profiling and analysis.
- **GC Logs**: Monitor logs to fine-tune GC performance.

---

## **Conclusion**
Understanding JVM and GC is crucial for optimizing Java applications. By selecting the right garbage collector and tuning JVM parameters, developers can improve performance and manage memory efficiently.

For further exploration, consider using profiling tools and experimenting with different GC algorithms based on application needs.

