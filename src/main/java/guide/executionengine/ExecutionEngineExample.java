package guide.executionengine;

public class ExecutionEngineExample {
    public static void main(String[] args) {
        System.out.println("Starting program execution.");

        // Example of interpreted execution
        int sum = calculateSum(10, 20);
        System.out.println("Sum calculated: " + sum);

        // Example of frequently executed code (hotspot for JIT)
        for (int i = 0; i < 100000; i++) {
            performTask();
        }

        System.out.println("Program execution completed.");
    }

    // A method to calculate the sum of two numbers
    public static int calculateSum(int a, int b) {
        return a + b;
    }

    // A method that simulates a frequently executed task
    public static void performTask() {
        // Some dummy operations to simulate workload
        int x = 5 * 10;
        int y = x / 2;
    }
}