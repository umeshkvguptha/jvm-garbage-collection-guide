package guide.classloader;

// Simple program to visualize JVM class loading, linking, and initialization

// Step 1: Define a class to be loaded
class DummyClass{
    // Static block to demonstrate initialization
    static {
        System.out.println("Static block of DummyClass executed: Class is initialized.");
    }

    // Static method to access after initialization
    public static void displayMessage() {
        System.out.println("Method displayMessage() called.");
    }
}

// Main class
public class JVMClassLoadingLinkingInitializationExample {
    public static void main(String[] args) {
        System.out.println("Program started: Main class loaded and initialized.");

        // Step 2: Access the DummyClass
        System.out.println("Accessing DummyClass for the first time:");
        DummyClass.displayMessage();

        // Step 3: Demonstrate that DummyClass is already loaded
        System.out.println("Accessing DummyClass again:");
        DummyClass.displayMessage();

        System.out.println("Program ended.");
    }
}
