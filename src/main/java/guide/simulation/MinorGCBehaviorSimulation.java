package guide.simulation;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Thread.sleep;

public class MinorGCBehaviorSimulation {
    private static final int OBJECT_COUNT = 100000;
    private static final List<Object> memoryLeak = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("Starting Minor GC simulation...");
        simulateFrequentMinorGC();
    }

    // Scenario 1: Frequent Minor GCs
    private static void simulateFrequentMinorGC() {
        List<byte[]> minorGCList = new ArrayList<>();
        for (int i = 0; i < OBJECT_COUNT; i++) {
            // Create small objects repeatedly to fill Young Generation.
            minorGCList.add(new byte[1024]); // 1 KB objects
            if (i % 1000 == 0) {
                System.out.println("Allocated " + i + " KB so far...");
                //sleep(50); // Add a delay to observe GC activity.
            }
        }
    }
}
